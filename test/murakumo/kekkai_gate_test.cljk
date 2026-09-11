;; murakumo.kekkai-gate-test — offline tests for the portable admission-gate core.
;; No SSH, no fleet, no subprocess: synthetic nodes + status map → assert partitioning.

(ns murakumo.kekkai-gate-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.kekkai.gate :as gate]))

(deftest env-resolution-defaults-then-overrides
  (testing "defaults"
    (is (= "kekkai-tailnet.edn" (gate/ledger-path (constantly nil))))
    (is (= "/home/jun/github/com-junkawasaki/orgs/kotoba-lang/kekkai"
           (gate/kekkai-dir #(when (= "HOME" %) "/home/jun")))))
  (testing "env overrides"
    (is (= "/etc/murakumo/ledger.edn"
           (gate/ledger-path #(when (= "MURAKUMO_KEKKAI_LEDGER" %) "/etc/murakumo/ledger.edn"))))
    (is (= "/opt/kekkai"
           (gate/kekkai-dir #(when (= "MURAKUMO_KEKKAI_DIR" %) "/opt/kekkai"))))))

(deftest cli-argv-and-status-parsing
  (is (= ["clojure" "-M" "-m" "kekkai.cli" "ledger.edn" "asher"]
         (gate/cli-argv "ledger.edn" "asher")))
  (is (= "authorized" (gate/parse-status {:exit 0 :out "authorized\n"})))
  (testing "kekkai.cli exits 1 for non-authorized statuses but still prints the real status"
    (is (= "pending" (gate/parse-status {:exit 1 :out "pending\n"})))
    (is (= "revoked" (gate/parse-status {:exit 1 :out "revoked\n"}))))
  (testing "a hard subprocess failure (no output at all) falls back to unknown"
    (is (= "unknown" (gate/parse-status {:exit 127 :out nil})))
    (is (= "unknown" (gate/parse-status {:exit 127 :out ""})))))

(deftest partition-nodes-denies-by-default
  (let [nodes [{:name "naphtali"} {:name "judah"} {:name "simeon"}]
        status {"naphtali" "authorized" "judah" "pending"}]
    (testing "authorized admitted; pending/unknown denied"
      (is (= [{:name "naphtali"}]
             (:admitted (gate/partition-nodes nodes status))))
      (is (= [{:name "judah" :kekkai/status "pending"}
              {:name "simeon" :kekkai/status "unknown"}]
             (:denied (gate/partition-nodes nodes status))))
      (testing "node missing from status map (never queried) is unknown, not admitted"
        (is (= "unknown" (:kekkai/status (last (:denied (gate/partition-nodes nodes status))))))))))

(deftest denial-line-is-visible-not-silent
  (is (= "[kekkai] judah: not authorized (pending) — excluded from fleet ops"
         (gate/denial-line {:name "judah" :kekkai/status "pending"}))))
