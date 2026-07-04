;; murakumo.infer.engine — engine adapters: shard plan → concrete process specs.
;;
;; A plan (murakumo.infer.plan) is engine-agnostic layer math. This namespace
;; turns it into the exact commands each participant runs, per engine:
;;
;;   :llamacpp-rpc  llama.cpp distributed — every worker runs `rpc-server`
;;                  (ggml RPC backend, Metal/CUDA/CPU alike → works on the mixed
;;                  macOS/linux fleet), the head runs `llama-server` with
;;                  --rpc <endpoints> --tensor-split <spans> and serves the
;;                  OpenAI-compatible API. Weights stream head→workers at load
;;                  (cacheable node-side with `-c`), tokens cost one activation
;;                  hop per shard boundary — the same pipeline-parallel wire
;;                  profile ADR-2605300000 picked for the 1 GbE fleet.
;;
;;   :mlx-ring      mlx_lm pipeline parallel via `mlx.launch --backend ring`
;;                  (all-Apple fleets; MLX-format checkpoints).
;;
;;   :mlx-moe       mu-hashmi/mlx-moe single-node MoE serving — no ring, ONE
;;                  process on the plan's sole (:head?) node; inactive experts
;;                  page in from SSD as the router selects them instead of
;;                  sharding layers across the fleet (murakumo.infer.moe).
;;
;; Everything here is pure string/data assembly — runnable and testable anywhere.

(ns murakumo.infer.engine
  (:require [clojure.string :as str]))

(def default-rpc-port 50052)

(defn- serving [plan] (filter (comp pos? :span) (:assignments plan)))

(defn workers
  "Serving assignments that need a remote rpc-server — i.e. everyone but the
   head (the head's slice rides its own local GPU, marked :head? on the node)."
  [plan]
  (remove (comp :head? :node) (serving plan)))

(defn head-span [plan]
  (or (some #(when (get-in % [:node :head?]) (:span %)) (:assignments plan)) 0))

(defn rpc-worker-cmds
  "One `rpc-server` spec per serving worker node.
   `-c` caches streamed tensors on the node's disk (skip on disk-tight nodes)."
  [plan {:keys [bin-dir port cache-dir device] :or {port default-rpc-port device "MTL0"}}]
  (for [{:keys [node]} (workers plan)
        :let [cache? (not (false? (:rpc-cache? node)))]]
    {:name (:name node)
     :host (:host node)
     :ip (or (:rpc-ip node) (:ip node))
     :port port
     ;; -d pins the worker to ONE device: rpc-server otherwise also exports its
     ;; BLAS/CPU backends and the head schedules ops onto them that they cannot
     ;; run (live fleet: RMS_NORM → ggml_backend_blas abort).
     :cmd (str bin-dir "/rpc-server -H 0.0.0.0 -p " port
               " -d " (or (:rpc-device node) device)
               (when cache? (str " -c"
                                 (when cache-dir (str " " cache-dir)))))}))

(defn tensor-split
  "--tensor-split proportions in DEVICE order: RPC workers as listed, the head's
   own device last (span 0 head = pure conductor)."
  [plan]
  (str/join "," (concat (map :span (workers plan)) [(head-span plan)])))

(defn rpc-endpoints [worker-cmds]
  (str/join "," (map #(str (or (:ip %) (:host %)) ":" (:port %)) worker-cmds)))

(defn head-cmd
  "The head's `llama-server` — loads the GGUF, drives the RPC ring, serves
   OpenAI-compatible /v1 on :port. The head's own slice stays on its local GPU.

   :strategy (murakumo.infer.plan/choose-strategy) maps onto llama.cpp:
     :pipeline → --split-mode layer  (contiguous layer shards; the default)
     :tensor   → --split-mode row    (row-parallel matmuls, all-reduce per layer)
     :expert   → --split-mode layer + :moe-override (-ot regex) pinning expert
                 tensors; whole-expert placement rides layer splits today —
                 true cross-node token routing is an upstream llama.cpp gap."
  [plan {:keys [bin-dir model-path port rpc-port ctx parallel strategy moe-override extra-args]
         :or {port 8080 rpc-port default-rpc-port ctx 4096 parallel 1
              strategy :pipeline}}]
  (let [ws (rpc-worker-cmds plan {:bin-dir bin-dir :port rpc-port})]
    (str bin-dir "/llama-server -m " model-path
         " --rpc " (rpc-endpoints ws)
         " --split-mode " (case strategy :tensor "row" "layer")
         " --tensor-split " (tensor-split plan)
         (when moe-override (str " -ot " (pr-str moe-override)))
         " -ngl 999 -c " ctx " --parallel " parallel
         " --host 0.0.0.0 --port " port
         (when (seq extra-args) (str " " (str/join " " extra-args))))))

;; ── mlx ring ────────────────────────────────────────────────────────────────

(defn mlx-hosts
  "mlx.launch hosts JSON structure (write with your JSON encoder of choice).
   The head IS a ring rank in MLX (rank 0 = the launcher's own machine)."
  [plan]
  (vec (for [{:keys [node]} (serving plan)]
         {:ssh (:host node) :ips [(or (:ip node) (:host node))]})))

(defn mlx-launch-cmd
  [plan {:keys [hosts-file venv model-repo prompt max-tokens]
         :or {max-tokens 128}}]
  (str venv "/bin/mlx.launch --hosts " hosts-file " --backend ring "
       venv "/bin/mlx_lm.generate -- --model " model-repo
       " --pipeline --max-tokens " max-tokens
       " --prompt " (pr-str (or prompt "Name three Japanese cities."))))

;; ── mlx-moe (single-node, SSD-paged experts) ───────────────────────────────

(defn mlx-moe-cmd
  "mu-hashmi/mlx-moe `serve` invocation. No --rpc/--hosts/ring — one process,
   one node; `:capacity` (murakumo.infer.moe/capacity-for-usable) and
   `:kv-bits` are optional, mlx-moe auto-selects capacity from live RAM when
   omitted. :extra-args is an escape hatch for mlx-moe flags that land before
   murakumo grows a named key."
  [{:keys [venv model-repo port capacity pin-top-k kv-bits profile warmup extra-args]
    :or {port 8080}}]
  (str (if venv (str venv "/bin/mlx-moe") "mlx-moe") " serve " model-repo
       " --host 0.0.0.0 --port " port
       (when capacity (str " --capacity " capacity))
       (when pin-top-k (str " --pin-top-k " pin-top-k))
       (when kv-bits (str " --kv-bits " kv-bits))
       (when profile (str " --profile " profile))
       (when warmup (str " --warmup " warmup))
       (when (seq extra-args) (str " " (str/join " " extra-args)))))

(defn commands
  "Plan + engine + opts → {:workers [...] :head {...}} process specs."
  [plan engine opts]
  (case engine
    :llamacpp-rpc {:workers (vec (rpc-worker-cmds plan opts))
                   :head {:cmd (head-cmd plan opts)}}
    :mlx-ring {:hosts (mlx-hosts plan)
               :head {:cmd (mlx-launch-cmd plan opts)}}
    ;; :mlx-moe ignores `plan` (no ring to conduct) — the sole node + capacity
    ;; already live in opts (murakumo.infer.moe/plan → cmd-serve-moe).
    :mlx-moe {:head {:cmd (mlx-moe-cmd opts)}}))
