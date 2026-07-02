(ns murakumo.infer-relay-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.relay :as relay]))

(defn- browser [did] {:did did :tier :browser :caps {:can [:media-postproc :embarrassingly-parallel]}})

(deftest full-round-trip
  (let [s0 (relay/init)
        [jid s1] (relay/enqueue s0 {:kind :embarrassingly-parallel :input {:n 42} :price 5})
        [wid s2] (relay/on-hello s1 (browser "did:key:alice"))
        [reply s3] (relay/on-ready s2 wid 1000)]
    (testing "a ready worker is handed the queued job it can do, with its price"
      (is (= :job (:msg reply)))
      (is (= jid (:job-id reply)))
      (is (= {:n 42} (:input reply)))
      (is (= 5 (:price reply)))
      (is (= 0 (:queued (relay/stats s3))))
      (is (= 1 (:in-flight (relay/stats s3)))))
    (testing "the result settles credits to the worker's did, exactly once"
      (let [[settled s4] (relay/on-result s3 wid {:job-id jid :output {:sum 99} :ms 120})]
        (is (= :settled (:msg settled)))
        (is (= "did:key:alice" (:did settled)))
        (is (= 5 (:credits settled)))
        (is (= 1 (:settled (relay/stats s4))))
        (is (= 0 (:in-flight (relay/stats s4))))
        (testing "a duplicate result is a no-op (no double credit)"
          (let [[dup _] (relay/on-result s4 wid {:job-id jid :output {} :ms 1})]
            (is (nil? dup))))))))

(deftest tier-gating
  (testing "a browser worker is NOT offered work its tier can't do"
    (let [[_ s1] (relay/enqueue (relay/init) {:kind :host-large-model :input {} :price 100})
          [wid s2] (relay/on-hello s1 (browser "did:key:b"))
          [reply _] (relay/on-ready s2 wid 0)]
      (is (= :idle (:msg reply))))))

(deftest idle-when-empty
  (let [[wid s1] (relay/on-hello (relay/init) (browser "did:key:c"))
        [reply _] (relay/on-ready s1 wid 0)]
    (is (= :idle (:msg reply)))))

(deftest lease-expiry-requeues
  (let [[jid s1] (relay/enqueue (relay/init) {:kind :embarrassingly-parallel :input {} :price 3})
        [wid s2] (relay/on-hello s1 (browser "did:key:d"))
        [_ s3] (relay/on-ready s2 wid 1000)]
    (testing "a job whose leaseholder vanished is requeued, not lost"
      (is (= 1 (:in-flight (relay/stats s3))))
      (let [s4 (relay/expire-leases s3 (+ 1000 60001) 60000)]
        (is (= 0 (:in-flight (relay/stats s4))))
        (is (= 1 (:queued (relay/stats s4))))))
    (testing "a late result from the vanished worker cannot double-credit"
      (let [s4 (relay/expire-leases s3 (+ 1000 60001) 60000)
            [wid2 s5] (relay/on-hello s4 (browser "did:key:e"))
            [_ s6] (relay/on-ready s5 wid2 70000)
            ;; original worker returns late — it is no longer the leaseholder
            [stale _] (relay/on-result s6 wid {:job-id jid :output {} :ms 1})]
        (is (nil? stale))))))

(deftest disconnect-requeues
  (let [[jid s1] (relay/enqueue (relay/init) {:kind :media-postproc :input {} :price 2})
        [wid s2] (relay/on-hello s1 (browser "did:key:f"))
        [_ s3] (relay/on-ready s2 wid 500)
        s4 (relay/drop-worker s3 wid 600)]
    (testing "dropping a worker mid-job requeues its lease and forgets it"
      (is (= 0 (:workers (relay/stats s4))))
      (is (= 1 (:queued (relay/stats s4))))
      (is (= 0 (:in-flight (relay/stats s4)))))))
