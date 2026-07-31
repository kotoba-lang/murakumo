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

(defn- o-record
  "T5.2: structural host map → call-record (requires shipped oracle)."
  [export host-map field-specs]
  (oracle/require-ready! oid)
  (oracle/call-record oid export host-map field-specs))

(def ^:private endpoint-schema
  [:record :engine/endpoint [[:host :string] [:port :i64]]])

(def ^:private rpc-server-schema
  [:record :engine/rpc-server
   [[:bin-dir :string] [:port :i64] [:device :string]
    [:cache :bool] [:cache-dir :string]]])

(def ^:private head-front-schema
  [:record :engine/head-front [[:bin-dir :string] [:model-path :string]]])

(def ^:private head-middle-schema
  [:record :engine/head-middle
   [[:rpc-csv :string] [:strategy :string] [:tsplit :string]]])

(def ^:private head-tail-schema
  [:record :engine/head-tail [[:ctx :i64] [:parallel :i64] [:port :i64]]])

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
  "One `rpc-server` spec per serving worker node.
   T5.2: structural map → call-record for rpc-server-cmd."
  [plan {:keys [bin-dir port cache-dir device] :or {port default-rpc-port device "MTL0"}}]
  (for [{:keys [node]} (workers plan)
        :let [cache? (not (false? (:rpc-cache? node)))
              dev (or (:rpc-device node) device)
              cmd (o-record 'rpc-server-cmd
                            {:cmd (oracle/record
                                   rpc-server-schema
                                   {:bin-dir bin-dir
                                    :port port
                                    :device dev
                                    :cache cache?
                                    :cache-dir (or cache-dir "")})}
                            [[:cmd :raw]])]]
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
                     (o-record 'i64-str
                               {:n s}
                               [[:n :i64]]))
                   spans))))

(defn rpc-endpoints [worker-cmds]
  (str/join ","
            (map (fn [w]
                   (o-record 'endpoint
                             {:ep (oracle/record
                                   endpoint-schema
                                   {:host (or (:ip w) (:host w))
                                    :port (:port w)})}
                             [[:ep :raw]]))
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
    (str (o-record 'head-cmd-front
                   {:h (oracle/record head-front-schema
                                      {:bin-dir bin-dir :model-path model-path})}
                   [[:h :raw]])
         (o-record 'head-cmd-middle
                   {:h (oracle/record head-middle-schema
                                      {:rpc-csv rpc-csv :strategy strat :tsplit tsplit})}
                   [[:h :raw]])
         (when moe-override (str " -ot " (pr-str moe-override)))
         (o-record 'head-cmd-tail
                   {:h (oracle/record head-tail-schema
                                      {:ctx ctx :parallel parallel :port port})}
                   [[:h :raw]])
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
  (str (o-record 'mlx-launch-front
                 {:venv venv
                  :hosts-file hosts-file
                  :model-repo model-repo
                  :max-tokens max-tokens}
                 [[:venv :string]
                  [:hosts-file :string]
                  [:model-repo :string]
                  [:max-tokens :i64]])
       " --prompt " (pr-str (or prompt "Name three Japanese cities."))))

;; ── mlx-moe ─────────────────────────────────────────────────────────────────

(defn mlx-moe-cmd
  "mu-hashmi/mlx-moe `serve` invocation (single-node)."
  [{:keys [venv model-repo port capacity pin-top-k kv-bits profile warmup extra-args]
    :or {port 8080}}]
  (str (o-record 'mlx-moe-front
                 {:venv (or venv "")
                  :model-repo model-repo
                  :port port}
                 [[:venv :string] [:model-repo :string] [:port :i64]])
       (o-record 'opt-i64-flag
                 {:flag " --capacity"
                  :value (or capacity 0)
                  :present? (boolean capacity)}
                 [[:flag :string] [:value :i64] [:present? :bool]])
       (o-record 'opt-i64-flag
                 {:flag " --pin-top-k"
                  :value (or pin-top-k 0)
                  :present? (boolean pin-top-k)}
                 [[:flag :string] [:value :i64] [:present? :bool]])
       (o-record 'opt-i64-flag
                 {:flag " --kv-bits"
                  :value (or kv-bits 0)
                  :present? (boolean kv-bits)}
                 [[:flag :string] [:value :i64] [:present? :bool]])
       (o-record 'opt-str-flag
                 {:flag " --profile"
                  :value (or profile "")
                  :present? (boolean profile)}
                 [[:flag :string] [:value :string] [:present? :bool]])
       (o-record 'opt-str-flag
                 {:flag " --warmup"
                  :value (or warmup "")
                  :present? (boolean warmup)}
                 [[:flag :string] [:value :string] [:present? :bool]])
       (when (seq extra-args) (str " " (str/join " " extra-args)))))

;; ── llamacpp-embed ──────────────────────────────────────────────────────────

(defn embed-head-cmd
  "Single-node llama.cpp embedding server."
  [{:keys [bin-dir model-path port ctx pooling parallel extra-args]
    :or {port 8091 ctx 8192 pooling "mean" parallel 4}}]
  (str (o-record 'embed-head-front
                 {:bin-dir bin-dir
                  :model-path model-path
                  :pooling pooling
                  :ctx ctx}
                 [[:bin-dir :string]
                  [:model-path :string]
                  [:pooling :string]
                  [:ctx :i64]])
       (o-record 'embed-head-back
                 {:parallel parallel :port port}
                 [[:parallel :i64] [:port :i64]])
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
