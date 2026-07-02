;; murakumo.infer.schedule — job-parallel media scheduling (pure cljc).
;;
;; Media generation (image/video/audio) runs WHOLE on one node, so the
;; "distributed" question is NOT how to split a model across ranks (that's
;; murakumo.infer.plan for text) but WHICH node runs each job: a render-farm
;; scheduler. Pure data → data so it runs in bb, tests, the CF Worker, and a
;; kotoba WASM component alike.
;;
;; A node is eligible for a model when it (a) has the model's engine, (b) holds
;; or can fetch the checkpoint, and (c) has enough free memory for the job. Among
;; eligible nodes, pick the least-loaded (fewest queued jobs, then most free
;; memory) so a fleet of minis drains a batch in parallel.

(ns murakumo.infer.schedule)

(defn eligible?
  "Can `node` run `model`? node: {:engines #{:comfyui …} :checkpoints #{…}
   :free-bytes n :queue q}. model: {:model/engine :model/checkpoint
   :model/min-free-bytes}."
  [{:keys [engines checkpoints free-bytes]} model]
  (and (contains? (or engines #{}) (:model/engine model))
       (or (nil? (:model/checkpoint model))
           (contains? (or checkpoints #{}) (:model/checkpoint model))
           ;; a node that can fetch (has spare disk) is eligible even without it
           (:node/can-fetch? true))
       (>= (or free-bytes 0) (:model/min-free-bytes model 0))))

(defn score
  "Lower is better: queued jobs dominate, free memory breaks ties (more = better,
   so negate). Deterministic — no clock, no RNG."
  [{:keys [queue free-bytes]}]
  [(or queue 0) (- (or free-bytes 0))])

(defn pick
  "Choose the node to run `model`, or nil if none eligible. Prefers a node that
   already HOLDS the checkpoint over one that must fetch it (cold-start cost),
   then least-loaded. `nodes` is a seq of node maps (see eligible?)."
  [nodes model]
  (let [ok (filter #(eligible? % model) nodes)
        holds? (fn [n] (contains? (or (:checkpoints n) #{}) (:model/checkpoint model)))
        warm (filter holds? ok)]
    (first (sort-by score (if (seq warm) warm ok)))))

(defn assign
  "Assign a batch of jobs to nodes, updating each picked node's queue so the
   next job in the batch spreads to a different mini. Returns
   [{:job j :node n} …] (node nil when nothing is eligible)."
  [nodes jobs]
  (let [by-name (atom (into {} (map (juxt :name identity) nodes)))]
    (vec
     (for [job jobs
           :let [n (pick (vals @by-name) (:model job))]]
       (do (when n
             (swap! by-name update-in [(:name n) :queue] (fnil inc 0)))
           {:job job :node (when n (:name n))})))))
