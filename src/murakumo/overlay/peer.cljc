;; murakumo.overlay.peer — peer discovery and route selection state.
;;
;; W6 product-shell (ADR-260728-w6-overlay-peer-tokens-pure-oracle):
;; choose-via / health / via name tokens DELEGATE to kotoba overlay_peer_core
;; when oracle loadable (JVM or cljs/nbb). Catalog/remember map folds stay host.

(ns murakumo.overlay.peer
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-peer)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "JVM: require shipped KIR (T6.4). cljs: oracle when ready, else mirror."
  [thunk mirror-thunk]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid})))
       (thunk))
     :cljs
     (if (oracle-ready?)
       (try
         (thunk)
         (catch :default _
           (mirror-thunk)))
       (mirror-thunk))))

(defn- oracle-str-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export []))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/call oid export [])
         mirror)
       (catch :default _
         mirror))))

(def ^:private mirror-health-unknown "unknown")
(def ^:private mirror-health-seen "seen")
(def ^:private mirror-health-down "down")
(def ^:private mirror-via-direct "direct")
(def ^:private mirror-via-relay "relay")

(def health-unknown
  "Peer health label: unknown. Kotoba when ready."
  (oracle-str-const 'health-unknown mirror-health-unknown))

(def health-seen
  "Peer health label: seen. Kotoba when ready."
  (oracle-str-const 'health-seen mirror-health-seen))

(def health-down
  "Peer health label: down. Kotoba when ready."
  (oracle-str-const 'health-down mirror-health-down))

(def via-direct
  "Path via direct. Kotoba when ready."
  (oracle-str-const 'via-direct mirror-via-direct))

(def via-relay
  "Path via relay. Kotoba when ready."
  (oracle-str-const 'via-relay mirror-via-relay))

(defn- via-kw [s]
  (keyword s))

(defn- mirror-choose-via [direct health relay]
  (cond
    (and direct (not= health health-down)) via-direct
    (and direct relay) via-relay
    (and direct (nil? relay)) ""
    relay via-relay
    :else ""))

(defn peer-record [route]
  {:type "murakumo.overlay.peer"
   :overlay (:overlay route)
   :node (:node route)
   :name (:name route)
   :direct (vec (:direct route))
   :relay (:relay route)
   :seen-at 0
   :health (via-kw health-unknown)})

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
                 :health (via-kw health-seen)))))

(defn mark-health [peers node health]
  (assoc-in peers [node :health] health))

(defn by-name [peers name]
  (first (filter #(= name (:name %)) (vals peers))))

(defn candidate-paths [peer]
  (let [direct-kw (via-kw via-direct)
        relay-kw (via-kw via-relay)]
    (vec (concat (map #(assoc % :via direct-kw) (:direct peer))
                 (when-let [relay (:relay peer)]
                   [(assoc relay :via relay-kw)])))))

(defn choose-path
  "Prefer healthy direct paths, then relay fallback.
   Kotoba `choose-via` when oracle ready (Product Value ABI options)."
  [peer]
  (let [paths (candidate-paths peer)
        direct-kw (via-kw via-direct)
        relay-kw (via-kw via-relay)
        direct? (boolean (some #(= direct-kw (:via %)) paths))
        relay? (boolean (some #(= relay-kw (:via %)) paths))
        health (name (or (:health peer) (via-kw health-unknown)))
        via (try-oracle
             #(o 'choose-via
                 [(oracle/option-string (when direct? via-direct))
                  health
                  (oracle/option-string (when relay? via-relay))])
             #(mirror-choose-via
               (when direct? via-direct)
               health
               (when relay? via-relay)))]
    (cond
      (= via via-direct) (first (filter #(= direct-kw (:via %)) paths))
      (= via via-relay) (first (filter #(= relay-kw (:via %)) paths))
      :else nil)))
