;; murakumo.infer.engine — engine adapters: shard plan → concrete process specs.
;;
;; W6 product-shell + T6.4: pure cmd-string helpers require the shipped
;; `:infer-engine` KIR on **every** platform. Host pure mirrors are gone —
;; cljs/nbb must preload shipped KIR before requiring this ns
;; (ADR-260731-w6-t64-infer-sched-rebal-engine-mirror-delete).
;; Host remains: plan vector walks (workers/serving), variable-arity CSV joins,
;; pr-str prompt quoting, optional extra-args join.

(ns murakumo.infer.engine
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-engine)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(def default-rpc-port
  (oracle/i64->host (o 'default-rpc-port [])))

(defn- serving [plan] (filter (comp pos? :span) (:assignments plan)))

(defn workers
  "Serving assignments that need a remote rpc-server — everyone but the head."
  [plan]
  (remove (comp :head? :node) (serving plan)))

(defn head-span [plan]
  (or (some #(when (get-in % [:node :head?]) (:span %)) (:assignments plan)) 0))

(defn rpc-worker-cmds
  "One `rpc-server` spec per serving worker node."
  [plan {:keys [bin-dir port cache-dir device] :or {port default-rpc-port device "MTL0"}}]
  (for [{:keys [node]} (workers plan)
        :let [cache? (not (false? (:rpc-cache? node)))
              dev (or (:rpc-device node) device)
              cmd (o 'rpc-server-cmd
                     [(str bin-dir)
                      (oracle/as-i64 port)
                      (str dev)
                      (boolean cache?)
                      (str (or cache-dir ""))])]]
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
                     (o 'i64-str [(oracle/as-i64 s)]))
                   spans))))

(defn rpc-endpoints [worker-cmds]
  (str/join ","
            (map (fn [w]
                   (o 'endpoint
                      [(str (or (:ip w) (:host w)))
                       (oracle/as-i64 (:port w))]))
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
    (str (o 'head-cmd-front [(str bin-dir) (str model-path)])
         (o 'head-cmd-middle [(str rpc-csv) (str strat) (str tsplit)])
         (when moe-override (str " -ot " (pr-str moe-override)))
         (o 'head-cmd-tail [(oracle/as-i64 ctx) (oracle/as-i64 parallel) (oracle/as-i64 port)])
         (when (seq extra-args) (str " " (str/join " " extra-args))))))

;; ── mlx ring ────────────────────────────────────────────────────────────────

(defn mlx-hosts
  "mlx.launch hosts JSON structure."
  [plan]
  (vec (for [{:keys [node]} (serving plan)]
         {:ssh (:host node) :ips [(or (:ip node) (:host node))]})))

(defn mlx-launch-cmd
  [plan {:keys [hosts-file venv model-repo prompt max-tokens]
         :or {max-tokens 128}}]
  (str (o 'mlx-launch-front
          [(str venv) (str hosts-file) (str model-repo) (oracle/as-i64 max-tokens)])
       " --prompt " (pr-str (or prompt "Name three Japanese cities."))))

;; ── mlx-moe ─────────────────────────────────────────────────────────────────

(defn mlx-moe-cmd
  "mu-hashmi/mlx-moe `serve` invocation (single-node)."
  [{:keys [venv model-repo port capacity pin-top-k kv-bits profile warmup extra-args]
    :or {port 8080}}]
  (str (o 'mlx-moe-front
          [(str (or venv "")) (str model-repo) (oracle/as-i64 port)])
       (o 'opt-i64-flag
          [" --capacity" (oracle/as-i64 (or capacity 0)) (boolean capacity)])
       (o 'opt-i64-flag
          [" --pin-top-k" (oracle/as-i64 (or pin-top-k 0)) (boolean pin-top-k)])
       (o 'opt-i64-flag
          [" --kv-bits" (oracle/as-i64 (or kv-bits 0)) (boolean kv-bits)])
       (o 'opt-str-flag
          [" --profile" (str (or profile "")) (boolean profile)])
       (o 'opt-str-flag
          [" --warmup" (str (or warmup "")) (boolean warmup)])
       (when (seq extra-args) (str " " (str/join " " extra-args)))))

;; ── llamacpp-embed ──────────────────────────────────────────────────────────

(defn embed-head-cmd
  "Single-node llama.cpp embedding server."
  [{:keys [bin-dir model-path port ctx pooling parallel extra-args]
    :or {port 8091 ctx 8192 pooling "mean" parallel 4}}]
  (str (o 'embed-head-front
          [(str bin-dir) (str model-path) (str pooling) (oracle/as-i64 ctx)])
       (o 'embed-head-back [(oracle/as-i64 parallel) (oracle/as-i64 port)])
       (when (seq extra-args) (str " " (str/join " " extra-args)))))

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
