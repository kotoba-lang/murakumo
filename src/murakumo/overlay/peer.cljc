;; murakumo.overlay.peer — peer discovery and route selection state.
;;
;; W6 product-shell: choose-via / health / via name strings via kotoba
;; overlay_peer_core when oracle loadable (JVM or cljs/nbb).
;; Catalog/remember map folds stay host.

(ns murakumo.overlay.peer
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-peer)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn- via-kw [s]
  (keyword s))

(defn peer-record [route]
  {:type "murakumo.overlay.peer"
   :overlay (:overlay route)
   :node (:node route)
   :name (:name route)
   :direct (vec (:direct route))
   :relay (:relay route)
   :seen-at 0
   :health (via-kw
            (try-oracle
             #(o 'health-unknown [])
             (fn [] "unknown")))})

(defn catalog [routes]
  (into {}
        (map (fn [route] [(:node route) (peer-record route)]))
        routes))

(defn remember
  ([peers route] (remember peers route 0))
  ([peers route seen-at]
   (assoc peers (:node route)
          (assoc (peer-record route)
                 :seen-at seen-at
                 :health (via-kw
                          (try-oracle
                           #(o 'health-seen [])
                           (fn [] "seen")))))))

(defn mark-health [peers node health]
  (assoc-in peers [node :health] health))

(defn by-name [peers name]
  (first (filter #(= name (:name %)) (vals peers))))

(defn candidate-paths [peer]
  (let [direct-kw (via-kw (try-oracle #(o 'via-direct []) (fn [] "direct")))
        relay-kw (via-kw (try-oracle #(o 'via-relay []) (fn [] "relay")))]
    (vec (concat (map #(assoc % :via direct-kw) (:direct peer))
                 (when-let [relay (:relay peer)]
                   [(assoc relay :via relay-kw)])))))

(defn choose-path
  "Prefer healthy direct paths, then relay fallback.
   Kotoba `choose-via` when oracle ready (Product Value ABI options)."
  [peer]
  (let [paths (candidate-paths peer)
        direct? (boolean (some #(= :direct (:via %)) paths))
        relay? (boolean (some #(= :relay (:via %)) paths))
        health (name (or (:health peer) :unknown))
        via (try-oracle
             #(o 'choose-via
                 [(oracle/option-string (when direct? "direct"))
                  health
                  (oracle/option-string (when relay? "relay"))])
             (fn []
               (cond
                 (and direct? (not= :down (:health peer))) "direct"
                 relay? "relay"
                 :else "")))]
    (case via
      "direct" (first (filter #(= :direct (:via %)) paths))
      "relay" (first (filter #(= :relay (:via %)) paths))
      nil)))
