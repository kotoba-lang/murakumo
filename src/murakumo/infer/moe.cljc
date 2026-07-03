;; murakumo.infer.moe — mlx-moe single-node MoE serving (pure cljc).
;;
;; murakumo.infer.plan cuts a memory-weighted CONTIGUOUS layer partition across
;; many nodes — the only scheme the fleet's 1 GbE interconnect can pay for
;; (ADR-2605300000), and the right one for a DENSE (or modestly-MoE) checkpoint
;; bigger than any single node's RAM.
;;
;; mu-hashmi/mlx-moe (https://github.com/mu-hashmi/mlx-moe) answers a different
;; question for MoE checkpoints specifically: a router only ever activates a
;; handful of experts per token, so a node can load the model WITHOUT its
;; expert weights (~GBs instead of tens of GBs), discover which experts a
;; prompt actually routes to, and page only THOSE in from SSD. The result: one
;; Mac with modest RAM serves a MoE checkpoint whose full weights exceed it —
;; no ring, no fleet, no cross-node activation hop at all. That makes it the
;; cheap path for MoE models the fleet doesn't need (or, on GbE, can't afford —
;; plan/choose-strategy's :expert branch) to shard cross-node.
;;
;; This namespace deliberately shapes its plan output like murakumo.infer.plan
;; ({:model :assignments :total-usable-bytes :fits?}, one :head? true
;; assignment spanning ALL layers) so murakumo.infer.engine/commands,
;; murakumo.infer.credits/settle, and plan/report all work UNMODIFIED — an
;; mlx-moe run is just a one-assignment, one-rank "ring".

(ns murakumo.infer.moe
  (:require [murakumo.infer.plan :as plan]))

;; mu-hashmi/mlx-moe README hardware table: usable RAM (GiB, binary) → experts
;; cached resident per MoE layer ("capacity"), the auto-selected setting the
;; measured tok/s and memory numbers in the README are for. Descending so the
;; first tier the node clears wins.
(def ^:private capacity-tiers
  [[128 512] [64 432] [48 320] [32 208]])

(defn capacity-for-usable
  "Usable bytes → mlx-moe capacity (experts cached resident per MoE layer), or
   nil below the smallest measured tier (32 GiB usable) — honestly: below that
   floor mlx-moe hasn't been shown to hold quality, so this planner declines to
   guess rather than extrapolate past the README's own table."
  [usable-bytes]
  (some (fn [[gib cap]] (when (>= usable-bytes (* gib plan/GiB)) cap)) capacity-tiers))

(defn expert-ratio
  "experts / active-experts (top-k) — the mlx-moe README's first predictor of
   whether reduced-coverage serving holds output quality. nil when the
   registry entry doesn't carry both fields."
  [{:model/keys [experts active-experts]}]
  (when (and experts active-experts (pos? active-experts))
    (/ (double experts) active-experts)))

(defn verdict
  "mu-hashmi/mlx-moe's 'which models benefit' heuristic (README), as data —
   honest guidance, not a hard gate the way :fits? is: a high expert ratio
   (≥10x) PLUS a shared expert is where output quality holds up at reduced
   expert coverage; a shared expert alone still needs high coverage; no shared
   expert (or an unknown ratio) means quality likely degrades below ~75%
   coverage, per the README's measured model table."
  [model]
  (let [ratio (expert-ratio model)
        shared? (boolean (:model/moe-shared-expert? model))]
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
       :why "no shared expert — quality likely degrades below ~75% coverage (README)"})))

(defn resident-bytes-estimate
  "Approximate RAM mlx-moe holds resident at `capacity` experts/layer cached:
   capacity's share of the expert weight bytes, plus everything that is NOT a
   routed expert (dense/shared layers, embeddings, router) which always stays
   resident. Falls back to the full weight-bytes when the model registry
   entry lacks :model/experts (can't apportion) — a deliberately pessimistic
   default so an unknown model never LOOKS cheaper than it might be. This is a
   planning estimate (the README's own measured numbers run a couple GiB above
   the raw ratio for KV cache/runtime overhead), not a promise."
  [{:model/keys [weight-bytes experts]} capacity]
  (if (and weight-bytes experts (pos? experts) capacity)
    (long (* weight-bytes (/ (double capacity) experts)))
    weight-bytes))

(defn plan
  "Single-node mlx-moe plan for `model` over `nodes` — NOT a fleet-wide
   partition (plan/plan partitions LAYERS across many ranks): picks the ONE
   node with the most usable memory, marks it :head? (it alone drives + serves
   the OpenAI-compatible /v1 API — there is no ring to conduct), and gives it
   the full layer span. :fits? gates on that node clearing mlx-moe's smallest
   measured hardware tier (32 GiB usable); `nodes` empty or all sub-floor
   yields a plan with :fits? false, not a crash (same honesty as plan/plan's
   zero-memory-fleet case)."
  [{:model/keys [layers] :as model} nodes]
  (let [ranked (->> nodes
                     (map (fn [n] {:node (assoc n :head? true) :usable (plan/usable-bytes n)}))
                     (sort-by (comp - :usable)))
        best (first ranked)
        usable (:usable best 0)
        cap (capacity-for-usable usable)
        span (or layers 1)
        est (resident-bytes-estimate model cap)]
    {:engine :mlx-moe   ; self-describing so callers holding only a saved plan (no model) can branch
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
