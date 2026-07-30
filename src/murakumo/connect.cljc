;; murakumo.connect — read the single connectivity description (connect.edn) and
;; answer "can this node reach that client class on that plane?".
;;
;; The decision helpers are portable .cljc and pure. load-connect is a small host
;; convenience for the bb/JVM CLI; callers that need strict portability can pass
;; the parsed connect map directly.
;;
;; W6 product-shell (ADR-260728-w6-connect-plane-tokens-pure-oracle):
;; class/plane pure helpers + class-native/plane-read/plane-live tokens DELEGATE
;; to kotoba connect_core when oracle is loadable (JVM classpath or cljs/nbb —
;; ADR-260728-w6-cljs-oracle-load). Host remains: class-transports lookup, set
;; intersection projection. cljs mirrors remain fallback when not ready.

(ns murakumo.connect
  "Connectivity description helpers.
   W6 product-shell: class/plane tokens + decision pure helpers via kotoba connect_core."
  (:require [clojure.set :as set]
            [murakumo.config :as config]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :connect)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "Run oracle body; on failure use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn- oracle-str-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

;; ── residual class / plane name tokens ───────────────────────────────

(def ^:private mirror-class-native "native")
(def ^:private mirror-plane-read "read")
(def ^:private mirror-plane-live "live")

(def class-native
  "Default node class name token. Kotoba when ready."
  (oracle-str-const 'class-native mirror-class-native))

(def plane-read
  "Read plane name token. Kotoba when ready."
  (oracle-str-const 'plane-read mirror-plane-read))

(def plane-live
  "Live plane name token. Kotoba when ready."
  (oracle-str-const 'plane-live mirror-plane-live))

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
  "Kotoba `default-class-name` → keyword when oracle ready."
  [connect]
  (try-oracle
   #(keyword (o 'default-class-name
                [(if-let [c (:default-class connect)] (name c) "")]))
   #(or (:default-class connect) (keyword class-native))))

(defn node-class
  "Kotoba `node-class-name` → keyword when oracle ready."
  [connect node]
  (try-oracle
   #(keyword (o 'node-class-name
                [(if-let [c (:class node)] (name c) "")
                 (if-let [c (:default-class connect)] (name c) "")]))
   #(or (:class node) (default-class connect))))

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
   Kotoba `serves-plane?` when oracle ready (Product Value ABI optional flags)."
  [connect node reach]
  (let [{:keys [class plane]} (parse-reach reach)
        ncls (node-class connect node)
        http? (when (some #{:http} (class-transports connect ncls (keyword plane-read))) 1)
        common? (when (seq (set/intersection
                            (set (class-transports connect ncls (keyword plane-live)))
                            (set (class-transports connect class (keyword plane-live)))))
                  1)]
    (try-oracle
     #(oracle/bool->host
       (o 'serves-plane?
          [(name plane)
           (oracle/option-i64 http?)
           (oracle/option-i64 common?)]))
     #(let [plane-name (name plane)]
        (cond
          (= plane-name plane-read)
          (boolean (some #{:http} (class-transports connect ncls (keyword plane-read))))
          (= plane-name plane-live)
          (boolean (seq (set/intersection
                         (set (class-transports connect ncls (keyword plane-live)))
                         (set (class-transports connect class (keyword plane-live))))))
          :else false)))))

(defn serves-all?
  "True if `node` satisfies every reach requirement (empty => trivially true)."
  [connect node reaches]
  (every? #(serves-reach? connect node %) reaches))
