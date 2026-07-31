;; murakumo.task.plan — PURE fleet task scheduler (task-level distribution).
;;
;; W6 product-shell + T6.4: slots / failed? / eligible? / task-id / retry bounds /
;; wave-slot / percentile / summary / unschedulable detail require the shipped
;; `:task-plan` KIR on **every** platform. Host pure mirrors are gone —
;; cljs/nbb must preload shipped KIR before requiring this ns
;; (ADR-260731-w6-t64-task-reconcile-mirror-delete).
;; Host remains: admit/prepare folds, set membership projection, sort-by
;; node-score, map assembly.

(ns murakumo.task.plan
  "Task pure helpers use kotoba/task_plan_core.kotoba authority (oracle required)."
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :task-plan)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(defn- o-record
  "T5.2: structural host map → call-record (requires shipped oracle)."
  [export host-map field-specs]
  (oracle/require-ready! oid)
  (oracle/call-record oid export host-map field-specs))

(def exclude-join-sep
  "CSV join for :exclude-nodes in unschedulable detail. Kotoba SSoT."
  (o 'exclude-join-sep []))

(def unsched-placement-prefix
  "Prefix before placement pr-str in unschedulable detail. Kotoba SSoT."
  (o 'unsched-placement-prefix []))

(def unsched-excluding-prefix
  "Prefix before exclude CSV in unschedulable detail. Kotoba SSoT."
  (o 'unsched-excluding-prefix []))

(def unsched-min-mem-prefix
  "Prefix before min-mem-bytes in unschedulable detail. Kotoba SSoT."
  (o 'unsched-min-mem-prefix []))

(defn- opt-i64 [v]
  (if (some? v) (long v) -1))

(def default-opts
  "Default planner opts. max-slots / max-attempts / timeout-ms from oracle."
  {:max-slots (long (o 'default-max-slots []))
   :slots-per-node nil
   :max-attempts (long (o 'default-max-attempts []))
   :timeout-ms (long (o 'default-timeout-ms []))
   :connect-timeout-s 8})

(defn slots
  "Concurrent task capacity of `node`. Kotoba `slots` with projected i64s.
   T5.2: structural capacity map → call-record."
  [node opts]
  (let [merged (merge default-opts opts)
        budget (if (contains? (or (:slots-by-node opts) {}) (:name node))
                 (long (get (:slots-by-node opts) (:name node)))
                 -1)
        node-slots (opt-i64 (:slots node))
        slots-per (opt-i64 (:slots-per-node merged))
        max-slots (long (or (:max-slots merged) 8))
        cores (opt-i64 (:cores node))]
    (long (o-record 'slots
                    {:budget budget
                     :node-slots node-slots
                     :slots-per slots-per
                     :max-slots max-slots
                     :cores cores}
                    [[:budget :i64]
                     [:node-slots :i64]
                     [:slots-per :i64]
                     [:max-slots :i64]
                     [:cores :i64]]))))

(defn admit
  "Operational admission gate, applied BEFORE placement."
  [nodes {:keys [max-load1 max-load-per-core]}]
  (reduce (fn [acc n]
            (let [l (:load1 n)
                  per-core (when (and l (pos? (or (:cores n) 0))) (/ l (:cores n)))
                  over (cond
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
  "Scale a {node-name slots} map down so the total stays within `budget`."
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
                  (recur (dissoc m (first (sort (keys m)))))
                  (recur (assoc m k (dec v))))))))))))

(defn prepare
  "Fold health gates and the fleet-wide concurrency budget into a ready-to-use
   {:nodes :skipped :opts}."
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
  "Guest descriptor for task_plan_core's eligibility record (T5.3 + profile 5)."
  [:record :task/eligibility
   [[:online :bool] [:labels-ok :bool] [:roles-ok :bool]
    [:not-excluded :bool] [:allowlist-ok :bool]
    [:mem-bytes :i64] [:min-mem :i64]]])

(defn- eligibility-fields
  "Host projects set/map membership + mem bounds into eligibility fields."
  [node {:keys [placement min-mem-bytes exclude-nodes nodes] :as task}]
  (let [{:keys [labels roles]} placement]
    {:online (not (false? (:online? node true)))
     :labels-ok (boolean (every? (fn [[k v]] (= v (get (:labels node) k)))
                                 (or labels {})))
     :roles-ok (boolean (every? (set (or (:roles node) #{}))
                                (or roles [])))
     :not-excluded (not (contains? (set (or exclude-nodes [])) (:name node)))
     :allowlist-ok (boolean (or (empty? (or nodes []))
                                (contains? (set nodes) (:name node))))
     :mem-bytes (or (:mem-bytes node) 0)
     :min-mem (or min-mem-bytes 0)}))

(defn eligible?
  "Can `node` run `task`? Kotoba `task-eligible?` with a single eligibility
  record (T5.2 native guest record wire: mem bounds on the record)."
  [node task]
  (oracle/bool->host
   (o-record 'task-eligible?
             {:eligibility (oracle/record eligibility-schema
                                           (eligibility-fields node task))}
             [[:eligibility :raw]])))

(defn node-score
  "Lower is better. Fill ratio dominates; load1 / memory / name break ties."
  [node opts load]
  [(/ (double (get load (:name node) 0)) (double (slots node opts)))
   (double (or (:load1 node) 0))
   (- (double (or (:mem-bytes node) 0)))
   (str (:name node))])

(defn- why-unschedulable [task]
  "Reject detail string via kotoba `unschedulable-detail`.
   T5.2: structural detail map → call-record."
  (let [placement (pr-str (:placement task))
        excluding (if (seq (:exclude-nodes task))
                    (str/join exclude-join-sep (:exclude-nodes task))
                    "")
        min-mem (if-let [m (:min-mem-bytes task)] (str m) "")]
    (o-record 'unschedulable-detail
              {:placement placement
               :excluding excluding
               :min-mem min-mem}
              [[:placement :string]
               [:excluding :string]
               [:min-mem :string]])))

(defn- assign-1
  "Place one task onto the currently least-filled eligible node."
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
            wave (long (o-record 'wave-of
                                  {:used used :slots s}
                                  [[:used :i64] [:slots :i64]]))
            slot (long (o-record 'slot-of
                                  {:used used :slots s}
                                  [[:used :i64] [:slots :i64]]))
            a {:task task :node k :host (:host n)
               :wave wave :slot slot}
            next-load (long (o-record 'load-after-assign
                                       {:used used}
                                       [[:used :i64]]))]
        (-> acc
            (update :assignments conj a)
            (assoc-in [:load k] next-load))))))

(defn assign
  "Assign `tasks` to `nodes`, greedily least-loaded-first."
  ([{:keys [nodes tasks opts]}] (assign nodes tasks opts))
  ([nodes tasks opts]
   (reduce (fn [acc task] (assign-1 acc task nodes opts))
           {:assignments [] :unschedulable [] :load {}}
           tasks)))

(defn expand
  "Build `n` identical tasks from a template. Task ids via kotoba `task-id`."
  [n template]
  (mapv (fn [i]
          (merge {:id (o-record 'task-id
                                 {:i i}
                                 [[:i :i64]])
                  :attempt 1}
                 template))
        (range n)))

(defn failed?
  "Process could not start, timed out, or exited non-zero. Profile 5: :bool.
   T5.2: structural result map → call-record."
  [{:keys [exit timeout? error] :as r}]
  (oracle/bool->host
   (o-record 'failed?
             {:exit exit
              :timeout? timeout?
              :error error}
             [[:exit :option-i64]
              [:timeout? :bool]
              [:error :option-string]])))

(defn retry-tasks
  "Tasks to re-submit from `results`, one attempt later."
  [results opts]
  (let [{:keys [max-attempts]} (merge default-opts opts)]
    (->> results
         (filter failed?)
         (keep (fn [{:keys [task node]}]
                 (let [attempt (or (:attempt task) 1)
                       can? (oracle/bool->host
                             (o-record 'can-retry?
                                       {:attempt attempt
                                        :max-attempts max-attempts}
                                       [[:attempt :i64]
                                        [:max-attempts :i64]]))]
                   (when can?
                     (-> task
                         (assoc :attempt (long (o-record 'attempt-next
                                                           {:attempt attempt}
                                                           [[:attempt :i64]])))
                         (update :exclude-nodes (fnil conj []) node))))))
         vec)))

(defn percentile
  "Nearest-rank percentile of `xs` (0.0–1.0), nil for an empty sample."
  [xs p]
  (let [v (vec (sort xs))]
    (when (seq v)
      (let [idx (long (o-record 'nearest-rank-idx
                                  {:n (count v)
                                   :p-milli (long (Math/floor (* p 1000)))}
                                  [[:n :i64] [:p-milli :i64]]))]
        (nth v idx)))))

(defn final-results
  "One result per task id — the last attempt made."
  [results]
  (->> results
       (group-by #(get-in % [:task :id]))
       (map (fn [[_ rs]] (last (sort-by #(or (get-in % [:task :attempt]) 1) rs))))
       (sort-by #(get-in % [:task :id]))
       vec))

(defn summary
  "Fold results (+ wall-clock) into a report map."
  [results wall-ms]
  (let [finals (final-results results)
        ok (remove failed? finals)
        ko (filter failed? finals)
        durations (keep :duration-ms results)
        task-ms (reduce + 0 durations)
        retried (long (o-record 'summary-retried
                                  {:results-n (count results)
                                   :finals-n (count finals)}
                                  [[:results-n :i64] [:finals-n :i64]]))
        speedup (let [milli (long (o-record 'speedup-milli
                                             {:task-ms task-ms
                                              :wall-ms (or wall-ms 0)}
                                             [[:task-ms :i64] [:wall-ms :i64]]))]
                  (when (pos? milli) (/ (double milli) 1000.0)))]
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
