;; Offline tests for the resident-worker framing (no ssh, no processes).
;; The framing IS the correctness argument for reusing one remote shell across
;; many tasks: if a task's output can bleed past its delimiter, every later
;; result on that node is wrong.
;;   nbb --classpath src:test test/murakumo/task_worker_test.cljs

(ns murakumo.task-worker-test
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [murakumo.task.worker :as worker]
            [murakumo.tunnel :as tunnel]))

(deftest framing-carries-status-and-delimiter
  (let [f (worker/frame "t-0007" "exit 3")]
    (is (str/starts-with? f "( exit 3") "the task runs in a subshell…")
    (is (str/includes? f ") 2>&1;") "…with stderr merged into the single stream")
    (is (str/includes? f (str "echo \"" tunnel/rc-marker "$__mrc\""))
        "status still travels in band — this is the same sentinel murakumo.tunnel owns")
    (is (str/includes? f (str worker/end-marker "t-0007")) "…followed by a per-task delimiter")
    (is (str/ends-with? f "\n") "must end with a newline or the remote shell never runs it")))

(deftest reader-stops-at-the-delimiter
  (testing "incomplete output yields nothing — the task is not done yet"
    (is (nil? (worker/take-completed "partial out" "t-1")))
    (is (nil? (worker/take-completed "__murakumo_end=t-2" "t-1")) "another task's delimiter is not ours"))
  (testing "complete output is cut exactly at the delimiter"
    (is (= ["hello\n__murakumo_rc=0\n" ""]
           (worker/take-completed "hello\n__murakumo_rc=0\n__murakumo_end=t-1" "t-1"))))
  (testing "output of the NEXT task already in the buffer is preserved, not lost"
    (let [[chunk remaining] (worker/take-completed
                             "one\n__murakumo_rc=0\n__murakumo_end=t-1\ntwo\n__murakumo_rc=0\n__murakumo_end=t-2"
                             "t-1")]
      (is (= "one\n__murakumo_rc=0\n" chunk))
      (is (= "\ntwo\n__murakumo_rc=0\n__murakumo_end=t-2" remaining))
      (is (= ["\ntwo\n__murakumo_rc=0\n" ""] (worker/take-completed remaining "t-2"))
          "the next task reads cleanly out of what was left"))))

(deftest a-completed-chunk-still-parses-as-a-normal-result
  (let [[chunk _] (worker/take-completed "out line\n__murakumo_rc=7\n__murakumo_end=t-9" "t-9")
        [clean rc] (tunnel/parse-rc chunk)]
    (is (= "out line" clean))
    (is (= 7 rc) "a macOS node that lies about ssh exit codes still reports 7 here")))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (:pass m) " assertions, "
                (:fail m) " failures, " (:error m) " errors"))
  (when-not (cljs.test/successful? m) (js/process.exit 1)))

(run-tests)
