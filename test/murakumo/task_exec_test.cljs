;; Offline unit tests for the exec shell's pure helpers (no SSH is opened).
;;   nbb --classpath src:test test/murakumo/task_exec_test.cljs

(ns murakumo.task-exec-test
  (:require [cljs.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [murakumo.task.exec :as exec]))

(deftest ssh-argv-is-non-interactive
  (let [argv (exec/ssh-argv "asher" "hostname" 8)]
    (is (= "ssh" (first argv)))
    (is (some #{"BatchMode=yes"} argv) "never prompt — an unreachable node must fail fast")
    (is (some #{"ConnectTimeout=8"} argv))
    (is (= "hostname" (last argv)))))

(deftest wrapped-command-survives-a-bare-exit
  (let [w (exec/wrap-cmd "exit 7")]
    (is (str/starts-with? w "( ") "the payload runs in a subshell…")
    (is (str/includes? w "__mrc=$?") "…so its status can still be captured")
    (is (str/includes? w (str "echo \"" exec/rc-marker "$__mrc\"")))))

(deftest in-band-exit-code-parsing
  (testing "sentinel wins and is stripped from stdout"
    (is (= ["hello" 0] (exec/parse-rc "hello\n__murakumo_rc=0")))
    (is (= ["" 7] (exec/parse-rc "__murakumo_rc=7")))
    (is (= ["a\nb" 3] (exec/parse-rc "a\nb\n__murakumo_rc=3"))))
  (testing "no sentinel (connection died / killed) => nil, caller falls back to ssh's code"
    (is (= ["partial output" nil] (exec/parse-rc "partial output"))))
  (testing "a task that legitimately prints something marker-shaped last still parses"
    (is (= ["done" 12] (exec/parse-rc "done\n__murakumo_rc=12")))))

(deftest probe-output-parsing
  (let [cmd exec/probe-cmd]
    (is (str/includes? cmd "hw.ncpu") "macOS path")
    (is (str/includes? cmd "nproc") "Linux path")
    (is (str/includes? cmd "MemTotal") "Linux memory path")))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (:pass m) " assertions, "
                (:fail m) " failures, " (:error m) " errors"))
  (when-not (cljs.test/successful? m) (js/process.exit 1)))

(run-tests)
