(ns murakumo.ci-broker-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.ci.broker :as broker]))

(def runner {:runner/id "did:key:zRunner" :runner/capabilities #{:linux :clojure}})
(def run {:ci.run/id "run-1" :ci.run/source {:kotoba/commit "bafy-source"}
          :ci.run/requires #{:linux}})

(deftest submit-is-idempotent-and-capability-aware
  (let [b (-> (broker/empty-broker) (broker/submit run) (broker/submit run))]
    (is (= ["run-1"] (:murakumo.ci/queue b)))
    (is (nil? (second (broker/lease b (assoc runner :runner/capabilities #{:macos})
                                    "t0" 0 100))))))

(deftest lease-run-heartbeat-complete
  (let [b0 (broker/submit (broker/empty-broker) run)
        [b1 l1] (broker/lease b0 runner "token-1" 1000 100)
        b2 (broker/start b1 l1 1001)
        [b3 l2] (broker/heartbeat b2 l1 1050 100)
        b4 (broker/complete b3 l2 1051 :passed "bafy-receipt")]
    (is (= :passed (get-in b4 [:murakumo.ci/runs "run-1" :ci.run/state])))
    (is (= "bafy-receipt" (get-in b4 [:murakumo.ci/runs "run-1" :ci.run/receipt-cid])))
    (is (nil? (get-in b4 [:murakumo.ci/runs "run-1" :ci.run/lease])))
    (is (= [:run/submitted :run/leased :run/started :lease/renewed :run/completed]
           (mapv :ci.event/type (:murakumo.ci/events b4))))))

(deftest expired-work-is-requeued-and-stale-completion-rejected
  (let [b0 (broker/submit (broker/empty-broker) run)
        [b1 lease] (broker/lease b0 runner "old-token" 0 10)
        b2 (broker/start b1 lease 1)
        b3 (broker/expire b2 10)]
    (is (= :queued (get-in b3 [:murakumo.ci/runs "run-1" :ci.run/state])))
    (is (= ["run-1"] (:murakumo.ci/queue b3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stale or unknown lease"
                          (broker/complete b3 lease 5 :passed "bad")))))
