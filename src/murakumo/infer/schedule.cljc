;; murakumo.infer.schedule — job-parallel media scheduling (pure cljc).
;;
;; W6 product-shell authority (ADR-260728-w6-schedule-oracle-authority):
;; eligible? / score / queue-inc DELEGATE to precompiled
;; kotoba/infer_schedule_core.kotoba when oracle loadable (JVM or cljs/nbb).
;; Host remains: set membership projection (engines/checkpoints), pick sort-by
;; (stable order on ties — differs from tournament later-index), assign atom fold.

(ns murakumo.infer.schedule
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-schedule)

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

;; Profile 5: eligibility fields are real host/guest booleans (not 0/1 i64).

(def ^:private eligibility-schema
  "Guest descriptor for infer_schedule_core's eligibility record (T5.3 + profile 5).
   Four flags used to be packed into one i64; then T5.3 made them :i64 fields;
   language profile 5 makes them :bool."
  [:record :schedule/eligibility
   [[:has-engine :bool] [:has-checkpoint :bool]
    [:holds-checkpoint :bool] [:can-fetch :bool]]])

(defn- eligibility-fields [node model]
  (let [engine (:model/engine model)
        ckpt (:model/checkpoint model)
        engines (or (:engines node) #{})
        checkpoints (or (:checkpoints node) #{})]
    {:has-engine (contains? engines engine)
     :has-checkpoint (some? ckpt)
     :holds-checkpoint (boolean (and ckpt (contains? checkpoints ckpt)))
     :can-fetch (not (false? (:node/can-fetch? node)))}))

(defn- mirror-eligible?
  [{:keys [engines checkpoints free-bytes] :as node} model]
  (and (contains? (or engines #{}) (:model/engine model))
       (or (nil? (:model/checkpoint model))
           (contains? (or checkpoints #{}) (:model/checkpoint model))
           (:node/can-fetch? node true))
       (>= (or free-bytes 0) (:model/min-free-bytes model 0))))

(defn eligible?
  "Can `node` run `model`? Kotoba eligible? with a bool eligibility record
  (T5.3 + language profile 5)."
  [node model]
  (try-oracle
   #(oracle/bool->host
     (o 'eligible?
        [(oracle/record eligibility-schema (eligibility-fields node model))
         (oracle/as-i64 (or (:free-bytes node) 0))
         (oracle/as-i64 (:model/min-free-bytes model 0))]))
   #(mirror-eligible? node model)))

(defn score
  "Lower is better: queue then -free-bytes. Kotoba score-queue + score-free when ready."
  [{:keys [queue free-bytes]}]
  (try-oracle
   (fn []
     [(oracle/i64->host (o 'score-queue [(oracle/as-i64 (or queue 0))]))
      (oracle/i64->host (o 'score-free [(oracle/as-i64 (or free-bytes 0))]))])
   (fn []
     [(or queue 0) (- (or free-bytes 0))])))

(defn pick
  "Choose the node to run `model`, or nil if none eligible.
   Prefers warm (holds checkpoint) then least-loaded. Host stable sort-by."
  [nodes model]
  (let [ok (filter #(eligible? % model) nodes)
        holds? (fn [n] (contains? (or (:checkpoints n) #{}) (:model/checkpoint model)))
        warm (filter holds? ok)]
    (first (sort-by score (if (seq warm) warm ok)))))

(defn assign
  "Assign a batch of jobs to nodes, updating each picked node's queue."
  [nodes jobs]
  (let [by-name (atom (into {} (map (juxt :name identity) nodes)))]
    (vec
     (for [job jobs
           :let [n (pick (vals @by-name) (:model job))]]
       (do (when n
             (swap! by-name update-in [(:name n) :queue]
                    (fn [q]
                      (try-oracle
                       (fn []
                         (oracle/i64->host
                          (o 'queue-inc-if [(oracle/as-i64 (or q 0)) (oracle/as-i64 1)])))
                       (fn [] ((fnil inc 0) q))))))
           {:job job :node (when n (:name n))})))))
