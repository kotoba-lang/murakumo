;; murakumo.infer.moe — mlx-moe single-node MoE serving (pure cljc).
;;
;; W6 product-shell: capacity-default / expert-ratio / verdict-name /
;; resident-est DELEGATE to precompiled kotoba/infer_moe_core.kotoba when
;; oracle loadable (JVM or cljs/nbb). Custom capacity tiers and plan ranking stay host.

(ns murakumo.infer.moe
  (:require [murakumo.infer.plan :as plan]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-moe)

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

(def ^:private capacity-tiers
  [[128 512] [64 432] [48 320] [32 208]])

(defn- capacity-from-tiers
  [tiers usable-bytes]
  (some (fn [[gib cap]] (when (>= usable-bytes (* gib plan/GiB)) cap))
        (sort-by (comp - first) tiers)))

(defn capacity-for-usable
  "Usable bytes → mlx-moe capacity, or nil below the smallest measured tier."
  ([usable-bytes]
   (try-oracle
    (fn []
      (let [c (oracle/i64->host (o 'capacity-default [(oracle/as-i64 usable-bytes)]))]
        (when (pos? c) c)))
    #(capacity-from-tiers capacity-tiers usable-bytes)))
  ([model usable-bytes]
   (if-let [custom (:model/mlx-moe-capacity-tiers model)]
     (capacity-from-tiers custom usable-bytes)
     (capacity-for-usable usable-bytes))))

(defn expert-ratio
  "experts / active-experts (top-k)."
  [{:model/keys [experts active-experts]}]
  (when (and experts active-experts (pos? active-experts))
    (try-oracle
     (fn []
       (let [milli (oracle/i64->host
                    (o 'expert-ratio-milli
                       [(oracle/as-i64 experts) (oracle/as-i64 active-experts)]))]
         (when (pos? milli) (/ (double milli) 1000.0))))
     #(/ (double experts) active-experts))))

(defn- mirror-verdict [model ratio shared?]
  (cond
    (nil? ratio)
    {:verdict :unknown :ratio nil
     :why "registry entry has no :model/experts / :model/active-experts"}
    (and (>= ratio 10) shared?)
    {:verdict :recommended :ratio ratio
     :why "expert ratio >=10x + shared expert — quality holds at reduced coverage"}
    shared?
    {:verdict :workable :ratio ratio
     :why "shared expert but ratio <10x — needs high coverage, verify output quality"}
    :else
    {:verdict :not-recommended :ratio ratio
     :why "no shared expert — quality likely degrades below ~75% coverage (README)"}))

(defn verdict
  "mu-hashmi/mlx-moe 'which models benefit' heuristic as data."
  [model]
  (let [ratio (expert-ratio model)
        shared? (boolean (:model/moe-shared-expert? model))]
    (try-oracle
     (fn []
       (let [name (o 'verdict-name
                     [(oracle/as-i64 (or (:model/experts model) 0))
                      (oracle/as-i64 (or (:model/active-experts model) 0))
                      (oracle/as-i64 (if shared? 1 0))])
             v (keyword name)]
         (case v
           :unknown
           {:verdict :unknown :ratio nil
            :why "registry entry has no :model/experts / :model/active-experts"}
           :recommended
           {:verdict :recommended :ratio ratio
            :why "expert ratio >=10x + shared expert — quality holds at reduced coverage"}
           :workable
           {:verdict :workable :ratio ratio
            :why "shared expert but ratio <10x — needs high coverage, verify output quality"}
           {:verdict :not-recommended :ratio ratio
            :why "no shared expert — quality likely degrades below ~75% coverage (README)"})))
     #(mirror-verdict model ratio shared?))))

(defn resident-bytes-estimate
  "Approximate RAM mlx-moe holds resident at `capacity` experts/layer cached."
  [{:model/keys [weight-bytes experts]} capacity]
  (try-oracle
   #(oracle/i64->host
     (o 'resident-est
        [(oracle/as-i64 (or weight-bytes 0))
         (oracle/as-i64 (or experts 0))
         (oracle/as-i64 (or capacity 0))]))
   (fn []
     (if (and weight-bytes experts (pos? experts) capacity)
       (long (* weight-bytes (/ (double capacity) experts)))
       weight-bytes))))

(defn plan
  "Single-node mlx-moe plan for `model` over `nodes`."
  [{:model/keys [layers] :as model} nodes]
  (let [ranked (->> nodes
                    (map (fn [n] {:node (assoc n :head? true) :usable (plan/usable-bytes n)}))
                    (sort-by (comp - :usable)))
        best (first ranked)
        usable (:usable best 0)
        cap (capacity-for-usable model usable)
        span (or layers 1)
        est (resident-bytes-estimate model cap)]
    {:engine :mlx-moe
     :model (select-keys model [:model/id :model/family :model/format :model/layers
                                :model/weight-bytes :model/experts :model/active-experts])
     :assignments (if best
                    [{:node (:node best) :layers [0 span] :span span
                      :est-bytes est :fits? (boolean cap)}]
                    [])
     :capacity cap
     :verdict (verdict model)
     :total-usable-bytes usable
     :fits? (boolean cap)}))
