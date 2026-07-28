;; murakumo.connect — read the single connectivity description (connect.edn) and
;; answer "can this node reach that client class on that plane?".
;;
;; The decision helpers are portable .cljc and pure. load-connect is a small host
;; convenience for the bb/JVM CLI; callers that need strict portability can pass
;; the parsed connect map directly.

(ns murakumo.connect
  "Connectivity description helpers.
   W6 product-shell: class/plane decision pure helpers via kotoba connect_core."
  (:require [clojure.set :as set]
            [murakumo.config :as config]
            #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :connect)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

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
  "JVM: kotoba `default-class-name` → keyword."
  [connect]
  #?(:clj (keyword (o 'default-class-name
                      [(if-let [c (:default-class connect)] (name c) "")]))
     :cljs (or (:default-class connect) :native)))

(defn node-class
  "JVM: kotoba `node-class-name` → keyword."
  [connect node]
  #?(:clj (keyword (o 'node-class-name
                      [(if-let [c (:class node)] (name c) "")
                       (if-let [c (:default-class connect)] (name c) "")]))
     :cljs (or (:class node) (default-class connect))))

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
   JVM: kotoba `serves-plane?` with host-projected transport flags."
  [connect node reach]
  (let [{:keys [class plane]} (parse-reach reach)
        ncls (node-class connect node)
        http? (when (some #{:http} (class-transports connect ncls :read)) 1)
        common? (when (seq (set/intersection
                            (set (class-transports connect ncls :live))
                            (set (class-transports connect class :live))))
                  1)]
    #?(:clj (= 1 (o 'serves-plane?
                    [(name plane)
                     (oracle/option-i64 http?)
                     (oracle/option-i64 common?)]))
       :cljs
       (case plane
         :read (boolean (some #{:http} (class-transports connect ncls :read)))
         :live (boolean (seq (set/intersection
                              (set (class-transports connect ncls :live))
                              (set (class-transports connect class :live)))))
         false))))

(defn serves-all?
  "True if `node` satisfies every reach requirement (empty => trivially true)."
  [connect node reaches]
  (every? #(serves-reach? connect node %) reaches))
