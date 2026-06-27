;; murakumo.connect — read the single connectivity description (connect.edn) and
;; answer "can this node reach that client class on that plane?". Pure; this is the
;; one place murakumo turns the declarative transport matrix into placement truth.
;;
;; See 90-docs/adr/2606271700-kotoba-transport-planes.md for the model:
;;   read plane = CID-over-HTTP (universal, transport-untrusted)
;;   live plane = libp2p multi-transport; a node serves a client on :live iff they
;;                share at least one live transport.

(ns murakumo.connect
  (:require [clojure.edn :as edn]
            [clojure.set :as set]))

(defn load-connect
  "Read connect.edn (nil if absent — reach constraints then degrade to no-op)."
  ([] (load-connect "connect.edn"))
  ([path] (try (edn/read-string (slurp path)) (catch Exception _ nil))))

(defn default-class [connect] (or (:default-class connect) :native))

(defn node-class [connect node] (or (:class node) (default-class connect)))

(defn class-transports
  "Transports a node-class speaks on `plane` (:read | :live)."
  [connect class plane]
  (get-in connect [:classes class plane] []))

(defn- parse-reach
  "Normalise a reach token. `:browser/live` → {:class :browser :plane :live};
   a map passes through unchanged."
  [r]
  (if (map? r)
    r
    {:class (keyword (namespace r)) :plane (keyword (name r))}))

(defn serves-reach?
  "Can `node` serve a client of `(:class reach)` on `(:plane reach)`?
     :read — node speaks :http (universal CID pull).
     :live — node and the target client class share ≥1 live transport."
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
  "True if `node` satisfies every reach requirement (empty ⇒ trivially true)."
  [connect node reaches]
  (every? #(serves-reach? connect node %) reaches))
