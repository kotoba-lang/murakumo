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
;;   :llamacpp-embed  single-node llama.cpp embedding server (ADR-2607192200
;;                  2026-07-19 addendum) — like :mlx-moe, no --rpc/--tensor-split
;;                  ring: the embedding model (BGE-M3-class, ~1024-dim dense) is
;;                  small enough for one node. `--embedding --pooling` switches
;;                  llama-server into embedding-serving mode (OpenAI-compatible
;;                  /v1/embeddings) on a port separate from any chat head, so
;;                  murakumo-main chat traffic (:llamacpp-rpc's head-cmd) is
;;                  never touched by this engine.
;;
;; Everything here is pure string/data assembly — runnable and testable anywhere.
;;
;; W6 product-shell authority (ADR-260728-w6-engine-oracle-authority):
;; On the JVM, pure cmd/string helpers DELEGATE to precompiled
;; kotoba/infer_engine_core.kotoba KIR. Plan-walking (workers/serving/spans)
;; and pr-str/extra-args joins stay host/cljc.

(ns murakumo.infer.engine
  "Engine cmd assembly uses kotoba/infer_engine_core.kotoba authority on JVM."
  (:require [clojure.string :as str]
            #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :infer-engine)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

;; ── host-mirror pure helpers (cljs fallback + semantic documentation) ──

(def ^:private mirror-default-rpc-port 50052)

(defn- mirror-i64-str [n]
  (str (long n)))

(defn- mirror-split-mode-name [strategy]
  (case strategy :tensor "row" "layer"))

(defn- mirror-endpoint [host port]
  (str host ":" port))

(defn- mirror-rpc-server-cmd [bin-dir port device cache? cache-dir]
  (str bin-dir "/rpc-server -H 0.0.0.0 -p " port
       " -d " device
       (when cache? (str " -c" (when (seq cache-dir) (str " " cache-dir))))))

(defn- mirror-tensor-split-csv [spans]
  (str/join "," (map mirror-i64-str spans)))

(defn- mirror-rpc-csv [eps]
  (str/join "," eps))

(defn- mirror-head-cmd [bin-dir model-path rpc-csv strategy tensor-csv
                        ctx parallel port moe-override extra-args]
  (str bin-dir "/llama-server -m " model-path
       " --rpc " rpc-csv
       " --split-mode " (mirror-split-mode-name strategy)
       " --tensor-split " tensor-csv
       (when moe-override (str " -ot " (pr-str moe-override)))
       " -ngl 999 -c " ctx " --parallel " parallel
       " --host 0.0.0.0 --port " port
       (when (seq extra-args) (str " " (str/join " " extra-args)))))

(defn- mirror-mlx-launch-cmd [venv hosts-file model-repo max-tokens prompt]
  (str venv "/bin/mlx.launch --hosts " hosts-file " --backend ring "
       venv "/bin/mlx_lm.generate -- --model " model-repo
       " --pipeline --max-tokens " max-tokens
       " --prompt " (pr-str (or prompt "Name three Japanese cities."))))

(defn- mirror-mlx-moe-cmd
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

(defn- mirror-embed-head-cmd
  [{:keys [bin-dir model-path port ctx pooling parallel extra-args]
    :or {port 8091 ctx 8192 pooling "mean" parallel 4}}]
  (str bin-dir "/llama-server -m " model-path
       " --embedding --pooling " pooling
       " -ngl 999 -c " ctx " --parallel " parallel
       " --host 0.0.0.0 --port " port
       (when (seq extra-args) (str " " (str/join " " extra-args)))))

;; ── product defaults ─────────────────────────────────────────────────

(def default-rpc-port
  "Default ggml RPC worker port (oracle `default-rpc-port` on JVM)."
  #?(:clj (long (o 'default-rpc-port []))
     :cljs mirror-default-rpc-port))

;; ── plan walks stay host ─────────────────────────────────────────────

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
   `-c` caches streamed tensors on the node's disk (skip on disk-tight nodes).
   JVM: `:cmd` via kotoba `rpc-server-cmd`."
  [plan {:keys [bin-dir port cache-dir device] :or {port default-rpc-port device "MTL0"}}]
  (for [{:keys [node]} (workers plan)
        :let [cache? (not (false? (:rpc-cache? node)))
              dev (or (:rpc-device node) device)
              cdir (or cache-dir "")]]
    {:name (:name node)
     :host (:host node)
     :ip (or (:rpc-ip node) (:ip node))
     :port port
     ;; -d pins the worker to ONE device: rpc-server otherwise also exports its
     ;; BLAS/CPU backends and the head schedules ops onto them that they cannot
     ;; run (live fleet: RMS_NORM → ggml_backend_blas abort).
     :cmd #?(:clj (o 'rpc-server-cmd
                     [(str bin-dir) (long port) (str dev)
                      (if cache? 1 0) (str cdir)])
             :cljs (mirror-rpc-server-cmd bin-dir port dev cache?
                                          (when cache? cache-dir)))}))

(defn tensor-split
  "--tensor-split proportions in DEVICE order: RPC workers as listed, the head's
   own device last (span 0 head = pure conductor).
   JVM: i64 formatting via oracle `i64-str` (join stays host)."
  [plan]
  (let [spans (concat (map :span (workers plan)) [(head-span plan)])]
    #?(:clj (str/join "," (map #(o 'i64-str [(long %)]) spans))
       :cljs (mirror-tensor-split-csv spans))))

(defn rpc-endpoints
  "Comma-joined host:port list. JVM: each element via kotoba `endpoint`."
  [worker-cmds]
  #?(:clj (str/join ","
                    (map (fn [w]
                           (o 'endpoint
                              [(str (or (:ip w) (:host w)))
                               (long (:port w))]))
                         worker-cmds))
     :cljs (mirror-rpc-csv
            (map #(mirror-endpoint (or (:ip %) (:host %)) (:port %))
                 worker-cmds))))

