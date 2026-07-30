;; murakumo.connect — read the single connectivity description (connect.edn) and
;; answer "can this node reach that client class on that plane?".
;;
;; The decision helpers are portable .cljc and pure. load-connect is a small host
;; convenience for the bb/JVM CLI; callers that need strict portability can pass
;; the parsed connect map directly.
;;
;; W6 product-shell + T6.4: class/plane pure helpers + class-native/plane-read/
;; plane-live tokens require the shipped `:connect` KIR on **every** platform.
;; Host pure mirrors are gone — cljs/nbb must preload shipped KIR (resources/
;; via nbb cwd, register-kir!, or set-resource-loader!) before requiring this ns
;; (ADR-260731-w6-t64-connect-mirror-delete).
;; Host remains: class-transports lookup, set intersection projection, load-connect.

(ns murakumo.connect
  "Connectivity description helpers.
   W6 product-shell: class/plane tokens + decision pure helpers via kotoba connect_core.
   T6.4: shipped :connect KIR required on every platform (no cljs pure mirrors)."
  (:require [clojure.set :as set]
            [murakumo.config :as config]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :connect)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

;; ── residual class / plane name tokens ───────────────────────────────

(def class-native
  "Default node class name token. Kotoba SSoT (requires oracle)."
  (o 'class-native []))

(def plane-read
  "Read plane name token. Kotoba SSoT (requires oracle)."
  (o 'plane-read []))

(def plane-live
  "Live plane name token. Kotoba SSoT (requires oracle)."
  (o 'plane-live []))

(defn load-connect
  "Read connect.edn (nil if absent — reach constraints then degrade to no-op).
   connect.edn is Datomic/Datascript tx-data (edn-datomize.cljs wrap-map-keep-ns!,
   promote-ns \"connect-doc\" for the previously-bare :planes/:classes/
   :default-class/:roadmap keys — the pre-existing :connect/* namespace is left
   as-is); tx-data->map reconstitutes the plain map the reach helpers expect."
  ([] (load-connect config/default-connect-path))
  ([path] (some-> (config/read-edn-file-or path nil)
                   (config/tx-data->map "connect-doc"))))

(defn default-class
  "Kotoba `default-class-name` → keyword."
  [connect]
  (keyword (o 'default-class-name
              [(if-let [c (:default-class connect)] (name c) "")])))

(defn node-class
  "Kotoba `node-class-name` → keyword."
  [connect node]
  (keyword (o 'node-class-name
              [(if-let [c (:class node)] (name c) "")
               (if-let [c (:default-class connect)] (name c) "")])))

(defn class-transports
  "Transports a node-class speaks on `plane` (:read | :live)."
  [connect class plane]
  (get-in connect [:classes class plane] []))

(defn- parse-reach
  "Normalise a reach token. `:browser/live` -> {:class :browser :plane :live};
   a map passes through unchanged."
  [r]
  (if (map? r)
    r
    {:class (keyword (namespace r)) :plane (keyword (name r))}))

(defn serves-reach?
  "Can `node` serve a client of `(:class reach)` on `(:plane reach)`?
     :read — node speaks :http (universal CID pull).
     :live — node and target client class share at least one live transport.
   Kotoba `serves-plane?` (profile-5 :bool flags for http?/common?)."
  [connect node reach]
  (let [{:keys [class plane]} (parse-reach reach)
        ncls (node-class connect node)
        http? (boolean (some #{:http}
                             (class-transports connect ncls (keyword plane-read))))
        common? (boolean (seq (set/intersection
                               (set (class-transports connect ncls (keyword plane-live)))
                               (set (class-transports connect class (keyword plane-live))))))]
    (oracle/bool->host
     (o 'serves-plane?
        [(name plane) http? common?]))))

(defn serves-all?
  "True if `node` satisfies every reach requirement (empty => trivially true)."
  [connect node reaches]
  (every? #(serves-reach? connect node %) reaches))
