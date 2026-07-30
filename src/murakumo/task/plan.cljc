;; murakumo.task.plan — PURE fleet task scheduler (task-level distribution).
;;
;; murakumo already has two placement cores: `reconcile/plan.cljc` places
;; long-lived APPS (wadm/k8s-Deployment shape: desired replicas converge onto
;; eligible nodes) and `infer/schedule.cljc` places whole media JOBS on the
;; least-loaded node that owns the right engine. Neither one distributes a
;; BATCH of short-lived tasks over the fleet — the Ray-tasks shape (fan out N
;; units of work, gather N results, retry the failures elsewhere). This
;; namespace is that missing core, and it is the k8s-Job / Ray-`.remote`
;; analogue in ADR-2607071400's equivalence table.
;;
;; PURE data -> data: no SSH, no filesystem, no wall clock, no RNG. The same
;; planner runs under nbb (the CLI shell), on the JVM (tests), in the CF Worker,
;; and inside a kotoba WASM component. Every decision is deterministic, so a
;; plan can be recorded to the Datom log and replayed/audited later.
;;
;; What this deliberately does NOT do (honest scope; see ADR-2607256000):
;;   - no distributed object store / futures — results come back inline
;;   - no lineage-based re-execution — a failed task is retried, not replayed
;;   - no autoscaler / placement groups / gang scheduling
;;
;; W6 product-shell authority (ADR-260728-w6-task-unsched-seps-pure-oracle +
;; ADR-260728-w6-task-oracle-authority):
;; slots / failed? / eligible? flags / task-id / retry bounds / wave-slot /
;; percentile index / summary retried+speedup / unschedulable detail seps
;; DELEGATE to precompiled kotoba/task_plan_core.kotoba KIR when the oracle is
;; loadable (JVM classpath or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
;; Host remains: admit/prepare folds, set membership projection for labels/
;; roles/exclude/allowlist, sort-by node-score, map assembly.
;; cljs mirrors remain as fallback when oracle is not ready.

(ns murakumo.task.plan
  "Task pure helpers use kotoba/task_plan_core.kotoba authority when oracle ready."
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :task-plan)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- oracle-str-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export []))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/call oid export [])
         mirror)
       (catch :default _
         mirror))))

(def ^:private mirror-exclude-join-sep ",")
(def ^:private mirror-unsched-placement-prefix "no node satisfies placement=")
(def ^:private mirror-unsched-excluding-prefix " excluding=")
(def ^:private mirror-unsched-min-mem-prefix " min-mem-bytes=")