(defn head-cmd
  "The head's `llama-server` — loads the GGUF, drives the RPC ring, serves
   OpenAI-compatible /v1 on :port. The head's own slice stays on its local GPU.

   :strategy (murakumo.infer.plan/choose-strategy) maps onto llama.cpp:
     :pipeline → --split-mode layer  (contiguous layer shards; the default)
     :tensor   → --split-mode row    (row-parallel matmuls, all-reduce per layer)
     :expert   → --split-mode layer + :moe-override (-ot regex) pinning expert
                 tensors; whole-expert placement rides layer splits today —
                 true cross-node token routing is an upstream llama.cpp gap.

   JVM: front/middle/tail pure fragments via oracle; moe pr-str + extra-args host."
  [plan {:keys [bin-dir model-path port rpc-port ctx parallel strategy moe-override extra-args]
         :or {port 8080 rpc-port default-rpc-port ctx 4096 parallel 1
              strategy :pipeline}}]
  (let [ws (rpc-worker-cmds plan {:bin-dir bin-dir :port rpc-port})
        rpc-csv (rpc-endpoints ws)
        tcsv (tensor-split plan)
        strat-s (name strategy)]
    #?(:clj
       (str (o 'head-cmd-front [(str bin-dir) (str model-path)])
            (o 'head-cmd-middle [(str rpc-csv) (str strat-s) (str tcsv)])
            (when moe-override (str " -ot " (pr-str moe-override)))
            (o 'head-cmd-tail [(long ctx) (long parallel) (long port)])
            (when (seq extra-args) (str " " (str/join " " extra-args))))
       :cljs
       (mirror-head-cmd bin-dir model-path rpc-csv strategy tcsv
                        ctx parallel port moe-override extra-args))))

;; ── mlx ring ────────────────────────────────────────────────────────────────

(defn mlx-hosts
  "mlx.launch hosts JSON structure (write with your JSON encoder of choice).
   The head IS a ring rank in MLX (rank 0 = the launcher's own machine)."
  [plan]
  (vec (for [{:keys [node]} (serving plan)]
         {:ssh (:host node) :ips [(or (:ip node) (:host node))]})))

(defn mlx-launch-cmd
  "JVM: front via kotoba `mlx-launch-front`; prompt pr-str stays host."
  [plan {:keys [hosts-file venv model-repo prompt max-tokens]
         :or {max-tokens 128}}]
  #?(:clj
     (str (o 'mlx-launch-front
             [(str venv) (str hosts-file) (str model-repo) (long max-tokens)])
          " --prompt " (pr-str (or prompt "Name three Japanese cities.")))
     :cljs
     (mirror-mlx-launch-cmd venv hosts-file model-repo max-tokens prompt)))

;; ── mlx-moe (single-node, SSD-paged experts) ───────────────────────────────

(defn mlx-moe-cmd
  "mu-hashmi/mlx-moe `serve` invocation. No --rpc/--hosts/ring — one process,
   one node; `:capacity` (murakumo.infer.moe/capacity-for-usable) and
   `:kv-bits` are optional, mlx-moe auto-selects capacity from live RAM when
   omitted. :extra-args is an escape hatch for mlx-moe flags that land before
   murakumo grows a named key.

   JVM: front + opt flags via oracle; extra-args join stays host."
  [{:keys [venv model-repo port capacity pin-top-k kv-bits profile warmup extra-args]
    :or {port 8080} :as opts}]
  #?(:clj
     (str (o 'mlx-moe-front [(str (or venv "")) (str model-repo) (long port)])
          (o 'opt-i64-flag [" --capacity" (long (or capacity 0)) (if capacity 1 0)])
          (o 'opt-i64-flag [" --pin-top-k" (long (or pin-top-k 0)) (if pin-top-k 1 0)])
          (o 'opt-i64-flag [" --kv-bits" (long (or kv-bits 0)) (if kv-bits 1 0)])
          (o 'opt-str-flag [" --profile" (str (or profile "")) (if profile 1 0)])
          (o 'opt-str-flag [" --warmup" (str (or warmup "")) (if warmup 1 0)])
          (when (seq extra-args) (str " " (str/join " " extra-args))))
     :cljs (mirror-mlx-moe-cmd opts)))

;; ── llamacpp-embed (single-node, no ring — mirrors :mlx-moe's positioning) ──

(defn embed-head-cmd
  "Single-node llama.cpp embedding server — model is small enough that no
   --rpc/--tensor-split ring is needed (mirrors :mlx-moe's single-node
   positioning). --embedding + --pooling switch llama-server into
   embedding-serving mode (OpenAI-compatible /v1/embeddings), on a port
   separate from any chat head so murakumo-main chat traffic is never
   touched by this addition.

   JVM: front+back via oracle; extra-args join stays host."
  [{:keys [bin-dir model-path port ctx pooling parallel extra-args]
    :or {port 8091 ctx 8192 pooling "mean" parallel 4} :as opts}]
  #?(:clj
     (str (o 'embed-head-front
             [(str bin-dir) (str model-path) (str pooling) (long ctx)])
          (o 'embed-head-back [(long parallel) (long port)])
          (when (seq extra-args) (str " " (str/join " " extra-args))))
     :cljs (mirror-embed-head-cmd opts)))

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
    :mlx-moe {:head {:cmd (mlx-moe-cmd opts)}}
    ;; :llamacpp-embed also ignores `plan` (single node, no ring) — same
    ;; single-node posture as :mlx-moe, for the embedding engine instead.
    :llamacpp-embed {:head {:cmd (embed-head-cmd opts)}}))
