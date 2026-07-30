;; murakumo.infer.engine — engine adapters: shard plan → concrete process specs.
;;
;; W6 product-shell authority (ADR-260728-w6-engine-oracle-authority):
;; pure cmd-string helpers DELEGATE to precompiled
;; kotoba/infer_engine_core.kotoba when oracle loadable (JVM or cljs/nbb).
;; Host remains: plan vector walks (workers/serving), variable-arity CSV joins,
;; pr-str prompt quoting, optional extra-args join. cljs mirrors as fallback.

(ns murakumo.infer.engine
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-engine)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "JVM: require shipped KIR (T6.4). cljs: oracle when ready, else mirror."
  [thunk mirror-thunk]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid})))
       (thunk))
     :cljs
     (if (oracle-ready?)
       (try
         (thunk)
         (catch :default _
           (mirror-thunk)))
       (mirror-thunk))))

(defn- oracle-i64-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/i64->host (oracle/call oid export [])))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/i64->host (oracle/call oid export []))
         mirror)
       (catch :default _
         mirror))))

(def default-rpc-port
  (oracle-i64-const 'default-rpc-port 50052))

(defn- serving [plan] (filter (comp pos? :span) (:assignments plan)))

(defn workers
  "Serving assignments that need a remote rpc-server — everyone but the head."
  [plan]
  (remove (comp :head? :node) (serving plan)))

(defn head-span [plan]
  (or (some #(when (get-in % [:node :head?]) (:span %)) (:assignments plan)) 0))

(defn- mirror-rpc-worker-cmd [bin-dir port device cache? cache-dir]
  (str bin-dir "/rpc-server -H 0.0.0.0 -p " port
       " -d " device
       (when cache? (str " -c" (when cache-dir (str " " cache-dir))))))

(defn rpc-worker-cmds
  "One `rpc-server` spec per serving worker node."
  [plan {:keys [bin-dir port cache-dir device] :or {port default-rpc-port device "MTL0"}}]
  (for [{:keys [node]} (workers plan)
        :let [cache? (not (false? (:rpc-cache? node)))
              dev (or (:rpc-device node) device)
              cmd (try-oracle
                   #(o 'rpc-server-cmd
                       [(str bin-dir)
                        (oracle/as-i64 port)
                        (str dev)
                        (oracle/as-i64 (if cache? 1 0))
                        (str (or cache-dir ""))])
                   #(mirror-rpc-worker-cmd bin-dir port dev cache? cache-dir))]]
    {:name (:name node)
     :host (:host node)
     :ip (or (:rpc-ip node) (:ip node))
     :port port
     :cmd cmd}))

(defn tensor-split
  "--tensor-split proportions: RPC workers then head last."
  [plan]
  (let [spans (concat (map :span (workers plan)) [(head-span plan)])]
    ;; host join; oracle has fixed tensor-split-3 for 3-way only / i64-str
    (str/join ","
              (map (fn [s]
                     (try-oracle
                      #(o 'i64-str [(oracle/as-i64 s)])
                      #(str s)))
                   spans))))

(defn rpc-endpoints [worker-cmds]
  (str/join ","
            (map (fn [w]
                   (try-oracle
                    #(o 'endpoint
                        [(str (or (:ip w) (:host w)))
                         (oracle/as-i64 (:port w))])
                    #(str (or (:ip w) (:host w)) ":" (:port w))))
                 worker-cmds)))

(defn head-cmd
  "The head's `llama-server` — loads GGUF, drives RPC ring, serves /v1."
  [plan {:keys [bin-dir model-path port rpc-port ctx parallel strategy moe-override extra-args]
         :or {port 8080 rpc-port default-rpc-port ctx 4096 parallel 1
              strategy :pipeline}}]
  (let [ws (rpc-worker-cmds plan {:bin-dir bin-dir :port rpc-port})
        strat (name (or strategy :pipeline))
        rpc-csv (rpc-endpoints ws)
        tsplit (tensor-split plan)]
    (try-oracle
     (fn []
       (str (o 'head-cmd-front [(str bin-dir) (str model-path)])
            (o 'head-cmd-middle [(str rpc-csv) (str strat) (str tsplit)])
            (when moe-override (str " -ot " (pr-str moe-override)))
            (o 'head-cmd-tail [(oracle/as-i64 ctx) (oracle/as-i64 parallel) (oracle/as-i64 port)])
            (when (seq extra-args) (str " " (str/join " " extra-args)))))
     (fn []
       (str bin-dir "/llama-server -m " model-path
            " --rpc " rpc-csv
            " --split-mode " (case strategy :tensor "row" "layer")
            " --tensor-split " tsplit
            (when moe-override (str " -ot " (pr-str moe-override)))
            " -ngl 999 -c " ctx " --parallel " parallel
            " --host 0.0.0.0 --port " port
            (when (seq extra-args) (str " " (str/join " " extra-args))))))))

;; ── mlx ring ────────────────────────────────────────────────────────────────

(defn mlx-hosts
  "mlx.launch hosts JSON structure."
  [plan]
  (vec (for [{:keys [node]} (serving plan)]
         {:ssh (:host node) :ips [(or (:ip node) (:host node))]})))

(defn mlx-launch-cmd
  [plan {:keys [hosts-file venv model-repo prompt max-tokens]
         :or {max-tokens 128}}]
  (try-oracle
   #(str (o 'mlx-launch-front
            [(str venv) (str hosts-file) (str model-repo) (oracle/as-i64 max-tokens)])
         " --prompt " (pr-str (or prompt "Name three Japanese cities.")))
   #(str venv "/bin/mlx.launch --hosts " hosts-file " --backend ring "
         venv "/bin/mlx_lm.generate -- --model " model-repo
         " --pipeline --max-tokens " max-tokens
         " --prompt " (pr-str (or prompt "Name three Japanese cities.")))))

;; ── mlx-moe ─────────────────────────────────────────────────────────────────

(defn mlx-moe-cmd
  "mu-hashmi/mlx-moe `serve` invocation (single-node)."
  [{:keys [venv model-repo port capacity pin-top-k kv-bits profile warmup extra-args]
    :or {port 8080}}]
  (try-oracle
   (fn []
     (str (o 'mlx-moe-front
             [(str (or venv "")) (str model-repo) (oracle/as-i64 port)])
          (o 'opt-i64-flag
             [" --capacity" (oracle/as-i64 (or capacity 0)) (oracle/as-i64 (if capacity 1 0))])
          (o 'opt-i64-flag
             [" --pin-top-k" (oracle/as-i64 (or pin-top-k 0)) (oracle/as-i64 (if pin-top-k 1 0))])
          (o 'opt-i64-flag
             [" --kv-bits" (oracle/as-i64 (or kv-bits 0)) (oracle/as-i64 (if kv-bits 1 0))])
          (o 'opt-str-flag
             [" --profile" (str (or profile "")) (oracle/as-i64 (if profile 1 0))])
          (o 'opt-str-flag
             [" --warmup" (str (or warmup "")) (oracle/as-i64 (if warmup 1 0))])
          (when (seq extra-args) (str " " (str/join " " extra-args)))))
   (fn []
     (str (if venv (str venv "/bin/mlx-moe") "mlx-moe") " serve " model-repo
          " --host 0.0.0.0 --port " port
          (when capacity (str " --capacity " capacity))
          (when pin-top-k (str " --pin-top-k " pin-top-k))
          (when kv-bits (str " --kv-bits " kv-bits))
          (when profile (str " --profile " profile))
          (when warmup (str " --warmup " warmup))
          (when (seq extra-args) (str " " (str/join " " extra-args)))))))

;; ── llamacpp-embed ──────────────────────────────────────────────────────────

(defn embed-head-cmd
  "Single-node llama.cpp embedding server."
  [{:keys [bin-dir model-path port ctx pooling parallel extra-args]
    :or {port 8091 ctx 8192 pooling "mean" parallel 4}}]
  (try-oracle
   (fn []
     (str (o 'embed-head-front
             [(str bin-dir) (str model-path) (str pooling) (oracle/as-i64 ctx)])
          (o 'embed-head-back [(oracle/as-i64 parallel) (oracle/as-i64 port)])
          (when (seq extra-args) (str " " (str/join " " extra-args)))))
   (fn []
     (str bin-dir "/llama-server -m " model-path
          " --embedding --pooling " pooling
          " -ngl 999 -c " ctx " --parallel " parallel
          " --host 0.0.0.0 --port " port
          (when (seq extra-args) (str " " (str/join " " extra-args)))))))

(defn commands
  "Plan + engine + opts → {:workers [...] :head {...}} process specs."
  [plan engine opts]
  (case engine
    :llamacpp-rpc {:workers (vec (rpc-worker-cmds plan opts))
                   :head {:cmd (head-cmd plan opts)}}
    :mlx-ring {:hosts (mlx-hosts plan)
               :head {:cmd (mlx-launch-cmd plan opts)}}
    :mlx-moe {:head {:cmd (mlx-moe-cmd opts)}}
    :llamacpp-embed {:head {:cmd (embed-head-cmd opts)}}))
