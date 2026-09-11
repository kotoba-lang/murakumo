;; murakumo.overlay-witness-reputation-test — reputation-gated witness
;; selection (ADR-2607110300 Phase 4), exercised against this repo's own
;; real fleet.edn (murakumo.config/default-fleet-path) via
;; config/read-edn-file -- the same production fleet inventory the rest of
;; the overlay code reads, not a synthetic mock. Still a single-operator
;; fleet, per witness_transport.clj's honesty note.

(ns murakumo.overlay-witness-reputation-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.witness-quorum.reputation :as reputation]
            [murakumo.config :as config]
            [murakumo.overlay.witness-transport :as witness-transport]))

(defn- real-fleet-edn [] (config/read-edn-file config/default-fleet-path))

(deftest real-fleet-edn-has-the-expected-node-roster
  (testing "sanity: the fixture this whole test namespace depends on hasn't drifted
            out from under it -- if fleet.edn's roster changes, this test should
            fail loudly here rather than the reputation tests failing confusingly"
    (let [fleet-edn (real-fleet-edn)]
      (is (= "com-junkawasaki-kotoba-mesh" (:fleet/name fleet-edn)))
      (is (<= 5 (count (:nodes fleet-edn))) "the real fleet is not a 1-2 node toy")
      (is (some #(= "naphtali" (:name %)) (:nodes fleet-edn))))))

(deftest fleet-edn->witness-fleet-covers-every-real-node
  (let [fleet-edn (real-fleet-edn)
        witness-fleet (witness-transport/fleet-edn->witness-fleet fleet-edn)]
    (is (= (count (:nodes fleet-edn)) (count witness-fleet)))
    (is (= (set (map :name (:nodes fleet-edn)))
           (set (map :node witness-fleet))))))

(deftest select-reputable-witnesses-uses-the-full-real-fleet-when-no-history-exists
  (testing "no reputation history yet -> nobody is excluded, selection draws from
            all real fleet nodes (fresh cells default to trusted, per
            reputation/score)"
    (let [fleet-edn (real-fleet-edn)
          quorum-size (min 5 (count (:nodes fleet-edn)))
          selected (witness-transport/select-reputable-witnesses
                    "bafy-real-fleet-cid" fleet-edn reputation/empty-reputation quorum-size)]
      (is (= quorum-size (count selected)))
      (is (every? #(contains? (set (map :name (:nodes fleet-edn))) (:node %)) selected)))))

(deftest select-reputable-witnesses-excludes-a-real-node-with-a-bad-track-record
  (testing "one real fleet node (naphtali) has repeatedly disagreed with quorum
            -> it's excluded from selection entirely, the rest of the real fleet
            still participates"
    (let [fleet-edn (real-fleet-edn)
          bad-cell-key "naphtali::witness"
          reputation-db (reduce #(reputation/record-outcome %1 bad-cell-key %2)
                                 reputation/empty-reputation
                                 [false false false false])
          quorum-size (min 5 (dec (count (:nodes fleet-edn))))
          selected (witness-transport/select-reputable-witnesses
                    "bafy-real-fleet-cid-2" fleet-edn reputation-db quorum-size)]
      (is (not (some #(= "naphtali" (:node %)) selected)))
      (is (= quorum-size (count selected))))))
