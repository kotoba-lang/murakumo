;; Offline unit tests for the pure fleet task scheduler.
;;   nbb --classpath src:test test/murakumo/task_plan_test.cljs
;; No SSH, no fleet, no clock — the planner is pure data -> data.

(ns murakumo.task-plan-test
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [murakumo.task.plan :as plan]))

(def nodes
  [{:name "asher" :host "asher" :roles ["pin" "compute"] :labels {:tier "edge" :zone "jp"}
    :cores 8 :mem-bytes 17179869184 :load1 0.5 :online? true}
   {:name "gad" :host "gad" :roles ["compute"] :labels {:tier "gpu" :zone "jp"}
    :cores 16 :mem-bytes 50130219008 :load1 0.1 :online? true}
   {:name "zebulun" :host "zebulun" :roles ["pin" "compute"] :labels {:tier "edge" :zone "jp"}
    :cores 8 :mem-bytes 17179869184 :online? false}])

(deftest eligibility
  (testing "labels and roles must all match"
    (is (plan/eligible? (first nodes) {:placement {:labels {:tier "edge"} :roles ["compute"]}}))
    (is (not (plan/eligible? (first nodes) {:placement {:labels {:tier "gpu"}}})))
    (is (not (plan/eligible? (second nodes) {:placement {:roles ["pin"]}}))))
  (testing "offline nodes are never eligible, unprobed nodes are"
    (is (not (plan/eligible? (nth nodes 2) {})))
    (is (plan/eligible? {:name "fresh"} {})))
  (testing "memory floor and exclusions"
    (is (not (plan/eligible? (first nodes) {:min-mem-bytes 32000000000})))
    (is (plan/eligible? (second nodes) {:min-mem-bytes 32000000000}))
    (is (not (plan/eligible? (first nodes) {:exclude-nodes ["asher"]})))
    (is (not (plan/eligible? (first nodes) {:nodes ["gad"]})))))

(deftest slot-capacity
  (is (= 8 (plan/slots {:cores 8} {})) "core count is the default slot count")
  (is (= 8 (plan/slots {:cores 16} {})) "clamped by :max-slots")
  (is (= 16 (plan/slots {:cores 16} {:max-slots 32})))
  (is (= 2 (plan/slots {:cores 16} {:slots-per-node 2})))
  (is (= 1 (plan/slots {} {})) "a node with no probe data still gets one slot"))

