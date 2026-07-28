;; murakumo.infer.plan — exo-style memory-weighted shard planning (pure cljc).
;;
;; W6 product-shell authority (ADR-260728-w6-infer-plan-oracle-authority):
;; On the JVM, selected pure helpers DELEGATE to precompiled
;; kotoba/infer_plan_core.kotoba → resources/murakumo/oracle/infer_plan_core.kir.edn:
;;   usable-bytes, default reserves, choose-strategy-name, ok-mark, GiB.
;; Host remains: partition-layers double walk (i64 overflow on weight*usable
;; for multi-hundred-GB models), layer-weights, report double GiB fields.

(ns murakumo.infer.plan
  (:require #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :infer-plan)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(def GiB
  #?(:clj (o 'gib [])
     :cljs (* 1024 1024 1024)))

(def default-os-reserve
  #?(:clj (o 'default-os-reserve [])
     :cljs (* 7/2 GiB)))

(def default-headroom
  #?(:clj (o 'default-headroom [])
     :cljs (* 5/4 GiB)))

(defn- mirror-usable-bytes
  [{:keys [mem-bytes os-reserve-bytes headroom-bytes wired-limit-bytes]}]
  (let [os-res (or os-reserve-bytes default-os-reserve)
        head (or headroom-bytes default-headroom)
        ceiling (- mem-bytes os-res)
        ceiling (if wired-limit-bytes (min ceiling wired-limit-bytes) ceiling)]
    (max 0 (long (- ceiling head)))))

(defn usable-bytes
  "Bytes of weights a node can realistically hold resident."
  [node]
  #?(:clj
     (let [{:keys [mem-bytes os-reserve-bytes headroom-bytes wired-limit-bytes]} node
           os (long (or os-reserve-bytes (o 'default-os-reserve [])))
           hd (long (or headroom-bytes (o 'default-headroom [])))
           wired (if wired-limit-bytes (long wired-limit-bytes) -1)]
       (o 'usable-bytes [(long mem-bytes) os hd wired]))
     :cljs (mirror-usable-bytes node)))

(defn- largest-remainder
  "Apportion `total` integer units over `quotas`."
  [total quotas]
  (let [floors (mapv long quotas)
        short (- total (reduce + floors))
        order (->> (map-indexed (fn [i q] [i (- q (nth floors i))]) quotas)
                   (sort-by (fn [[i frac]] [(- frac) i]))
                   (map first))
        bump (set (take short order))]
    (vec (map-indexed (fn [i f] (+ f (if (bump i) 1 0))) floors))))

(defn layer-weights
  "Per-layer byte estimates for the model's decoder stack."
  [{:model/keys [layers weight-bytes dense-layers dense-layer-frac]}]
  (let [d (or dense-layers 0)
        f (or dense-layer-frac 1/10)
        units (+ (* d (double f)) (- layers d))
        moe-bytes (/ (double weight-bytes) units)]
    (mapv #(if (< % d) (* f moe-bytes) moe-bytes) (range layers))))

(defn partition-layers
  "Memory-weighted contiguous partition of the decoder stack over `nodes`.
   Host double walk (integer guest partition-target overflows for large models)."
  [{:model/keys [layers] :as model} nodes]
  (let [usable (mapv usable-bytes nodes)
        total (reduce + usable)
        lw (layer-weights model)
        wsum (reduce + lw)]
    (loop [i 0, lo 0, acc 0.0, out []]
      (if (= i (count nodes))
        out
        (let [target (if (pos? total)
                       (* wsum (/ (double (reduce + (take (inc i) usable))) total))
                       0.0)
              last? (= i (dec (count nodes)))
              hi (if last?
                   layers
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
  "Full shard plan: {:model :assignments :total-usable-bytes :fits?}."
  [model nodes]
  (let [asg (partition-layers model nodes)
        total (reduce + (map (comp usable-bytes :node) asg))]
    {:model (select-keys model [:model/id :model/family :model/format
                                :model/layers :model/weight-bytes])
     :assignments asg
     :total-usable-bytes total
     :fits? (and (>= total (:model/weight-bytes model))
                 (every? :fits? (filter (comp pos? :span) asg)))}))

(def ^:private strategy-why
  {"tensor" "fast interconnect and kv-heads divide the ranks — all-reduce per layer is affordable"
   "expert" "fast interconnect and enough experts for every rank to hold whole ones"
   "pipeline" "GbE-class link: one activation handoff per boundary is all it can pay for"})

(defn choose-strategy
  "Pick parallelism; JVM strategy name from oracle, why on host."
  [{:keys [link-gbps ranks model]}]
  (let [{:model/keys [experts kv-heads]} model]
    #?(:clj
       (let [name (o 'choose-strategy-name
                     [(long (or link-gbps 0))
                      (long (or ranks 0))
                      (long (or experts 0))
                      (long (or kv-heads 0))])]
         {:strategy (keyword name)
          :why (get strategy-why name (strategy-why "pipeline"))})
       :cljs
       (let [fast? (and link-gbps (>= (double link-gbps) 20.0))]
         (cond
           (and fast? kv-heads (pos? (or ranks 0)) (zero? (mod kv-heads ranks)))
           {:strategy :tensor :why (strategy-why "tensor")}
           (and fast? experts (> (or experts 0) (or ranks 1)))
           {:strategy :expert :why (strategy-why "expert")}
           :else
           {:strategy :pipeline :why (strategy-why "pipeline")})))))

(defn report
  "Human-oriented rows for the plan table."
  [{:keys [assignments] :as _plan}]
  (for [{:keys [node layers span est-bytes fits?]} assignments]
    {:name (:name node)
     :mem-gib (/ (double (:mem-bytes node)) GiB)
     :usable-gib (/ (double (usable-bytes node)) GiB)
     :layers layers
     :span span
     :est-gib (/ (double est-bytes) GiB)
     :ok #?(:clj (o 'ok-mark [(long (if fits? 1 0))])
            :cljs (if fits? "✓" "✗"))}))
