;; murakumo.infer.plan — exo-style memory-weighted shard planning (pure cljc).
;;
;; Given a model descriptor and the fleet's live memory map, decide which nodes
;; participate and which CONTIGUOUS layer range each node serves, proportional to
;; its usable memory (exo's ring memory-weighted partitioning). Pure data → data:
;; no SSH, no engine, no platform — so the same planner runs in bb (the terminal
;; operator), on the JVM (tests), in the CF Worker (cloud-murakumo), and inside a
;; kotoba WASM component.
;;
;; The planner is engine-agnostic: it emits layer ranges + byte estimates. Engine
;; adapters (murakumo.infer.engine) turn a plan into concrete process commands
;; (llama.cpp --rpc/--tensor-split, mlx.launch ring, …).

(ns murakumo.infer.plan)

(def GiB (* 1024 1024 1024))

;; What a node cannot give to weights: the OS floor plus a per-node headroom for
;; KV cache + activations + runtime overhead. macOS keeps ~3.5 GiB to itself on a
;; 16 GiB Apple-Silicon box before memory pressure bites; GPU-wired allocations
;; are additionally capped by iogpu.wired_limit_mb (0 = macOS default ≈ 70 %).
(def default-os-reserve (* 7/2 GiB))
(def default-headroom (* 5/4 GiB))

(defn usable-bytes
  "Bytes of weights a node can realistically hold resident.
   {:mem-bytes total, :os-reserve-bytes?, :headroom-bytes?, :wired-limit-bytes?}
   → min(mem − os-reserve, wired-limit) − headroom, floored at 0."
  [{:keys [mem-bytes os-reserve-bytes headroom-bytes wired-limit-bytes]}]
  (let [os-res (or os-reserve-bytes default-os-reserve)
        head (or headroom-bytes default-headroom)
        ceiling (- mem-bytes os-res)
        ceiling (if wired-limit-bytes (min ceiling wired-limit-bytes) ceiling)]
    (max 0 (long (- ceiling head)))))

(defn- largest-remainder
  "Apportion `total` integer units over `quotas` (seq of non-negative reals that
   sum to ~total) — floor everything, then hand the remaining units to the largest
   fractional parts. Deterministic: ties break to the earlier index."
  [total quotas]
  (let [floors (mapv long quotas)
        short (- total (reduce + floors))
        order (->> (map-indexed (fn [i q] [i (- q (nth floors i))]) quotas)
                   (sort-by (fn [[i frac]] [(- frac) i]))
                   (map first))
        bump (set (take short order))]
    (vec (map-indexed (fn [i f] (+ f (if (bump i) 1 0))) floors))))

(defn layer-weights
  "Per-layer byte estimates for the model's decoder stack. MoE models often open
   with a few DENSE layers (`:model/dense-layers`, e.g. GLM-5.2 first_k_dense=3)
   that weigh a fraction (`:model/dense-layer-frac`, default 1/10) of an
   expert-bearing layer — the first shard can therefore take MORE layers, which
   is exactly what lets a 78-layer GLM-5.2 sit on eleven 16 GiB ranks."
  [{:model/keys [layers weight-bytes dense-layers dense-layer-frac]}]
  (let [d (or dense-layers 0)
        f (or dense-layer-frac 1/10)
        units (+ (* d (double f)) (- layers d))          ; total in MoE-layer units
        moe-bytes (/ (double weight-bytes) units)]
    (mapv #(if (< % d) (* f moe-bytes) moe-bytes) (range layers))))

(defn partition-layers
  "Memory-weighted contiguous partition of the decoder stack over `nodes` (ring
   order = given order): walk the per-layer weight vector, cutting each node a
   contiguous slice whose BYTES (not count) match its share of usable memory.
   Returns [{:node <node> :layers [lo hi) :span n :est-bytes b :fits? bool} …] —
   nodes with zero usable memory get :span 0 and are dropped from serving."
  [{:model/keys [layers] :as model} nodes]
  (let [usable (mapv usable-bytes nodes)
        total (reduce + usable)
        lw (layer-weights model)
        wsum (reduce + lw)]
    (loop [i 0, lo 0, acc 0.0, out []]
      (if (= i (count nodes))
        out
        (let [;; cumulative byte target through node i, mapped onto the layer axis
              target (if (pos? total)
                       (* wsum (/ (double (reduce + (take (inc i) usable))) total))
                       0.0)
              last? (= i (dec (count nodes)))
              hi (if last?
                   layers
                   ;; advance while adding the next layer keeps us nearer target
                   (loop [h lo, a acc]
                     (if (or (= h layers)
                             (> (+ a (nth lw h)) target)) h (recur (inc h) (+ a (nth lw h))))))
              est (long (reduce + (subvec lw lo hi)))]
          (recur (inc i) hi (+ acc (double (reduce + (subvec lw lo hi))))
                 (conj out {:node (nth nodes i)
                            :layers [lo hi]
                            :span (- hi lo)
                            :est-bytes est
                            :fits? (<= est (nth usable i))})))))))

(defn plan
  "Full shard plan: {:model :assignments :total-usable-bytes :fits?}.
   :fits? is the go/no-go gate — total usable memory ≥ model weights AND every
   node's contiguous slice fits its own budget (largest-remainder keeps slices
   proportional, so a per-node overflow means the fleet is genuinely too small,
   not badly balanced)."
  [model nodes]
  (let [asg (partition-layers model nodes)
        total (reduce + (map (comp usable-bytes :node) asg))]
    {:model (select-keys model [:model/id :model/family :model/format
                                :model/layers :model/weight-bytes])
     :assignments asg
     :total-usable-bytes total
     :fits? (and (>= total (:model/weight-bytes model))
                 (every? :fits? (filter (comp pos? :span) asg)))}))

(defn report
  "Human-oriented rows for the plan table (pure; printing is the caller's job)."
  [{:keys [assignments] :as plan}]
  (for [{:keys [node layers span est-bytes fits?]} assignments]
    {:name (:name node)
     :mem-gib (/ (double (:mem-bytes node)) GiB)
     :usable-gib (/ (double (usable-bytes node)) GiB)
     :layers layers
     :span span
     :est-gib (/ (double est-bytes) GiB)
     :ok (if fits? "✓" "✗")}))