(deftest assignment-spreads-across-the-fleet
  (let [tasks (plan/expand 8 {:cmd "hostname"})
        {:keys [assignments unschedulable]} (plan/assign nodes tasks {})]
    (is (= 8 (count assignments)))
    (is (empty? unschedulable))
    (is (= #{"asher" "gad"} (set (map :node assignments)))
        "the offline node gets nothing")
    (testing "under the default :max-slots 8 cap both boxes look 8-wide → even split"
      (is (= {"asher" 4 "gad" 4} (frequencies (map :node assignments)))))
    (testing "raise the cap and the 16-core box takes proportionally more"
      (let [by-node (frequencies (map :node (:assignments (plan/assign nodes tasks {:max-slots 16}))))]
        (is (> (get by-node "gad") (get by-node "asher")))))
    (testing "deterministic: same input, same plan"
      (is (= assignments (:assignments (plan/assign nodes tasks {})))))))

(deftest waves-are-assigned-when-tasks-exceed-slots
  (let [tasks (plan/expand 6 {:cmd "x"})
        {:keys [assignments]} (plan/assign nodes tasks {:slots-per-node 1})]
    (is (= #{0 1 2} (set (map :wave assignments)))
        "1 slot/node × 2 nodes × 6 tasks = 3 waves")
    (is (= 6 (count assignments)))))

(deftest unschedulable-is-reported-not-dropped
  (let [tasks (plan/expand 3 {:cmd "x" :placement {:labels {:tier "mars"}}})
        {:keys [assignments unschedulable]} (plan/assign nodes tasks {})]
    (is (empty? assignments))
    (is (= 3 (count unschedulable)))
    (is (= :no-eligible-node (:reason (first unschedulable))))))

(deftest retries-land-on-a-different-node
  (let [t {:id "t-0000" :cmd "x" :attempt 1}
        results [{:task t :node "asher" :exit 255}
                 {:task {:id "t-0001" :cmd "x" :attempt 1} :node "gad" :exit 0}]
        retry (plan/retry-tasks results {:max-attempts 2})]
    (is (= 1 (count retry)))
    (is (= 2 (:attempt (first retry))))
    (is (= ["asher"] (:exclude-nodes (first retry))))
    (testing "the retry is then placed somewhere else"
      (let [{:keys [assignments]} (plan/assign nodes retry {})]
        (is (= "gad" (:node (first assignments))))))
    (testing "attempts are bounded"
      (is (empty? (plan/retry-tasks [{:task (assoc t :attempt 2) :node "asher" :exit 1}]
                                    {:max-attempts 2}))))))

(deftest failure-classification
  (is (plan/failed? {:exit 1}))
  (is (plan/failed? {:exit nil :timeout? true}))
  (is (plan/failed? {:error "spawn ENOENT"}))
  (is (not (plan/failed? {:exit 0}))))

(deftest saturated-nodes-are-held-back-not-failed
  (let [busy (assoc-in (vec nodes) [0 :load1] 10.8)]   ; asher, 10 cores, other resident work
    (testing "no load gate => every REACHABLE node is admitted"
      (is (= ["asher" "gad"] (mapv :name (:nodes (plan/admit busy {}))))))
    (testing "absolute load gate"
      (let [{:keys [nodes skipped]} (plan/admit busy {:max-load1 4})]
        (is (= ["gad"] (mapv :name nodes)))
        (is (= [{:node "asher" :reason :saturated :detail "load1 10.8 > 4"}
                {:node "zebulun" :reason :unreachable :detail "probe failed"}]
               skipped))))
    (testing "per-core gate treats a 32-core box differently from a 10-core mini"
      (let [{:keys [nodes]} (plan/admit [{:name "mini" :cores 10 :load1 8}
                                         {:name "big" :cores 32 :load1 8}]
                                        {:max-load-per-core 0.5})]
        (is (= ["big"] (mapv :name nodes)))))
    (testing "an unprobed node is admitted — a missing probe must not empty the fleet"
      (is (= 1 (count (:nodes (plan/admit [{:name "fresh"}] {:max-load1 1}))))))
    (testing "a node that FAILED its probe is dropped here, so it cannot eat slot budget"
      (let [{:keys [nodes skipped]} (plan/admit busy {})]
        (is (= ["asher" "gad"] (mapv :name nodes)))
        (is (= [{:node "zebulun" :reason :unreachable :detail "probe failed"}] skipped))))
    (testing "…and it does not consume the fleet inflight budget"
      (let [{:keys [nodes opts]} (plan/prepare busy {:max-inflight 2})]
        (is (= 2 (count nodes)))
        (is (not (contains? (:slots-by-node opts) "zebulun")))))))

(deftest fleet-wide-inflight-budget
  (let [ns [{:name "a" :cores 8} {:name "b" :cores 8} {:name "c" :cores 8}]]
    (testing "uncapped: every node gets its own slot count"
      (is (= {"a" 8 "b" 8 "c" 8} (:slots-by-node (:opts (plan/prepare ns {}))))))
    (testing "capped: slots shrink to fit, never silently dropping tasks"
      (let [{:keys [opts]} (plan/prepare ns {:max-inflight 6})
            m (:slots-by-node opts)]
        (is (= 6 (reduce + 0 (vals m))))
        (is (= {"a" 2 "b" 2 "c" 2} m))))
    (testing "budget smaller than the node count drops whole nodes, and says so"
      (let [{:keys [nodes skipped opts]} (plan/prepare ns {:max-inflight 2})]
        (is (= 2 (count nodes)))
        (is (= 2 (reduce + 0 (vals (:slots-by-node opts)))))
        (is (= :over-inflight-budget (:reason (first skipped))))))
    (testing "assignment honours the budgeted slots"
      (let [{:keys [nodes opts]} (plan/prepare ns {:max-inflight 3})
            {:keys [assignments]} (plan/assign nodes (plan/expand 6 {:cmd "x"}) opts)]
        (is (= {"a" 2 "b" 2 "c" 2} (frequencies (map :node assignments))))
        (is (= #{0 1} (set (map :wave assignments))) "1 slot each => two waves")))))

(deftest a-task-that-succeeds-on-retry-is-a-succeeded-task
  (let [t {:id "t-0000" :cmd "x"}
        results [{:task (assoc t :attempt 1) :node "asher" :exit 7 :duration-ms 100}
                 {:task (assoc t :attempt 2) :node "gad" :exit 0 :duration-ms 120}]
        s (plan/summary results 200)]
    (is (= 1 (:tasks s)) "one task, not two")
    (is (= 1 (:ok s)))
    (is (= 0 (:failed s)) "the run succeeded — judged on the final attempt")
    (is (= 2 (:attempts s)))
    (is (= 1 (:retried s)))
    (is (= "gad" (:node (first (plan/final-results results)))))))

(deftest summary-reports-honest-parallelism
  (let [results [{:task {:id "a"} :node "asher" :exit 0 :duration-ms 1000}
                 {:task {:id "b"} :node "gad" :exit 0 :duration-ms 1000}
                 {:task {:id "c"} :node "gad" :exit 7 :duration-ms 500}]
        s (plan/summary results 1200)]
    (is (= 3 (:tasks s)))
    (is (= 2 (:ok s)))
    (is (= 1 (:failed s)))
    (is (= 2 (:nodes-used s)))
    (is (= {"asher" 1 "gad" 2} (into {} (:by-node s))))
    (is (= (/ 2500.0 1200.0) (:speedup s)) "2500ms of task time in 1200ms of wall clock")
    (is (= 1000 (:p50-ms s)) "per-task latency is the figure that survives a transport change")
    (is (= 1000 (:p95-ms s)))
    (is (nil? (plan/percentile [] 0.5)))
    (is (= 9 (plan/percentile (range 10) 0.95)))
    (is (= "c" (get-in s [:failures 0 :id])))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (:pass m) " assertions, "
                (:fail m) " failures, " (:error m) " errors"))
  (when-not (cljs.test/successful? m) (js/process.exit 1)))

(run-tests)
