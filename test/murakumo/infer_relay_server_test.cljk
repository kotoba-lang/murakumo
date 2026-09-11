(ns murakumo.infer-relay-server-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.credits :as credits]
            [murakumo.infer.relay-server :as relay-server]))

(deftest swarm-run-record-uses-the-shared-protocol-frac-not-a-second-literal
  (testing "worker gets total minus the treasury cut, treasury gets the cut -- both derived from credits/default-protocol-frac"
    (let [settled {:did "did:key:alice" :credits 100.0}
          run (relay-server/swarm-run-record settled credits/default-protocol-frac)]
      (is (= 100.0 (:run/total run)))
      (is (= 5.0 (:run/treasury run)))
      (is (= {"did:key:alice" 95.0} (:run/shares run)))
      (testing "worker share + treasury always reconstitutes the total, for any protocol-frac"
        (is (= (:run/total run) (+ (:run/treasury run) (get (:run/shares run) "did:key:alice"))))))))

(deftest swarm-run-record-tracks-credits-settle-frac-changes
  ;; if credits.cljc's default-protocol-frac ever changes, this test (and
  ;; swarm-run-record) move with it automatically -- there is no second,
  ;; independently-maintained fraction to fall out of sync (ADR-2607995000).
  (let [settled {:did "did:key:bob" :credits 40.0}
        run (relay-server/swarm-run-record settled 0.1)]
    (is (= 4.0 (:run/treasury run)))
    (is (= {"did:key:bob" 36.0} (:run/shares run)))))
