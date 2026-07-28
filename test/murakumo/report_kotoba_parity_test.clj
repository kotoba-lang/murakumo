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
       "missing-pinned-binaries-line1 missing-pinned-binaries-line2 "
       "nodes-header status-header status-down-suffix "
       "spaces pad-right pad-to field-10 field-16 field-8 field-9 field-12 field-6 "
       "nodes-row status-down-row status-row "
       "command-help reconcile-title reconcile-col-header "
       "cid-display action-detail nat-len field-i64-7 "
       "reconcile-app-row reconcile-app-line reach-line drift-line"))

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- opt-str-form [s]
  (if (nil? s)
    "(option-none-of [:option :string])"
    (str "(option-some-of [:option :string] " (kotoba-literal s) ")")))

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
                 "ho" (str "(health-label " (opt-str-form "ok") ")")
                 "hn" (str "(health-label " (opt-str-form nil) ")")
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


(deftest nodes-status-headers-and-pad
  (let [actual (compile-string-cases
                {"nh" "(nodes-header)"
                 "sh" "(status-header)"
                 "sd" "(status-down-suffix)"
                 "sp3" "(spaces 3)"
                 "sp0" "(spaces 0)"
                 "pr" (str "(pad-right " (kotoba-literal "asher") " 5)")
                 "pt" (str "(pad-to " (kotoba-literal "asher") " 10)")
                 "f10" (str "(field-10 " (kotoba-literal "NODE") " 6)")
                 "sdr" (str "(status-down-row " (kotoba-literal "asher") ")")
                 "nr" (str "(nodes-row " (kotoba-literal "asher") " "
                           (kotoba-literal "100.1.2.3") " 1 1 "
                           (kotoba-literal "up/running") ")")
                 "nrq" (str "(nodes-row " (kotoba-literal "x") " "
                            (kotoba-literal "?") " 0 0 "
                            (kotoba-literal "off") ")")
                 "sr" (str "(status-row " (kotoba-literal "asher") " "
                           (opt-str-form "ok") " "
                           (kotoba-literal "ready") " "
                           (kotoba-literal "3") " 8077)")
                 "srn" (str "(status-row " (kotoba-literal "asher") " "
                            (opt-str-form nil) " "
                            (kotoba-literal "?") " "
                            (kotoba-literal "-") " 0)")})]
    (is (= (report/nodes-header) (get actual "nh")))
    (is (= (report/status-header) (get actual "sh")))
    (is (= "down    " (get actual "sd")))
    (is (= "   " (get actual "sp3")))
    (is (= "" (get actual "sp0")))
    (is (= "asher     " (get actual "pr")))
    (is (= "asher     " (get actual "pt")))
    (is (= "NODE      " (get actual "f10")))
    (is (= (report/status-down-row {:name "asher"}) (get actual "sdr")))
    (is (= (report/nodes-row {:name "asher" :ip "100.1.2.3" :online? true} true "up/running")
           (get actual "nr")))
    (is (= (report/nodes-row {:name "x" :ip nil :online? false} false "off")
           (get actual "nrq")))
    (is (= (report/status-row {:name "asher"}
                              {:subsystems {:wasm_executor "ready"}} 3 8077)
           (get actual "sr")))
    (is (= (report/status-row {:name "asher"} nil 0 0)
           (get actual "srn")))))
(deftest command-help-and-reconcile-pure
  (let [padn (fn [s w] (max 0 (- w (count s))))
        help (compile-string-cases {"h" "(command-help)"})
        actual
        (compile-string-cases
         {"title" (str "(reconcile-title " (kotoba-literal "f1") " " (kotoba-literal "T") ")")
          "tdef" (str "(reconcile-title " (kotoba-literal "fleet") " " (kotoba-literal "t0") ")")
          "col" "(reconcile-col-header)"
          "cid0" (str "(cid-display " (kotoba-literal "") " 0)")
          "cid1" (str "(cid-display " (kotoba-literal "bafy1234567890ab") " 1)")
          "dplace" (str "(action-detail " (kotoba-literal "place") " "
                        (kotoba-literal "t1,t2") " " (kotoba-literal "") " 1 "
                        (kotoba-literal "") ")")
          "dsat" (str "(action-detail " (kotoba-literal "satisfied") " "
                      (kotoba-literal "") " " (kotoba-literal "n1") " 0 "
                      (kotoba-literal "") ")")
          "dsat0" (str "(action-detail " (kotoba-literal "satisfied") " "
                       (kotoba-literal "") " " (kotoba-literal "") " 1 "
                       (kotoba-literal "") ")")
          "drem" (str "(action-detail " (kotoba-literal "remove") " "
                      (kotoba-literal "") " " (kotoba-literal "") " 1 "
                      (kotoba-literal "gone") ")")
          "fi" "(field-i64-7 2)"
          "fi0" "(field-i64-7 0)"
          "reach" (str "(reach-line " (kotoba-literal "r1") " " (kotoba-literal "e1") ")")
          "drift" (str "(drift-line " (kotoba-literal "m1") ")")
          "row" (let [app "a1"
                      cid "bafy1234567890ab"
                      act "satisfied"
                      app14 (str "(pad-right " (kotoba-literal app) " " (padn app 14) ")")
                      cid10 (str "(pad-right " (kotoba-literal cid) " " (padn cid 10) ")")
                      act9 (str "(pad-right " (kotoba-literal act) " " (padn act 9) ")")
                      front (str "(reconcile-app-row " app14 " " cid10 " 2 1 " act9 ")")
                      detail (str "(action-detail " (kotoba-literal "satisfied") " "
                                  (kotoba-literal "") " " (kotoba-literal "n1") " 0 "
                                  (kotoba-literal "") ")")]
                  (str "(reconcile-app-line " front " " detail ")"))})]
    (is (= (report/command-help) (get help "h")))
    (is (= "reconcile f1  @ T" (get actual "title")))
    (is (= "reconcile fleet  @ t0" (get actual "tdef")))
    (is (= (second (report/reconcile-lines {:fleet "x" :ts "y" :apps []}))
           (get actual "col")))
    (is (= "—" (get actual "cid0")))
    (is (= "bafy1234567890ab" (get actual "cid1")))
    (is (= "→ t1,t2" (get actual "dplace")))
    (is (= "on n1" (get actual "dsat")))
    (is (= "" (get actual "dsat0")))
    (is (= "gone" (get actual "drem")))
    (is (= "2      " (get actual "fi")))
    (is (= "0      " (get actual "fi0")))
    (let [plan {:fleet "f" :ts "t"
                :apps [{:app "a" :cid "c" :desired 1 :running [] :action :place
                        :targets ["x"] :reach ["r1"] :eligible ["e1"] :misplaced ["m1"]}]}
          lines (report/reconcile-lines plan)]
      (is (= (nth lines 3) (get actual "reach")))
      (is (= (nth lines 4) (get actual "drift"))))
    (let [plan {:fleet "f1" :ts "T"
                :apps [{:app "a1" :cid "bafy1234567890abcd" :desired 2
                        :running ["n1"] :action :satisfied}]}
          want (nth (report/reconcile-lines plan) 2)]
      (is (= want (get actual "row"))))))

