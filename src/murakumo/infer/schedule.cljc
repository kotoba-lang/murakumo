;; murakumo.infer.schedule — job-parallel media scheduling (pure cljc).
;;
;; W6 product-shell authority (ADR-260728-w6-schedule-oracle-authority):
;; On the JVM, eligible? / score / queue-inc DELEGATE to precompiled
;; kotoba/infer_schedule_core.kotoba → resources/murakumo/oracle/infer_schedule_core.kir.edn.
;; Host remains: set membership projection (engines/checkpoints), pick sort-by
;; (stable order on ties — differs from tournament later-index), assign atom fold.

(ns murakumo.infer.schedule
  (:require #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :infer-schedule)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

;; flags: 1 has-engine | 2 has-checkpoint | 4 holds-checkpoint | 8 can-fetch

(defn- eligibility-flags [node model]
  (let [engine (:model/engine model)
        ckpt (:model/checkpoint model)
        engines (or (:engines node) #{})
        checkpoints (or (:checkpoints node) #{})
        has-engine (if (contains? engines engine) 1 0)
        has-ckpt (if (nil? ckpt) 0 1)
        holds (if (and ckpt (contains? checkpoints ckpt)) 1 0)
        can-fetch (if (false? (:node/can-fetch? node)) 0 1)]
    (+ has-engine (* 2 has-ckpt) (* 4 holds) (* 8 can-fetch))))

(defn- mirror-eligible?
  [{:keys [engines checkpoints free-bytes] :as node} model]
  (and (contains? (or engines #{}) (:model/engine model))
       (or (nil? (:model/checkpoint model))
           (contains? (or checkpoints #{}) (:model/checkpoint model))
           (:node/can-fetch? node true))
       (>= (or free-bytes 0) (:model/min-free-bytes model 0))))

(defn eligible?
  "Can `node` run `model`? JVM: kotoba eligible? with projected flags."
  [node model]
  #?(:clj
     (= 1 (o 'eligible?
             [(long (eligibility-flags node model))
              (long (or (:free-bytes node) 0))
              (long (:model/min-free-bytes model 0))]))
     :cljs (mirror-eligible? node model)))

(defn score
  "Lower is better: queue then -free-bytes. JVM: score-queue + score-free."
  [{:keys [queue free-bytes]}]
  #?(:clj
     [(long (o 'score-queue [(long (or queue 0))]))
      (long (o 'score-free [(long (or free-bytes 0))]))]
     :cljs
     [(or queue 0) (- (or free-bytes 0))]))

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
                      #?(:clj (long (o 'queue-inc-if [(long (or q 0)) 1]))
                         :cljs ((fnil inc 0) q)))))
           {:job job :node (when n (:name n))})))))
