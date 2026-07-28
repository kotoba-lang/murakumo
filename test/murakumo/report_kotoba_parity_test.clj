(ns murakumo.report-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.report :as report]))

(def port-source (slurp "kotoba/report_core.kotoba"))

(def export-prefix
  (str "digit-char nat-str i64-str blank? ws? trim "
       "mesh-status node-prefix unreachable-skipped-line provision-result-line "
       "mesh-pass1-line mesh-wait-peerid-line mesh-pass2-line mesh-forming-line "
       "deploy-wait-placement-line operator-seed-required-line operator-seed-hex-required-line "
       "deploy-usage-line reconcile-usage-line dashboard-no-persistence-line "
       "reconcile-no-persistence-line reconcile-converged-line reconcile-dry-run-line "
       "missing-binary-line deploy-start-line pin-success-line snapshot-error-line "
       "reconcile-persist-error-line online-label ssh-label health-label command-error-line "
       "launch-result-line rollout-line collected-peers-line artifact-node-status "
       "deploy-command-output alert-line dashboard-start-line apply-target-line "
       "watch-start-line deploy-observed-empty-line deploy-observed-placed-line "
       "missing-pinned-binaries-line1 missing-pinned-binaries-line2"))

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest constants-and-simple-lines-match
  (let [actual (compile-string-cases
                {"ms" (str "(mesh-status " (kotoba-literal "installed") " "
                           (kotoba-literal "running") ")")
                 "np" (str "(node-prefix " (kotoba-literal "asher") ")")
                 "us" "(unreachable-skipped-line)"
                 "p0" "(provision-result-line 0)"
                 "p1" "(provision-result-line 1)"
                 "m1" "(mesh-pass1-line)"
                 "mw" "(mesh-wait-peerid-line)"
                 "m2" "(mesh-pass2-line)"
                 "mf" "(mesh-forming-line)"
                 "dw" "(deploy-wait-placement-line)"
                 "os" "(operator-seed-required-line)"
                 "oh" "(operator-seed-hex-required-line)"
                 "du" "(deploy-usage-line)"
                 "ru" "(reconcile-usage-line)"
                 "dn" "(dashboard-no-persistence-line)"
                 "rn" "(reconcile-no-persistence-line)"
                 "rc" "(reconcile-converged-line)"
                 "rd" "(reconcile-dry-run-line)"
                 "mb" (str "(missing-binary-line " (kotoba-literal "/bin/kotoba") ")")
                 "ds" (str "(deploy-start-line " (kotoba-literal "app.edn") " "
                           (kotoba-literal "bafy1") ")")
                 "ps" (str "(pin-success-line " (kotoba-literal "src") " "
                           (kotoba-literal "abc") " " (kotoba-literal "1.0") ")")
                 "se" (str "(snapshot-error-line " (kotoba-literal "boom") ")")
                 "re" (str "(reconcile-persist-error-line " (kotoba-literal "nope") ")")
                 "on" "(online-label 1)" "off" "(online-label 0)"
                 "so" "(ssh-label 1)" "sn" "(ssh-label 0)"
                 "ho" "(health-label 1)" "hn" "(health-label 0)"
                 "e1" (str "(command-error-line " (kotoba-literal "provision") " "
                           (kotoba-literal "missing-operator-seed-hex") ")")
                 "e2" (str "(command-error-line " (kotoba-literal "deploy") " "
                           (kotoba-literal "missing-manifest") ")")
                 "e3" (str "(command-error-line " (kotoba-literal "mesh") " "
                           (kotoba-literal "missing-operator-seed") ")")
                 "e4" (str "(command-error-line " (kotoba-literal "foo") " "
                           (kotoba-literal "bar") ")")})]
    (is (= (report/mesh-status "installed" "running") (get actual "ms")))
    (is (= (report/node-prefix {:name "asher"}) (get actual "np")))
    (is (= report/unreachable-skipped-line (get actual "us")))
    (is (= (report/provision-result-line false) (get actual "p0")))
    (is (= (report/provision-result-line true) (get actual "p1")))
    (is (= report/mesh-pass1-line (get actual "m1")))
    (is (= report/mesh-wait-peerid-line (get actual "mw")))
    (is (= report/mesh-pass2-line (get actual "m2")))
    (is (= report/mesh-forming-line (get actual "mf")))
    (is (= report/deploy-wait-placement-line (get actual "dw")))
    (is (= report/operator-seed-required-line (get actual "os")))
    (is (= report/operator-seed-hex-required-line (get actual "oh")))
    (is (= report/deploy-usage-line (get actual "du")))
    (is (= report/reconcile-usage-line (get actual "ru")))
    (is (= report/dashboard-no-persistence-line (get actual "dn")))
    (is (= report/reconcile-no-persistence-line (get actual "rn")))
    (is (= report/reconcile-converged-line (get actual "rc")))
    (is (= report/reconcile-dry-run-line (get actual "rd")))
    (is (= (report/missing-binary-line "/bin/kotoba") (get actual "mb")))
    (is (= (report/deploy-start-line "app.edn" "bafy1") (get actual "ds")))
    (is (= (report/pin-success-line "src" "abc" "1.0") (get actual "ps")))
    (is (= (report/snapshot-error-line "boom") (get actual "se")))
    (is (= (report/reconcile-persist-error-line "nope") (get actual "re")))
    (is (= "yes" (get actual "on")))
    (is (= "no" (get actual "off")))
    (is (= "ok" (get actual "so")))
    (is (= "no" (get actual "sn")))
    (is (= "ok" (get actual "ho")))
    (is (= "no-resp" (get actual "hn")))
    (is (= (report/command-error-line :provision :missing-operator-seed-hex) (get actual "e1")))
    (is (= (report/command-error-line :deploy :missing-manifest) (get actual "e2")))
    (is (= (report/command-error-line :mesh :missing-operator-seed) (get actual "e3")))
    (is (= (report/command-error-line :foo :bar) (get actual "e4")))))

