;; murakumo.infer.engine — engine adapters: shard plan → concrete process specs.
;;
;; W6 product-shell authority (ADR-260728-w6-engine-oracle-authority):
;; On the JVM, pure cmd-string helpers DELEGATE to precompiled
;; kotoba/infer_engine_core.kotoba → resources/murakumo/oracle/infer_engine_core.kir.edn.
;; Host remains: plan vector walks (workers/serving), variable-arity CSV joins,
;; pr-str prompt quoting, optional extra-args join.

(ns murakumo.infer.engine
  (:require [clojure.string :as str]
            #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :infer-engine)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(def default-rpc-port
  #?(:clj (long (o 'default-rpc-port []))
     :cljs 50052))

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
              cmd #?(:clj (o 'rpc-server-cmd
                             [(str bin-dir)
                              (long port)
                              (str dev)
                              (long (if cache? 1 0))
                              (str (or cache-dir ""))])
                     :cljs (mirror-rpc-worker-cmd bin-dir port dev cache? cache-dir))]]
    {:name (:name node)
     :host (:host node)
     :ip (or (:rpc-ip node) (:ip node))
     :port port
     :cmd cmd}))

(defn tensor-split
  "--tensor-split proportions: RPC workers then head last."
  [plan]
  #?(:clj
     (let [spans (concat (map :span (workers plan)) [(head-span plan)])]
       ;; host join; oracle has fixed tensor-split-3 for 3-way only
       (str/join "," (map #(o 'i64-str [(long %)]) spans)))
     :cljs
     (str/join "," (concat (map :span (workers plan)) [(head-span plan)]))))

(defn rpc-endpoints [worker-cmds]
  #?(:clj
     (str/join ","
               (map (fn [w]
                      (o 'endpoint
                         [(str (or (:ip w) (:host w)))
                          (long (:port w))]))
                    worker-cmds))
     :cljs
     (str/join "," (map #(str (or (:ip %) (:host %)) ":" (:port %)) worker-cmds))))

(defn head-cmd
  "The head's `llama-server` — loads GGUF, drives RPC ring, serves /v1."
  [plan {:keys [bin-dir model-path port rpc-port ctx parallel strategy moe-override extra-args]
         :or {port 8080 rpc-port default-rpc-port ctx 4096 parallel 1
              strategy :pipeline}}]
  (let [ws (rpc-worker-cmds plan {:bin-dir bin-dir :port rpc-port})
        strat (name (or strategy :pipeline))
        rpc-csv (rpc-endpoints ws)
        tsplit (tensor-split plan)]
    #?(:clj
       (str (o 'head-cmd-front [(str bin-dir) (str model-path)])
            (o 'head-cmd-middle [(str rpc-csv) (str strat) (str tsplit)])
            (when moe-override (str " -ot " (pr-str moe-override)))
            (o 'head-cmd-tail [(long ctx) (long parallel) (long port)])
            (when (seq extra-args) (str " " (str/join " " extra-args))))
       :cljs
       (str bin-dir "/llama-server -m " model-path
            " --rpc " rpc-csv
            " --split-mode " (case strategy :tensor "row" "layer")
            " --tensor-split " tsplit
            (when moe-override (str " -ot " (pr-str moe-override)))
            " -ngl 999 -c " ctx " --parallel " parallel
            " --host 0.0.0.0 --port " port
            (when (seq extra-args) (str " " (str/join " " extra-args)))))))

;; ── mlx ring ────────────────────────────────────────────────────────────────

(defn mlx-hosts
  "mlx.launch hosts JSON structure."
  [plan]
  (vec (for [{:keys [node]} (serving plan)]
         {:ssh (:host node) :ips [(or (:ip node) (:host node))]})))

(defn mlx-launch-cmd
  [plan {:keys [hosts-file venv model-repo prompt max-tokens]
         :or {max-tokens 128}}]
  #?(:clj
     (str (o 'mlx-launch-front
             [(str venv) (str hosts-file) (str model-repo) (long max-tokens)])
          " --prompt " (pr-str (or prompt "Name three Japanese cities.")))
     :cljs
     (str venv "/bin/mlx.launch --hosts " hosts-file " --backend ring "
          venv "/bin/mlx_lm.generate -- --model " model-repo
          " --pipeline --max-tokens " max-tokens
          " --prompt " (pr-str (or prompt "Name three Japanese cities.")))))

;; ── mlx-moe ─────────────────────────────────────────────────────────────────

(defn mlx-moe-cmd
  "mu-hashmi/mlx-moe `serve` invocation (single-node)."
  [{:keys [venv model-repo port capacity pin-top-k kv-bits profile warmup extra-args]
    :or {port 8080}}]
  #?(:clj
     (str (o 'mlx-moe-front
             [(str (or venv "")) (str model-repo) (long port)])
          (o 'opt-i64-flag
             [" --capacity" (long (or capacity 0)) (long (if capacity 1 0))])
          (o 'opt-i64-flag
             [" --pin-top-k" (long (or pin-top-k 0)) (long (if pin-top-k 1 0))])
          (o 'opt-i64-flag
             [" --kv-bits" (long (or kv-bits 0)) (long (if kv-bits 1 0))])
          (o 'opt-str-flag
             [" --profile" (str (or profile "")) (long (if profile 1 0))])
          (o 'opt-str-flag
             [" --warmup" (str (or warmup "")) (long (if warmup 1 0))])
          (when (seq extra-args) (str " " (str/join " " extra-args))))
     :cljs
     (str (if venv (str venv "/bin/mlx-moe") "mlx-moe") " serve " model-repo
          " --host 0.0.0.0 --port " port
          (when capacity (str " --capacity " capacity))
          (when pin-top-k (str " --pin-top-k " pin-top-k))
          (when kv-bits (str " --kv-bits " kv-bits))
          (when profile (str " --profile " profile))
          (when warmup (str " --warmup " warmup))
          (when (seq extra-args) (str " " (str/join " " extra-args))))))

;; ── llamacpp-embed ──────────────────────────────────────────────────────────

(defn embed-head-cmd
  "Single-node llama.cpp embedding server."
  [{:keys [bin-dir model-path port ctx pooling parallel extra-args]
    :or {port 8091 ctx 8192 pooling "mean" parallel 4}}]
  #?(:clj
     (str (o 'embed-head-front
             [(str bin-dir) (str model-path) (str pooling) (long ctx)])
          (o 'embed-head-back [(long parallel) (long port)])
          (when (seq extra-args) (str " " (str/join " " extra-args))))
     :cljs
     (str bin-dir "/llama-server -m " model-path
          " --embedding --pooling " pooling
          " -ngl 999 -c " ctx " --parallel " parallel
          " --host 0.0.0.0 --port " port
          (when (seq extra-args) (str " " (str/join " " extra-args))))))

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
