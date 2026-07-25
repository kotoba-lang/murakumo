;; murakumo.tunnel-test — offline tests for THE fleet transport contract.
;; Portable on purpose: this file is the regression net for a defect that hits
;; both the bb/JVM control plane and the nbb task plane.
;;   nbb --classpath src:test test/murakumo/tunnel_test.cljc

(ns murakumo.tunnel-test
  (:require #?(:clj [clojure.test :refer [deftest is testing run-tests]]
               :cljs [cljs.test :refer [deftest is testing run-tests]])
            [clojure.string :as str]
            [murakumo.tunnel :as tunnel]))

(deftest connection-options-are-non-interactive
  (let [o (tunnel/conn-opts nil)]
    (is (some #{"BatchMode=yes"} o) "never prompt — a dead node must fail fast")
    (is (some #{"ConnectTimeout=8"} o))
    (is (some #{"StrictHostKeyChecking=accept-new"} o))
    (is (not (some #(str/starts-with? % "ControlMaster") o))
        "multiplexing is opt-in, not the default"))
  (testing "opt-in multiplexing"
    (let [o (tunnel/conn-opts {:control-path "/tmp/m/%C" :control-persist-s 45})]
      (is (some #{"ControlMaster=auto"} o))
      (is (some #{"ControlPath=/tmp/m/%C"} o))
      (is (some #{"ControlPersist=45s"} o)))))

(deftest remote-commands-carry-their-own-exit-status
  ;; The regression this file exists for: Tailscale SSH on the macOS nodes
  ;; returns 0 for `exit 7`, so ssh's own status cannot be trusted.
  (let [argv (tunnel/ssh-argv "asher" "exit 7")
        remote (last argv)]
    (is (= "ssh" (first argv)))
    (is (= "asher" (nth argv (- (count argv) 2))))
    (is (str/starts-with? remote "( exit 7") "payload runs in a subshell…")
    (is (str/includes? remote "__mrc=$?") "…so a bare `exit` is still observable")
    (is (str/includes? remote (str "echo \"" tunnel/rc-marker "$__mrc\""))))
  (testing "wrapping can be waived for argv that is not a remote shell command"
    (is (= "true" (last (tunnel/ssh-argv "asher" "true" {:wrap? false}))))))

(deftest in-band-status-parsing
  (is (= ["hello" 0] (tunnel/parse-rc "hello\n__murakumo_rc=0")))
  (is (= ["" 7] (tunnel/parse-rc "__murakumo_rc=7")))
  (is (= ["a\nb" 3] (tunnel/parse-rc "a\nb\n__murakumo_rc=3")))
  (testing "no sentinel => nil, so the caller falls back to ssh's own code"
    (is (= ["partial" nil] (tunnel/parse-rc "partial")))
    (is (= ["" nil] (tunnel/parse-rc "")))))

(deftest sh-result-prefers-the-remote-status
  (testing "the fleet's failure mode: ssh says 0, the command really failed"
    (is (= {:exit 7 :ssh-exit 0 :out "" :err ""}
           (tunnel/sh-result {:exit 0 :out "__murakumo_rc=7\n" :err nil}))))
  (testing "transport failure keeps ssh's own code"
    (is (= {:exit 255 :ssh-exit 255 :out "" :err "connection refused"}
           (tunnel/sh-result {:exit 255 :out "" :err " connection refused\n"}))))
  (testing "success still looks like success, with the sentinel stripped"
    (is (= {:exit 0 :ssh-exit 0 :out "ok" :err ""}
           (tunnel/sh-result {:exit 0 :out " ok\n__murakumo_rc=0\n" :err nil})))))

(deftest command-shapes-are-stable
  (is (= ["scp" "-o" "BatchMode=yes" "-o" "ConnectTimeout=8" "-o" "StrictHostKeyChecking=accept-new"
          "bin/kotoba" "asher:.murakumo/bin/kotoba"]
         (tunnel/scp-argv "asher" "bin/kotoba" ".murakumo/bin/kotoba")))
  (is (= ["ssh" "-o" "ControlPath=/tmp/m/%C" "-O" "exit" "asher"]
         (tunnel/close-master-argv "asher" "/tmp/m/%C")))
  (is (= "pgrep -f '18099:localhost:8077 asher' >/dev/null 2>&1 || ssh -o BatchMode=yes -fN -L 18099:localhost:8077 asher"
         (tunnel/ensure-forward-command 18099 8077 "asher")))
  (is (= "pkill -f '18077:localhost' 2>/dev/null; sleep 0.3; ssh -o BatchMode=yes -fN -L 18077:localhost:8077 asher"
         (tunnel/replace-forward-command 18077 8077 "asher")))
  (is (= "curl -s -m 5 http://localhost:8077/health 2>/dev/null"
         (tunnel/remote-curl-command "http://localhost:8077/health")))
  (is (= {:exit 1 :err "missing"} (tunnel/scp-result {:exit 1 :err " missing\n"}))))

#?(:cljs
   (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
     (println (str "\n" (:test m) " tests, " (:pass m) " assertions, "
                   (:fail m) " failures, " (:error m) " errors"))
     (when-not (cljs.test/successful? m) (js/process.exit 1))))

#?(:cljs (run-tests))
