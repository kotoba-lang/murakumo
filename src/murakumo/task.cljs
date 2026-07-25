#!/usr/bin/env nbb
;; murakumo.task — CLI shell for the fleet task plane (`murakumo task …`).
;;
;;   nbb src/murakumo/task.cljs probe
;;   nbb src/murakumo/task.cljs plan  --n 20 --cmd 'hostname'
;;   nbb src/murakumo/task.cljs run   --n 20 --cmd 'hostname'
;;   nbb src/murakumo/task.cljs run   --tasks scripts/tasks-example.edn
;;   nbb src/murakumo/task.cljs report --last 3
;;
;; Positioning: `reconcile` converges long-lived APPS onto the fleet (wadm /
;; k8s-Deployment). `task` fans a BATCH of short-lived units of work out over
;; the same fleet and gathers the results (k8s-Job / Ray-tasks). Placement
;; decisions live in the pure murakumo.task.plan core; this file only does I/O.

(ns murakumo.task
  (:require ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [murakumo.task.exec :as exec]
            [murakumo.task.plan :as plan]))

(def default-ledger ".murakumo-task-ledger.edn")

;; --- flags ------------------------------------------------------------------

(defn parse-flags
  "--k v and --k=v into a string map; bare args accumulate under :_."
  [args]
  (loop [xs (vec args) acc {:_ []}]
    (if-let [x (first xs)]
      (if (str/starts-with? (str x) "--")
        (let [body (subs x 2)
              i (str/index-of body "=")]
          (cond
            i (recur (rest xs) (assoc acc (subs body 0 i) (subs body (inc i))))
            (and (second xs) (not (str/starts-with? (str (second xs)) "--")))
            (recur (drop 2 xs) (assoc acc body (second xs)))
            :else (recur (rest xs) (assoc acc body "true"))))
        (recur (rest xs) (update acc :_ conj x)))
      acc)))

(defn- flag-int [f k default]
  (if-let [v (get f k)] (js/parseInt v 10) default))

