;; murakumo.overlay.peer — peer discovery and route selection state.

(ns murakumo.overlay.peer)

(defn peer-record [route]
  {:type "murakumo.overlay.peer"
   :overlay (:overlay route)
   :node (:node route)
   :name (:name route)
   :direct (vec (:direct route))
   :relay (:relay route)
   :seen-at 0
   :health :unknown})

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
                 :health :seen))))

(defn mark-health [peers node health]
  (assoc-in peers [node :health] health))

(defn by-name [peers name]
  (first (filter #(= name (:name %)) (vals peers))))

(defn candidate-paths [peer]
  (vec (concat (map #(assoc % :via :direct) (:direct peer))
               (when-let [relay (:relay peer)]
                 [(assoc relay :via :relay)]))))

(defn choose-path
  "Prefer healthy direct paths, then relay fallback."
  [peer]
  (let [paths (candidate-paths peer)]
    (or (first (filter #(and (= :direct (:via %))
                             (not= :down (:health peer)))
                       paths))
        (first (filter #(= :relay (:via %)) paths)))))
