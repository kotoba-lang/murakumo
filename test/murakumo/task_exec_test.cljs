;; Offline unit tests for the exec shell's pure helpers (no SSH is opened).
;; The ssh dialect itself is tested in murakumo.tunnel-test — this file only
;; covers what the executor adds on top: probing and multiplexing plumbing.
;;   nbb --classpath src:test test/murakumo/task_exec_test.cljs

(ns murakumo.task-exec-test
  (:require ["node:fs" :as fs]
            [cljs.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [murakumo.task.exec :as exec]))

(deftest probe-command-is-portable-and-answers-both-questions
  (let [cmd (exec/probe-cmd 8077)]
    (is (str/includes? cmd "hw.ncpu") "macOS core count")
    (is (str/includes? cmd "nproc") "Linux core count")
    (is (str/includes? cmd "MemTotal") "Linux memory")
    (is (str/includes? cmd "http://localhost:8077/health") "mesh health in the same round trip")
    (is (str/includes? (exec/probe-cmd 8076) ":8076/health") "per-node port is honoured")))

(deftest probe-parsing
  (testing "healthy node with a live kotoba-server"
    (let [p (exec/parse-probe "10 17179869184 1.53 dannoMac-mini.local\nhealth:{:status ok}")]
      (is (= 10 (:cores p)))
      (is (= 17179869184 (:mem-bytes p)) "byte counts must survive (cljs `int` would zero this)")
      (is (= 1.53 (:load1 p)))
      (is (= "dannoMac-mini.local" (:hostname p)))
      (is (true? (:mesh-up? p)))))
  (testing "node up, mesh down — reported honestly, not as a probe failure"
    (let [p (exec/parse-probe "10 17179869184 0.4 asher.local\nhealth:")]
      (is (= 10 (:cores p)))
      (is (false? (:mesh-up? p)))
      (is (nil? (:mesh-health p)))))
  (testing "big-memory Linux node"
    (is (= 50130219008 (:mem-bytes (exec/parse-probe "32 50130219008 0.16 gad\nhealth:"))))))

(deftest multiplexing-plumbing
  (testing "disabled by explicit opt-out"
    (is (nil? (exec/control-dir! {:multiplex? false})))
    (is (nil? (exec/control-path nil))))
  (testing "enabled by default: a private dir, one socket per host via %C"
    (let [dir (exec/control-dir! {})]
      (is (some? dir))
      (is (str/includes? (exec/control-path dir) "%C"))
      (is (str/starts-with? (exec/control-path dir) dir))
      (.rmSync fs dir #js {:recursive true :force true}))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (:pass m) " assertions, "
                (:fail m) " failures, " (:error m) " errors"))
  (when-not (cljs.test/successful? m) (js/process.exit 1)))

(run-tests)
