;; murakumo.connect — read the single connectivity description (connect.edn) and
;; answer "can this node reach that client class on that plane?".
;;
;; The decision helpers are portable .cljc and pure. load-connect is a small host
;; convenience for the bb/JVM CLI; callers that need strict portability can pass
;; the parsed connect map directly.

(ns murakumo.connect
  (:require [clojure.set :as set]
            [murakumo.config :as config]))

(defn load-connect
  "Read connect.edn (nil if absent — reach constraints then degrade to no-op)."
  ([] (load-connect config/default-connect-path))
  ([path] (config/read-edn-file-or path nil)))

(defn default-class [connect] (or (:default-class connect) :native))

(defn node-class [connect node] (or (:class node) (default-class connect)))

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
     :live — node and target client class share at least one live transport."
  [connect node reach]
  (let [{:keys [class plane]} (parse-reach reach)
        ncls (node-class connect node)]
    (case plane
      :read (boolean (some #{:http} (class-transports connect ncls :read)))
      :live (boolean (seq (set/intersection
                           (set (class-transports connect ncls :live))
                           (set (class-transports connect class :live)))))
      false)))

(defn serves-all?
  "True if `node` satisfies every reach requirement (empty => trivially true)."
  [connect node reaches]
  (every? #(serves-reach? connect node %) reaches))