(deftest extended-ops-lines-match
  (let [actual (compile-string-cases
                {"lr" (str "(launch-result-line " (kotoba-literal "asher") " "
                           (kotoba-literal "0") ")")
                 "ro" (str "(rollout-line " (kotoba-literal "1.2.3") " "
                           (kotoba-literal "deadbeef") " " (kotoba-literal "quic") ")")
                 "cp" (str "(collected-peers-line 3 " (kotoba-literal ".peers") ")")
                 "a1" (str "(artifact-node-status " (kotoba-literal "n1") " 1)")
                 "a0" (str "(artifact-node-status " (kotoba-literal "n1") " 0)")
                 "dc" (str "(deploy-command-output " (kotoba-literal "  out  ") " "
                           (kotoba-literal "err") ")")
                 "al" (str "(alert-line " (kotoba-literal "warn") " "
                           (kotoba-literal "asher") " " (kotoba-literal "cpu high") ")")
                 "ds" (str "(dashboard-start-line 8080 30)")
                 "at" (str "(apply-target-line " (kotoba-literal "app1") " "
                           (kotoba-literal "edge-a") ")")
                 "ws" (str "(watch-start-line 15)")
                 "de" "(deploy-observed-empty-line)"
                 "dp" (str "(deploy-observed-placed-line "
                           (kotoba-literal "a, b") " "
                           (kotoba-literal "pub") ")")
                 "mp1" (str "(missing-pinned-binaries-line1 "
                            (kotoba-literal "9.0") " "
                            (kotoba-literal "abc123") ")")
                 "mp2" "(missing-pinned-binaries-line2)"})]
    (is (= (report/launch-result-line {:name "asher"} {:exit 0}) (get actual "lr")))
    (is (= (report/rollout-line {:version "1.2.3" :git-sha "deadbeef" :features "quic"})
           (get actual "ro")))
    (is (= (report/collected-peers-line 3 ".peers") (get actual "cp")))
    (is (= (report/artifact-node-status {:name "n1"} {:exit 0}) (get actual "a1")))
    (is (= (report/artifact-node-status {:name "n1"} {:exit 1}) (get actual "a0")))
    (is (= (report/deploy-command-output "  out  " "err") (get actual "dc")))
    (is (= (report/alert-line {:level "warn" :node "asher" :msg "cpu high"})
           (get actual "al")))
    (is (= (report/dashboard-start-line 8080 30) (get actual "ds")))
    (is (= (report/apply-target-line {:app "app1"} "edge-a") (get actual "at")))
    (is (= (report/watch-start-line 15) (get actual "ws")))
    (is (= (report/deploy-observed-row [] {:name "pub"}) (get actual "de")))
    (is (= (report/deploy-observed-row ["a" "b"] {:name "pub"}) (get actual "dp")))
    (is (= (first (report/missing-pinned-binaries-lines
                   {:version "9.0" :git-sha "abc123"}))
           (get actual "mp1")))
    (is (= (second (report/missing-pinned-binaries-lines
                    {:version "9.0" :git-sha "abc123"}))
           (get actual "mp2")))))
