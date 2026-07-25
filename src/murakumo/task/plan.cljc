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
;; What this deliberately does NOT do (honest scope; see ADR-2607250100):
;;   - no distributed object store / futures — results come back inline
;;   - no lineage-based re-execution — a failed task is retried, not replayed
;;   - no autoscaler / placement groups / gang scheduling

(ns murakumo.task.plan
  (:require [clojure.string :as str]))

(def default-opts
  {:max-slots 8            ; hard ceiling on concurrent tasks per node
   :slots-per-node nil     ; override: fixed slots for every node
   :max-attempts 2         ; 1 = no retry
   :timeout-ms 120000
   :connect-timeout-s 8})

(defn slots
  "Concurrent task capacity of `node`: explicit :slots, else the opts override,
   else the node's core count, clamped to :max-slots. Always >= 1."
  [node opts]
  (let [{:keys [max-slots slots-per-node]} (merge default-opts opts)]
    (max 1 (min (or max-slots 8)
                (or (:slots node) slots-per-node (:cores node) 1)))))

(defn eligible?
  "Can `node` run `task`? Same placement vocabulary as reconcile/plan.cljc
   (`:labels` all match, `:roles` all present) plus task-level resource and
   exclusion constraints:

     :min-mem-bytes  node must have at least this much RAM
     :exclude-nodes  node names this task already failed on (retry placement)
     :nodes          explicit allow-list of node names

   An offline node (`:online? false`) is never eligible; nodes with no
   reachability metadata are assumed online (offline probing is the shell's
   job, and a missing probe must not silently empty the fleet)."
  [node {:keys [placement min-mem-bytes exclude-nodes nodes] :as _task}]
  (let [{:keys [labels roles]} placement]
    (and (not (false? (:online? node true)))
         (every? (fn [[k v]] (= v (get (:labels node) k))) labels)
         (every? (set (:roles node)) roles)
         (>= (or (:mem-bytes node) 0) (or min-mem-bytes 0))
         (not (contains? (set exclude-nodes) (:name node)))
         (or (empty? nodes) (contains? (set nodes) (:name node))))))

(defn node-score
  "Lower is better. Fill ratio (assigned / slots) dominates so big machines take
   proportionally more work; live 1-minute load average breaks ties, then more
   memory, then name for determinism. No clock, no RNG."
  [node opts load]
  [(/ (double (get load (:name node) 0)) (double (slots node opts)))
   (double (or (:load1 node) 0))
   (- (double (or (:mem-bytes node) 0)))
   (str (:name node))])

(defn- why-unschedulable [task]
  (str "no node satisfies placement=" (pr-str (:placement task))
       (when (seq (:exclude-nodes task))
         (str " excluding=" (str/join "," (:exclude-nodes task))))
       (when (:min-mem-bytes task)
         (str " min-mem-bytes=" (:min-mem-bytes task)))))

(defn- assign-1
  "Place one task onto the currently least-filled eligible node, threading the
   per-node load counter so the next task in the batch spreads elsewhere."
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
            a {:task task :node k :host (:host n)
               :wave (quot used s) :slot (mod used s)}]
        (-> acc
            (update :assignments conj a)
            (assoc-in [:load k] (inc used)))))))

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
   so a plan is reproducible across runs."
  [n template]
  (mapv (fn [i]
          (merge {:id (str "t-" (subs (str "0000" i) (- (count (str "0000" i)) 4)))
                  :attempt 1}
                 template))
        (range n)))

(defn failed?
  "A result is a failure when the process could not start, timed out, or exited
   non-zero."
  [{:keys [exit timeout? error]}]
  (boolean (or error timeout? (nil? exit) (not (zero? exit)))))

(defn retry-tasks
  "Tasks to re-submit from `results`, one attempt later and excluding the node
   that just failed them. Empty when every failure has exhausted :max-attempts.
   This is Ray's task-retry semantics, NOT lineage re-execution: the task is
   re-run from its own spec, nothing upstream is replayed."
  [results opts]
  (let [{:keys [max-attempts]} (merge default-opts opts)]
    (->> results
         (filter failed?)
         (keep (fn [{:keys [task node]}]
                 (let [attempt (or (:attempt task) 1)]
                   (when (< attempt max-attempts)
                     (-> task
                         (assoc :attempt (inc attempt))
                         (update :exclude-nodes (fnil conj []) node))))))
         vec)))

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
   1.0 means the fan-out bought nothing, ~N means N tasks really ran at once."
  [results wall-ms]
  (let [finals (final-results results)
        ok (remove failed? finals)
        ko (filter failed? finals)
        durations (keep :duration-ms results)
        task-ms (reduce + 0 durations)]
    {:tasks (count finals)
     :ok (count ok)
     :failed (count ko)
     :attempts (count results)
     :retried (- (count results) (count finals))
     :nodes-used (count (distinct (keep :node results)))
     :by-node (into (sorted-map) (frequencies (keep :node results)))
     :wall-ms wall-ms
     :task-ms task-ms
     :speedup (when (and (pos? (or wall-ms 0)) (pos? task-ms))
                (/ (double task-ms) (double wall-ms)))
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