(def exclude-join-sep
  "CSV join for :exclude-nodes in unschedulable detail. Kotoba when ready."
  (oracle-str-const 'exclude-join-sep mirror-exclude-join-sep))

(def unsched-placement-prefix
  "Prefix before placement pr-str in unschedulable detail. Kotoba when ready."
  (oracle-str-const 'unsched-placement-prefix mirror-unsched-placement-prefix))

(def unsched-excluding-prefix
  "Prefix before exclude CSV in unschedulable detail. Kotoba when ready."
  (oracle-str-const 'unsched-excluding-prefix mirror-unsched-excluding-prefix))

(def unsched-min-mem-prefix
  "Prefix before min-mem-bytes in unschedulable detail. Kotoba when ready."
  (oracle-str-const 'unsched-min-mem-prefix mirror-unsched-min-mem-prefix))

;; ── host-mirror pure helpers (cljs fallback + semantic documentation) ──

(def ^:private mirror-default-opts
  {:max-slots 8
   :slots-per-node nil
   :max-attempts 2
   :timeout-ms 120000
   :connect-timeout-s 8})

(defn- mirror-slots [node opts]
  (or (get (:slots-by-node opts) (:name node))
      (let [{:keys [max-slots slots-per-node]} (merge mirror-default-opts opts)]
        (max 1 (min (or max-slots 8)
                    (or (:slots node) slots-per-node (:cores node) 1))))))

(defn- mirror-failed? [{:keys [exit timeout? error]}]
  (boolean (or error timeout? (nil? exit) (not (zero? exit)))))

(defn- mirror-eligible? [node {:keys [placement min-mem-bytes exclude-nodes nodes] :as _task}]
  (let [{:keys [labels roles]} placement]
    (and (not (false? (:online? node true)))
         (every? (fn [[k v]] (= v (get (:labels node) k))) labels)
         (every? (set (:roles node)) roles)
         (>= (or (:mem-bytes node) 0) (or min-mem-bytes 0))
         (not (contains? (set exclude-nodes) (:name node)))
         (or (empty? nodes) (contains? (set nodes) (:name node))))))

(defn- mirror-task-id [i]
  (str "t-" (subs (str "0000" i) (- (count (str "0000" i)) 4))))

(defn- mirror-can-retry? [attempt max-attempts]
  (< attempt max-attempts))

(defn- mirror-wave-of [used s]
  (quot used s))

(defn- mirror-slot-of [used s]
  (mod used s))

(defn- opt-i64 [v]
  (if (some? v) (long v) -1))

;; ── product defaults + pure helpers (kotoba SSoT on JVM) ──────────────

(def default-opts
  "Default planner opts. JVM: max-slots / max-attempts / timeout-ms from oracle."
  #?(:clj
     {:max-slots (long (o 'default-max-slots []))
      :slots-per-node nil
      :max-attempts (long (o 'default-max-attempts []))
      :timeout-ms (long (o 'default-timeout-ms []))
      :connect-timeout-s 8}
     :cljs mirror-default-opts))

(defn slots
  "Concurrent task capacity of `node`. JVM: kotoba `slots` with projected i64s."
  [node opts]
  #?(:clj
     (let [merged (merge default-opts opts)
           budget (if (contains? (or (:slots-by-node opts) {}) (:name node))
                    (long (get (:slots-by-node opts) (:name node)))
                    -1)
           node-slots (opt-i64 (:slots node))
           slots-per (opt-i64 (:slots-per-node merged))
           max-slots (long (or (:max-slots merged) 8))
           cores (opt-i64 (:cores node))]
       (long (o 'slots [budget node-slots slots-per max-slots cores])))
     :cljs (mirror-slots node opts)))

(defn admit
  "Operational admission gate, applied BEFORE placement.

   `:max-load1` / `:max-load-per-core` skip nodes that are already saturated
   (this fleet's minis also run other resident work, so a node at load 10.8 on
   10 cores is healthy but a bad place to add tasks). A skipped node is
   reported separately from `:unschedulable`: skipping is a throughput choice
   about a healthy node, unschedulable means no node can ever satisfy the task.
   Nodes with no measured load are admitted — a missing probe must not empty
   the fleet."
  [nodes {:keys [max-load1 max-load-per-core]}]
  (reduce (fn [acc n]
            (let [l (:load1 n)
                  per-core (when (and l (pos? (or (:cores n) 0))) (/ l (:cores n)))
                  over (cond
                         ;; A node that failed its probe is not a candidate at
                         ;; all — dropping it here (not just at `eligible?`)
                         ;; keeps it from consuming the fleet's slot budget.
                         (false? (:online? n true))
                         [:unreachable (or (:probe-error n) "probe failed")]

                         (and max-load1 l (> l max-load1))
                         [:saturated (str "load1 " l " > " max-load1)]

                         (and max-load-per-core per-core (> per-core max-load-per-core))
                         [:saturated (str "load/core " (/ (Math/round (* 100.0 per-core)) 100.0)
                                          " > " max-load-per-core)])]
              (if over
                (update acc :skipped conj
                        {:node (:name n) :reason (first over) :detail (second over)})
                (update acc :nodes conj n))))
          {:nodes [] :skipped []}
          nodes))

(defn- trim-to-budget
  "Scale a {node-name slots} map down so the total stays within `budget`,
   proportionally and deterministically (largest allocations give up first)."
  [slots-map budget]
  (let [total (reduce + 0 (vals slots-map))]
    (if (or (nil? budget) (<= total budget))
      slots-map
      (let [ratio (/ (double budget) (double total))
            scaled (into {} (map (fn [[k v]] [k (max 1 (int (Math/floor (* v ratio))))]) slots-map))]
        (loop [m scaled]
          (let [t (reduce + 0 (vals m))]
            (if (<= t budget)
              m
              (let [[k v] (first (sort-by (juxt (comp - val) key) m))]
                (if (<= v 1)
                  ;; every node is already at one slot: drop whole nodes, least
                  ;; preferred (by name) first, so the budget is really honoured
                  (recur (dissoc m (first (sort (keys m)))))
                  (recur (assoc m k (dec v))))))))))))

(defn prepare
  "Fold health gates and the fleet-wide concurrency budget into a ready-to-use
   {:nodes :skipped :opts}. `:max-inflight` caps how many tasks the whole fleet
   runs at once (protects a shared LAN / the operator's laptop); it is applied
   by shrinking each node's slot budget, never by silently dropping tasks."
  [nodes opts]
  (let [{:keys [nodes skipped]} (admit nodes opts)
        base (into {} (map (fn [n] [(:name n) (slots n (dissoc opts :slots-by-node))]) nodes))
        budgeted (trim-to-budget base (:max-inflight opts))
        kept (filterv #(contains? budgeted (:name %)) nodes)
        dropped (for [n nodes :when (not (contains? budgeted (:name n)))]
                  {:node (:name n) :reason :over-inflight-budget
                   :detail (str "fleet --max-inflight " (:max-inflight opts)
                                " is smaller than the node count")})]
    {:nodes kept
     :skipped (into (vec skipped) dropped)
     :opts (assoc opts :slots-by-node budgeted)}))

(def ^:private eligibility-schema
  "Guest descriptor for task_plan_core's eligibility record (T5.3 + profile 5).
   Five flags used to be packed into one i64; then T5.3 made them :i64 fields;
   language profile 5 makes them :bool."
  [:record :task/eligibility
   [[:online :bool] [:labels-ok :bool] [:roles-ok :bool]
    [:not-excluded :bool] [:allowlist-ok :bool]]])

(defn- eligibility-fields
  "Host projects set/map membership into named :bool eligibility fields."
  [node {:keys [placement min-mem-bytes exclude-nodes nodes] :as _task}]
  (let [{:keys [labels roles]} placement]
    {:online (not (false? (:online? node true)))
     :labels-ok (boolean (every? (fn [[k v]] (= v (get (:labels node) k)))
                                 (or labels {})))
     :roles-ok (boolean (every? (set (or (:roles node) #{}))
                                (or roles [])))
     :not-excluded (not (contains? (set (or exclude-nodes [])) (:name node)))
     :allowlist-ok (boolean (or (empty? (or nodes []))
                                (contains? (set nodes) (:name node))))}))

(defn eligible?
  "Can `node` run `task`? Same placement vocabulary as reconcile/plan.cljc
   (`:labels` all match, `:roles` all present) plus task-level resource and
   exclusion constraints.

   JVM: kotoba `task-eligible?` with a bool eligibility record
   (T5.3 + language profile 5)."
  [node task]
  #?(:clj
     (oracle/bool->host
      (o 'task-eligible?
         [(oracle/record eligibility-schema (eligibility-fields node task))
          (long (or (:mem-bytes node) 0))
          (long (or (:min-mem-bytes task) 0))]))
     :cljs (mirror-eligible? node task)))

(defn node-score
  "Lower is better. Fill ratio (assigned / slots) dominates so big machines take
   proportionally more work; live 1-minute load average breaks ties, then more
   memory, then name for determinism. No clock, no RNG."
  [node opts load]
  [(/ (double (get load (:name node) 0)) (double (slots node opts)))
   (double (or (:load1 node) 0))
   (- (double (or (:mem-bytes node) 0)))
   (str (:name node))])

(defn- mirror-why-unschedulable [task]
  (str mirror-unsched-placement-prefix (pr-str (:placement task))
       (when (seq (:exclude-nodes task))
         (str mirror-unsched-excluding-prefix
              (str/join mirror-exclude-join-sep (:exclude-nodes task))))
       (when (:min-mem-bytes task)
         (str mirror-unsched-min-mem-prefix (:min-mem-bytes task)))))

(defn- why-unschedulable [task]
  "Reject detail string via kotoba `unschedulable-detail` when oracle ready.
   Host projects placement pr-str, exclude CSV (sep dual-sourced), optional
   min-mem string."
  (let [placement (pr-str (:placement task))
        excluding (if (seq (:exclude-nodes task))
                    (str/join exclude-join-sep (:exclude-nodes task))
                    "")
        min-mem (if-let [m (:min-mem-bytes task)] (str m) "")]
    (if (oracle-ready?)
      (try
        (o 'unschedulable-detail [placement excluding min-mem])
        (catch #?(:clj Exception :cljs :default) _
          (mirror-why-unschedulable task)))
      (mirror-why-unschedulable task))))

(defn- assign-1
  "Place one task onto the currently least-filled eligible node, threading the
   per-node load counter so the next task in the batch spreads elsewhere.
   JVM: wave/slot/load-inc via oracle."
  [acc task nodes opts]
  (let [load (:load acc)
        candidates (filterv #(eligible? % task) nodes)]
    (if (empty? candidates)
      (update acc :unschedulable conj
              {:task task :reason :no-eligible-node :detail (why-unschedulable task)})
      (let [n (first (sort-by #(node-score % opts load) candidates))
            k (:name n)
            used (get load k 0)
            s (slots n opts)
            wave #?(:clj (long (o 'wave-of [(long used) (long s)]))
                    :cljs (mirror-wave-of used s))
            slot #?(:clj (long (o 'slot-of [(long used) (long s)]))
                    :cljs (mirror-slot-of used s))
            a {:task task :node k :host (:host n)
               :wave wave :slot slot}
            next-load #?(:clj (long (o 'load-after-assign [(long used)]))
                         :cljs (inc used))]
        (-> acc
            (update :assignments conj a)
            (assoc-in [:load k] next-load))))))

(defn assign
  "Assign `tasks` to `nodes`, greedily least-loaded-first.

   Returns {:assignments [{:task t :node name :host h :wave w :slot s} …]
            :unschedulable [{:task t :reason :no-eligible-node} …]
            :load {node-name n}}

   `:wave` is the round in which a task runs on its node given that node's slot
   count (wave 0 = first `slots` tasks, wave 1 = the next batch, …). The
   executor uses it only for reporting; concurrency is enforced by slots."
  ([{:keys [nodes tasks opts]}] (assign nodes tasks opts))
  ([nodes tasks opts]
   (reduce (fn [acc task] (assign-1 acc task nodes opts))
           {:assignments [] :unschedulable [] :load {}}
           tasks)))

(defn expand
  "Build `n` identical tasks from a template. Task ids are positional (t-0000…),
   so a plan is reproducible across runs. JVM: kotoba `task-id`."
  [n template]
  (mapv (fn [i]
          (merge {:id #?(:clj (o 'task-id [(long i)])
                         :cljs (mirror-task-id i))
                  :attempt 1}
                 template))
        (range n)))

(defn failed?
  "A result is a failure when the process could not start, timed out, or exited
   non-zero. Kotoba `failed?` when oracle ready (JVM or cljs/nbb load).
   Profile 5: guest returns :bool."
  [{:keys [exit timeout? error] :as r}]
  (if (oracle-ready?)
    (oracle/bool->host
     (o 'failed?
        [(oracle/option-i64 exit)
         (boolean timeout?)
         (oracle/option-string error)]))
    (mirror-failed? r)))

(defn retry-tasks
  "Tasks to re-submit from `results`, one attempt later and excluding the node
   that just failed them. Empty when every failure has exhausted :max-attempts.
   This is Ray's task-retry semantics, NOT lineage re-execution: the task is
   re-run from its own spec, nothing upstream is replayed.
   JVM: can-retry? + attempt-next via oracle. Profile 5: can-retry? is :bool."
  [results opts]
  (let [{:keys [max-attempts]} (merge default-opts opts)]
    (->> results
         (filter failed?)
         (keep (fn [{:keys [task node]}]
                 (let [attempt (or (:attempt task) 1)
                       can? #?(:clj (oracle/bool->host
                                     (o 'can-retry?
                                        [(long attempt) (long max-attempts)]))
                               :cljs (mirror-can-retry? attempt max-attempts))]
                   (when can?
                     (-> task
                         (assoc :attempt #?(:clj (long (o 'attempt-next [(long attempt)]))
                                            :cljs (inc attempt)))
                         (update :exclude-nodes (fnil conj []) node))))))
         vec)))

(defn percentile
  "Nearest-rank percentile of `xs` (0.0–1.0), nil for an empty sample.
   JVM: index via kotoba `nearest-rank-idx` (p as milli)."
  [xs p]
  (let [v (vec (sort xs))]
    (when (seq v)
      (let [idx #?(:clj (long (o 'nearest-rank-idx
                                 [(long (count v))
                                  (long (Math/floor (* p 1000)))]))
                   :cljs (min (dec (count v))
                              (max 0 (int (Math/floor (* p (count v)))))))]
        (nth v idx)))))

(defn final-results
  "One result per task id — the last attempt made. A task that failed on asher
   and then succeeded on gad is ONE succeeded task with two attempts, so the
   run's outcome must be judged on these, not on the raw attempt log."
  [results]
  (->> results
       (group-by #(get-in % [:task :id]))
       (map (fn [[_ rs]] (last (sort-by #(or (get-in % [:task :attempt]) 1) rs))))
       (sort-by #(get-in % [:task :id]))
       vec))

(defn summary
  "Fold results (+ the measured wall-clock) into a report map.

   Counts are per TASK (final attempt); `:attempts` is the raw execution count.
   `:speedup` is total task time / wall-clock — the honest parallelism figure:
   1.0 means the fan-out bought nothing, ~N means N tasks really ran at once.
   JVM: retried + speedup milli via oracle."
  [results wall-ms]
  (let [finals (final-results results)
        ok (remove failed? finals)
        ko (filter failed? finals)
        durations (keep :duration-ms results)
        task-ms (reduce + 0 durations)
        retried #?(:clj (long (o 'summary-retried
                                 [(long (count results)) (long (count finals))]))
                   :cljs (- (count results) (count finals)))
        speedup #?(:clj
                   (let [milli (long (o 'speedup-milli
                                        [(long task-ms) (long (or wall-ms 0))]))]
                     (when (pos? milli) (/ (double milli) 1000.0)))
                   :cljs
                   (when (and (pos? (or wall-ms 0)) (pos? task-ms))
                     (/ (double task-ms) (double wall-ms))))]
    {:tasks (count finals)
     :ok (count ok)
     :failed (count ko)
     :attempts (count results)
     :retried retried
     :nodes-used (count (distinct (keep :node results)))
     :by-node (into (sorted-map) (frequencies (keep :node results)))
     :wall-ms wall-ms
     :task-ms task-ms
     :speedup speedup
     ;; NOTE: :speedup compares total task time to wall clock, so it only
     ;; compares runs of the SAME work. Per-task latency (p50/p95) is the
     ;; figure to watch when the transport changes.
     :p50-ms (percentile durations 0.5)
     :p95-ms (percentile durations 0.95)
     :slowest (when (seq durations) (apply max durations))
     :failures (mapv (fn [r] {:id (get-in r [:task :id])
                              :node (:node r)
                              :attempt (get-in r [:task :attempt])
                              :exit (:exit r)
                              :timeout? (:timeout? r)
                              :error (:error r)})
                     ko)}))

(defn plan-table
  "Rows for a human-readable plan preview: node, assigned count, slots, waves."
  [{:keys [assignments]} nodes opts]
  (let [by-node (frequencies (map :node assignments))]
    (->> nodes
         (map (fn [n]
                (let [c (get by-node (:name n) 0)
                      s (slots n opts)]
                  {:node (:name n) :assigned c :slots s
                   :waves (if (zero? c) 0 (int (Math/ceil (/ (double c) (double s)))))})))
         (sort-by (juxt (comp - :assigned) :node))
         vec)))
