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

(defn partition-layers
  "Memory-weighted contiguous partition of `(:model/layers model)` decoder layers
   over `nodes` (ring order = given order). Each node gets span_i ∝ usable_i.
   Returns [{:node <node> :layers [lo hi) :span n :est-bytes b :fits? bool} …] —
   nodes with zero usable memory get :span 0 and are dropped from serving.
   Estimation: weights are dominated by the (uniform, MoE-expert-bearing) decoder
   layers, so est-bytes = weight-bytes × span/layers."
  [{:model/keys [layers weight-bytes] :as _model} nodes]
  (let [usable (mapv usable-bytes nodes)
        total (reduce + usable)
        quotas (if (pos? total)
                 (mapv #(/ (* (double layers) %) total) usable)
                 (mapv (constantly 0.0) usable))
        spans (largest-remainder layers quotas)
        per-layer (/ (double weight-bytes) layers)]
    (loop [i 0, lo 0, out []]
      (if (= i (count nodes))
        out
        (let [span (nth spans i)
              est (long (* per-layer span))]
          (recur (inc i) (+ lo span)
                 (conj out {:node (nth nodes i)
                            :layers [lo (+ lo span)]
                            :span span
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
