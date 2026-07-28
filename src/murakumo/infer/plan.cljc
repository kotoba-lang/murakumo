;; murakumo.infer.plan — exo-style memory-weighted shard planning (pure cljc).
;;
;; W6 product-shell: GiB/defaults/usable-bytes/choose-strategy-name DELEGATE to
;; precompiled kotoba/infer_plan_core.kotoba when oracle loadable (JVM or cljs/nbb).
;; Partition walk and plan map assembly stay host (float/vector).

(ns murakumo.infer.plan
  "Shard planning. Pure helpers use kotoba/infer_plan_core.kotoba when oracle ready."
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-plan)

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

(def ^:private mirror-GiB (* 1024 1024 1024))
(def ^:private mirror-os-reserve (long (* 7/2 mirror-GiB)))
(def ^:private mirror-headroom (long (* 5/4 mirror-GiB)))

(def GiB
  "Binary GiB in bytes."
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid 'gib []))
      mirror-GiB)
    (catch #?(:clj Exception :cljs :default) _
      mirror-GiB)))

(def default-os-reserve
  "Default OS reserve bytes."
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid 'default-os-reserve []))
      mirror-os-reserve)
    (catch #?(:clj Exception :cljs :default) _
      mirror-os-reserve)))

(def default-headroom
  "Default per-node headroom bytes."
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid 'default-headroom []))
      mirror-headroom)
    (catch #?(:clj Exception :cljs :default) _
      mirror-headroom)))

(defn- mirror-usable-bytes
  [{:keys [mem-bytes os-reserve-bytes headroom-bytes wired-limit-bytes]}]
  (let [os-res (or os-reserve-bytes default-os-reserve)
        head (or headroom-bytes default-headroom)
        ceiling (- mem-bytes os-res)
        ceiling (if wired-limit-bytes (min ceiling wired-limit-bytes) ceiling)]
    (max 0 (long (- ceiling head)))))

(defn usable-bytes
  "Bytes of weights a node can realistically hold resident."
  [{:keys [mem-bytes os-reserve-bytes headroom-bytes wired-limit-bytes] :as node}]
  (try-oracle
   (fn []
     (let [os (or os-reserve-bytes default-os-reserve)
           head (or headroom-bytes default-headroom)
           wired (if (some? wired-limit-bytes) wired-limit-bytes -1)]
       (oracle/i64->host
        (o 'usable-bytes
           [(oracle/as-i64 mem-bytes)
            (oracle/as-i64 os)
            (oracle/as-i64 head)
            (oracle/as-i64 wired)]))))
   #(mirror-usable-bytes node)))

(defn- largest-remainder
  [total quotas]
  (let [floors (mapv long quotas)
        short (- total (reduce + floors))
        order (->> (map-indexed (fn [i q] [i (- q (nth floors i))]) quotas)
                   (sort-by (fn [[i frac]] [(- frac) i]))
                   (map first))
        bump (set (take short order))]
    (vec (map-indexed (fn [i f] (+ f (if (bump i) 1 0))) floors))))

(defn layer-weights
  [{:model/keys [layers weight-bytes dense-layers dense-layer-frac]}]
  (let [d (or dense-layers 0)
        f (or dense-layer-frac 1/10)
        units (+ (* d (double f)) (- layers d))
        moe-bytes (/ (double weight-bytes) units)]
    (mapv #(if (< % d) (* f moe-bytes) moe-bytes) (range layers))))

(defn partition-layers
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
  {:tensor "fast interconnect and kv-heads divide the ranks — all-reduce per layer is affordable"
   :expert "fast interconnect and enough experts for every rank to hold whole ones"
   :pipeline "GbE-class link: one activation handoff per boundary is all it can pay for"})

(defn- mirror-choose-strategy
  [{:keys [link-gbps ranks model]}]
  (let [{:model/keys [experts kv-heads]} model
        fast? (and link-gbps (>= (double link-gbps) 20.0))]
    (cond
      (and fast? kv-heads (pos? (or ranks 0)) (zero? (mod kv-heads ranks)))
      {:strategy :tensor :why (:tensor strategy-why)}
      (and fast? experts (> (or experts 0) (or ranks 1)))
      {:strategy :expert :why (:expert strategy-why)}
      :else
      {:strategy :pipeline :why (:pipeline strategy-why)})))

(defn choose-strategy
  "Pick the parallelism the interconnect can actually pay for."
  [{:keys [link-gbps ranks model] :as opts}]
  (try-oracle
   (fn []
     (let [name (o 'choose-strategy-name
                   [(oracle/as-i64 (or link-gbps 0))
                    (oracle/as-i64 (or ranks 0))
                    (oracle/as-i64 (or (:model/experts model) 0))
                    (oracle/as-i64 (or (:model/kv-heads model) 0))])
           strat (keyword name)]
       {:strategy strat
        :why (get strategy-why strat (:pipeline strategy-why))}))
   #(mirror-choose-strategy opts)))

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
