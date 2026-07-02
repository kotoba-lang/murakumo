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
     :ip (:ip node)
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
   OpenAI-compatible /v1 on :port. The head's own slice stays on its local GPU."
  [plan {:keys [bin-dir model-path port rpc-port ctx parallel]
         :or {port 8080 rpc-port default-rpc-port ctx 4096 parallel 1}}]
  (let [ws (rpc-worker-cmds plan {:bin-dir bin-dir :port rpc-port})]
    (str bin-dir "/llama-server -m " model-path
         " --rpc " (rpc-endpoints ws)
         " --tensor-split " (tensor-split plan)
         " -ngl 999 -c " ctx " --parallel " parallel
         " --host 0.0.0.0 --port " port)))

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

(defn commands
  "Plan + engine + opts → {:workers [...] :head {...}} process specs."
  [plan engine opts]
  (case engine
    :llamacpp-rpc {:workers (vec (rpc-worker-cmds plan opts))
                   :head {:cmd (head-cmd plan opts)}}
    :mlx-ring {:hosts (mlx-hosts plan)
               :head {:cmd (mlx-launch-cmd plan opts)}}))
