;; murakumo.overlay.peer — peer discovery and route selection state.
;;
;; W6 product-shell: choose-via / health / via name strings via kotoba
;; overlay_peer_core. Catalog/remember map folds stay host.

(ns murakumo.overlay.peer
  (:require #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :overlay-peer)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(defn peer-record [route]
  {:type "murakumo.overlay.peer"
   :overlay (:overlay route)
   :node (:node route)
   :name (:name route)
   :direct (vec (:direct route))
   :relay (:relay route)
   :seen-at 0
   :health #?(:clj (keyword (o 'health-unknown []))
              :cljs :unknown)})

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
                 :health #?(:clj (keyword (o 'health-seen []))
                            :cljs :seen)))))

(defn mark-health [peers node health]
  (assoc-in peers [node :health] health))

(defn by-name [peers name]
  (first (filter #(= name (:name %)) (vals peers))))

(defn candidate-paths [peer]
  (vec (concat (map #(assoc % :via #?(:clj (keyword (o 'via-direct []))
                                      :cljs :direct))
                    (:direct peer))
               (when-let [relay (:relay peer)]
                 [(assoc relay :via #?(:clj (keyword (o 'via-relay []))
                                       :cljs :relay))]))))

(defn choose-path
  "Prefer healthy direct paths, then relay fallback.
   JVM: via decision via kotoba `choose-via`."
  [peer]
  (let [paths (candidate-paths peer)
        direct? (boolean (some #(= :direct (:via %)) paths))
        relay? (boolean (some #(= :relay (:via %)) paths))
        health (name (or (:health peer) :unknown))
        via #?(:clj (o 'choose-via
                       [(oracle/option-string (when direct? "direct"))
                        health
                        (oracle/option-string (when relay? "relay"))])
               :cljs nil)]
    #?(:clj
       (case via
         "direct" (first (filter #(= :direct (:via %)) paths))
         "relay" (first (filter #(= :relay (:via %)) paths))
         nil)
       :cljs
       (or (first (filter #(and (= :direct (:via %))
                                (not= :down (:health peer)))
                          paths))
           (first (filter #(= :relay (:via %)) paths))))))
