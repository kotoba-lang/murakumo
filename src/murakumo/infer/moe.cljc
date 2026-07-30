;; murakumo.infer.moe — mlx-moe single-node MoE serving (pure cljc).
;;
;; W6 product-shell + T6.4: capacity-default / expert-ratio / verdict-name /
;; resident-est require the shipped `:infer-moe` KIR on **every** platform.
;; Host pure mirrors are gone — cljs/nbb must preload shipped KIR before
;; requiring this ns (ADR-260731-w6-t64-infer-small-mirror-delete).
;; Custom capacity tiers and plan ranking stay host.

(ns murakumo.infer.moe
  (:require [murakumo.infer.plan :as plan]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-moe)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(def ^:private capacity-tiers
  [[128 512] [64 432] [48 320] [32 208]])

(defn- capacity-from-tiers
  [tiers usable-bytes]
  (some (fn [[gib cap]] (when (>= usable-bytes (* gib plan/GiB)) cap))
        (sort-by (comp - first) tiers)))

(defn capacity-for-usable
  "Usable bytes → mlx-moe capacity, or nil below the smallest measured tier."
  ([usable-bytes]
   (let [c (oracle/i64->host (o 'capacity-default [(oracle/as-i64 usable-bytes)]))]
     (when (pos? c) c)))
  ([model usable-bytes]
   (if-let [custom (:model/mlx-moe-capacity-tiers model)]
     (capacity-from-tiers custom usable-bytes)
     (capacity-for-usable usable-bytes))))

(defn expert-ratio
  "experts / active-experts (top-k)."
  [{:model/keys [experts active-experts]}]
  (when (and experts active-experts (pos? active-experts))
    (let [milli (oracle/i64->host
                 (o 'expert-ratio-milli
                    [(oracle/as-i64 experts) (oracle/as-i64 active-experts)]))]
      (when (pos? milli) (/ (double milli) 1000.0)))))

(defn verdict
  "mu-hashmi/mlx-moe 'which models benefit' heuristic as data."
  [model]
  (let [ratio (expert-ratio model)
        shared? (boolean (:model/moe-shared-expert? model))
        name (o 'verdict-name
               [(oracle/as-i64 (or (:model/experts model) 0))
                (oracle/as-i64 (or (:model/active-experts model) 0))
                (boolean shared?)])
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

(defn resident-bytes-estimate
  "Approximate RAM mlx-moe holds resident at `capacity` experts/layer cached."
  [{:model/keys [weight-bytes experts]} capacity]
  (oracle/i64->host
   (o 'resident-est
      [(oracle/as-i64 (or weight-bytes 0))
       (oracle/as-i64 (or experts 0))
       (oracle/as-i64 (or capacity 0))])))

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
