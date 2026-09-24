(ns murakumo.cd-executor-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [murakumo.cd.capability :as capability]
            [murakumo.cd.executor :as executor]
            [murakumo.cd.rollout :as rollout]))

(def seed (byte-array (repeat 32 (byte 8))))
(def issuer (ed/did-key-from-seed seed))

(defn issued [ttl]
  (capability/issue seed "rid-ci"
                    {:verdict-cid "verdict" :artifact-cid "artifact"
                     :previous-artifact-cid "artifact-v1"
                     :environment "production" :revision "v2" :previous-revision "v1"
                     :now 100 :ttl ttl :nonce "n"}))

(defn plan []
  (rollout/plan {:capability-cid (:capability-cid (issued 100))
                 :artifact-cid "artifact" :environment "production"
                 :previous-artifact-cid "artifact-v1"
                 :revision "v2" :previous-revision "v1"
                 :targets ["canary" "node-b" "node-c"] :batch-size 2}))

(def policy {:rid "rid-ci" :issuers #{issuer} :environment "production"
             :artifact-cid "artifact" :verdict-cid "verdict"
             :previous-artifact-cid "artifact-v1"
             :revision "v2" :previous-revision "v1"})

(deftest canary-health-then-progressive-success
  (let [deployed (atom [])
        result (executor/execute!
                {:issued-capability (issued 100) :verification-policy policy
                 :clock-fn (constantly 110) :rollout-plan (plan)
                 :deploy-fn (fn [x] (swap! deployed conj (:node x)) {:ok? true})
                 :health-fn (constantly {:ok? true})
                 :rollback-fn (constantly {:ok? true})})]
    (is (= ["canary" "node-b" "node-c"] @deployed))
    (is (= :succeeded (get-in result [:rollout :cd.rollout/state])))
    (is (string? (:receipt-cid result)))
    (is (= [:deploy/completed :health/completed :deploy/completed
            :health/completed :rollout/succeeded]
           (mapv :cd.event/type (get-in result [:rollout :cd.rollout/events]))))))

(deftest unhealthy-canary-rolls-back-and-records
  (let [rolled-back (atom [])
        result (executor/execute!
                {:issued-capability (issued 100) :verification-policy policy
                 :clock-fn (constantly 110) :rollout-plan (plan)
                 :deploy-fn (constantly {:ok? true})
                 :health-fn (constantly {:ok? false :reason :unhealthy})
                 :rollback-fn (fn [x] (swap! rolled-back conj (:node x)) {:ok? true})})]
    (is (= ["canary"] @rolled-back))
    (is (= :rolled-back (get-in result [:rollout :cd.rollout/state])))
    (is (= :health-failed
           (get-in result [:rollout :cd.rollout/events 2 :cd.event/data :reason])))))

(deftest capability-expiry-between-batches-stops-promotion
  (let [clock (atom [110 201])
        now (fn [] (let [x (first @clock)]
                     (swap! clock (fn [xs] (if (next xs) (vec (next xs)) xs)))
                     x))
        deployed (atom []) rolled (atom [])
        result (executor/execute!
                {:issued-capability (issued 100) :verification-policy policy
                 :clock-fn now :rollout-plan (plan)
                 :deploy-fn (fn [x] (swap! deployed conj (:node x)) {:ok? true})
                 :health-fn (constantly {:ok? true})
                 :rollback-fn (fn [x] (swap! rolled conj (:node x)) {:ok? true})})]
    (is (= ["canary"] @deployed))
    (is (= ["canary"] @rolled))
    (is (= :rolled-back (get-in result [:rollout :cd.rollout/state])))
    (is (= :capability-expired
           (get-in result [:rollout :cd.rollout/events 2 :cd.event/data :reason])))))