(defn- csv [s] (when (seq (str s)) (vec (remove str/blank? (str/split (str s) #",")))))

(defn- kv-map
  "tier=edge,zone=jp -> {:tier \"edge\" :zone \"jp\"} (fleet.edn labels are
   keyword-keyed)."
  [s]
  (into {} (for [pair (csv s)
                 :let [[k v] (str/split pair #"=" 2)]
                 :when (and k v)]
             [(keyword k) v])))

;; --- io ---------------------------------------------------------------------

(defn- slurp-edn [path]
  (edn/read-string (.readFileSync fs path "utf8")))

(defn load-fleet [f]
  (slurp-edn (get f "fleet" "fleet.edn")))

(defn- opts-from [f]
  (merge plan/default-opts
         {:max-slots (flag-int f "max-slots" (:max-slots plan/default-opts))
          :slots-per-node (when (get f "slots") (flag-int f "slots" nil))
          :max-attempts (flag-int f "attempts" (:max-attempts plan/default-opts))
          :timeout-ms (flag-int f "timeout-ms" (:timeout-ms plan/default-opts))
          :connect-timeout-s (flag-int f "connect-timeout" (:connect-timeout-s plan/default-opts))}))

(defn- tasks-from
  "Either --tasks <file.edn> ({:tasks [...]} or a bare vector) or --n/--cmd."
  [f]
  (let [placement (cond-> {}
                    (get f "labels") (assoc :labels (kv-map (get f "labels")))
                    (get f "roles") (assoc :roles (csv (get f "roles"))))
        template (cond-> {:cmd (get f "cmd")}
                   (seq placement) (assoc :placement placement)
                   (get f "nodes") (assoc :nodes (csv (get f "nodes")))
                   (get f "min-mem-gb") (assoc :min-mem-bytes
                                               (* 1024 1024 1024 (flag-int f "min-mem-gb" 0))))]
    (if-let [path (get f "tasks")]
      (let [raw (slurp-edn path)
            ts (if (map? raw) (:tasks raw) raw)]
        (mapv (fn [i t] (merge {:id (str "t-" i) :attempt 1} t)) (range) ts))
      (plan/expand (flag-int f "n" 1) template))))

;; --- printing ---------------------------------------------------------------

(defn- pad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))

(defn- print-rows [headers rows]
  (let [ks (mapv first headers)
        widths (mapv (fn [[k label]]
                       (apply max (count (str label)) (map #(count (str (get % k ""))) rows)))
                     headers)]
    (println (str/join "  " (map (fn [[_ label] w] (pad label w)) headers widths)))
    (doseq [r rows]
      (println (str/join "  " (map (fn [k w] (pad (get r k "") w)) ks widths))))))

(defn- print-nodes [nodes]
  (print-rows [[:name "NODE"] [:host "HOST"] [:online "ONLINE"] [:cores "CORES"]
               [:mem "MEM"] [:load1 "LOAD1"] [:hostname "HOSTNAME"] [:note "NOTE"]]
              (mapv (fn [n]
                      {:name (:name n) :host (:host n)
                       :online (if (:online? n) "yes" "NO")
                       :cores (or (:cores n) "-")
                       :mem (if-let [m (:mem-bytes n)]
                              (str (.toFixed (/ m 1024 1024 1024) 0) "G") "-")
                       :load1 (or (:load1 n) "-")
                       :hostname (or (:hostname n) "-")
                       :note (or (some-> (:probe-error n) (str/replace #"\s+" " ")) "")})
                    nodes)))

(defn- print-summary [s]
  (println)
  (println (str "tasks=" (:tasks s) " ok=" (:ok s) " failed=" (:failed s)
                " attempts=" (:attempts s) " retried=" (:retried s)
                " nodes-used=" (:nodes-used s)
                " wall=" (:wall-ms s) "ms"
                " task-time=" (:task-ms s) "ms"
                (when-let [sp (:speedup s)] (str " speedup=" (.toFixed sp 2) "x"))))
  (println (str "by-node: " (str/join "  " (map (fn [[k v]] (str k "=" v)) (:by-node s))))))

;; --- commands ---------------------------------------------------------------

(defn cmd-probe [f]
  (let [fleet (load-fleet f)
        nodes (:nodes fleet)]
    (-> (exec/probe nodes (opts-from f))
        (.then (fn [probed]
                 (print-nodes probed)
                 (let [up (filterv :online? probed)]
                   (println)
                   (println (str (count up) "/" (count probed) " nodes reachable, "
                                 (reduce + 0 (keep :cores up)) " cores, "
                                 (.toFixed (/ (reduce + 0 (keep :mem-bytes up)) 1024 1024 1024) 0)
                                 "G RAM")))
                 (when (get f "out")
                   (.writeFileSync fs (get f "out") (pr-str probed))
                   (println (str "wrote " (get f "out")))))))))

(defn- plan-and-print [nodes tasks opts]
  (let [p (plan/assign nodes tasks opts)]
    (print-rows [[:node "NODE"] [:assigned "ASSIGNED"] [:slots "SLOTS"] [:waves "WAVES"]]
                (plan/plan-table p nodes opts))
    (when (seq (:unschedulable p))
      (println)
      (println (str "UNSCHEDULABLE (" (count (:unschedulable p)) "):"))
      (doseq [u (take 5 (:unschedulable p))]
        (println (str "  " (get-in u [:task :id]) " — " (:detail u)))))
    p))

(defn cmd-plan [f]
  (let [fleet (load-fleet f)
        tasks (tasks-from f)
        opts (opts-from f)]
    (if (get f "no-probe")
      (do (plan-and-print (:nodes fleet) tasks opts) (js/Promise.resolve nil))
      (-> (exec/probe (:nodes fleet) opts)
          (.then (fn [probed] (plan-and-print probed tasks opts)))))))

(defn- append-ledger! [path entry]
  (.appendFileSync fs path (str (pr-str entry) "\n")))

(defn cmd-run [f]
  (let [fleet (load-fleet f)
        tasks (tasks-from f)
        opts (opts-from f)
        ledger (get f "ledger" default-ledger)
        run-id (str "run-" (js/Date.now))]
    (when (str/blank? (str (:cmd (first tasks))))
      (println "error: --cmd is required (or --tasks <file.edn>)")
      (.exit js/process 2))
    (-> (if (get f "no-probe")
          (js/Promise.resolve (:nodes fleet))
          (exec/probe (:nodes fleet) opts))
        (.then
         (fn [nodes]
           (let [t0 (js/Date.now)
                 acc (atom [])]
             (letfn [(round [pending]
                       (let [p (plan/assign nodes pending opts)]
                         (when (seq (:unschedulable p))
                           (doseq [u (:unschedulable p)]
                             (swap! acc conj {:task (:task u) :node nil :exit nil
                                              :error (str "unschedulable: " (:detail u))
                                              :duration-ms 0})))
                         (-> (exec/run-plan p nodes opts)
                             (.then (fn [results]
                                      (swap! acc into results)
                                      (let [retry (plan/retry-tasks results opts)]
                                        (if (seq retry)
                                          (do (println (str "retrying " (count retry)
                                                            " failed task(s) on other nodes…"))
                                              (round retry))
                                          nil)))))))]
               (println (str "submitting " (count tasks) " task(s) to "
                             (count (filter :online? nodes)) " reachable node(s)…"))
               (-> (round tasks)
                   (.then
                    (fn [_]
                      (let [results @acc
                            wall (- (js/Date.now) t0)
                            s (plan/summary results wall)]
                        (print-rows [[:id "TASK"] [:try "TRY"] [:node "NODE"] [:exit "EXIT"]
                                     [:ms "MS"] [:out "STDOUT"]]
                                    (mapv (fn [r]
                                            {:id (get-in r [:task :id])
                                             :try (or (get-in r [:task :attempt]) 1)
                                             :node (or (:node r) "-")
                                             :exit (cond (:timeout? r) "TIMEOUT"
                                                         (:error r) "ERR"
                                                         :else (:exit r))
                                             :ms (:duration-ms r)
                                             :out (let [o (str/replace (str (or (:stdout r) (:error r))) #"\s+" " ")]
                                                    (subs o 0 (min 60 (count o))))})
                                          (sort-by (juxt #(get-in % [:task :id])
                                                         #(get-in % [:task :attempt]))
                                                   results)))
                        (print-summary s)
                        (append-ledger! ledger
                                        {:run/id run-id
                                         :run/at (.toISOString (js/Date.))
                                         :run/cmd (:cmd (first tasks))
                                         :run/task-count (count tasks)
                                         :run/summary s
                                         :run/results (mapv (fn [r]
                                                              {:id (get-in r [:task :id])
                                                               :attempt (get-in r [:task :attempt])
                                                               :node (:node r)
                                                               :exit (:exit r)
                                                               :timeout? (:timeout? r)
                                                               :duration-ms (:duration-ms r)
                                                               :stdout (:stdout r)})
                                                            results)})
                        (println (str "recorded " run-id " -> " ledger))
                        (when (pos? (:failed s)) (.exit js/process 1)))))))))))))

(defn cmd-report [f]
  (let [ledger (get f "ledger" default-ledger)]
    (if-not (.existsSync fs ledger)
      (println (str "no ledger at " ledger))
      (let [entries (->> (str/split-lines (.readFileSync fs ledger "utf8"))
                         (remove str/blank?)
                         (mapv edn/read-string))
            n (flag-int f "last" 5)]
        (print-rows [[:id "RUN"] [:at "AT"] [:tasks "TASKS"] [:ok "OK"] [:failed "FAIL"]
                     [:nodes "NODES"] [:wall "WALL-MS"] [:speedup "SPEEDUP"] [:cmd "CMD"]]
                    (mapv (fn [e]
                            (let [s (:run/summary e)]
                              {:id (:run/id e) :at (:run/at e)
                               :tasks (:tasks s) :ok (:ok s) :failed (:failed s)
                               :nodes (:nodes-used s) :wall (:wall-ms s)
                               :speedup (if-let [sp (:speedup s)] (str (.toFixed sp 2) "x") "-")
                               :cmd (let [c (str (:run/cmd e))]
                                      (subs c 0 (min 40 (count c))))}))
                          (take-last n entries)))))
    (js/Promise.resolve nil)))

(def usage
  (str/join
   "\n"
   ["murakumo task — distribute a BATCH of tasks over the fleet (k8s-Job / Ray-tasks shape)"
    ""
    "  probe                       SSH every node: cores / memory / load / reachability"
    "  plan   --n N --cmd '…'      pure placement preview (no execution)"
    "  run    --n N --cmd '…'      place, execute over SSH, gather results, retry failures"
    "  report [--last N]           replay recorded runs from the ledger"
    ""
    "flags:"
    "  --fleet fleet.edn      inventory (default fleet.edn)"
    "  --tasks tasks.edn      explicit task list instead of --n/--cmd"
    "  --labels tier=edge     placement label constraints (all must match)"
    "  --roles compute,pin    placement role constraints (all must be present)"
    "  --nodes a,b            explicit node allow-list"
    "  --min-mem-gb 16        require at least this much RAM on the node"
    "  --slots N              fixed slots per node (default: node core count)"
    "  --max-slots N          cap slots per node (default 8)"
    "  --attempts N           max attempts per task (default 2 = one retry elsewhere)"
    "  --timeout-ms N         per-task timeout (default 120000)"
    "  --no-probe             skip the SSH probe (offline planning)"
    "  --ledger PATH          run ledger (default .murakumo-task-ledger.edn)"]))

(defn -main [& args]
  (let [[cmd & rest] args
        f (parse-flags rest)]
    (case cmd
      "probe" (cmd-probe f)
      "plan" (cmd-plan f)
      "run" (cmd-run f)
      "report" (cmd-report f)
      (do (println usage) (js/Promise.resolve nil)))))

(apply -main *command-line-args*)
