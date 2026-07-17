(ns murakumo.ci-finalizer-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba-rad.sigref :as sigref]
            [murakumo.ci.attest :as attest]
            [murakumo.ci.finalizer :as finalizer]
            [murakumo.ci.store :as store]))

(def verdict
  {:verdict/version 1 :verdict/run-id "logical-1" :verdict/status :passed
   :verdict/source {} :verdict/pipeline-digest "pipeline" :verdict/steps []
   :verdict/artifacts []})

(defn fixture
  ([] (fixture verdict))
  ([verdict-document]
  (let [seeds [(byte-array (repeat 32 (byte 1)))
               (byte-array (repeat 32 (byte 2)))]
        delegates (set (map ed/did-key-from-seed seeds))
        cid (attest/verdict-cid verdict-document)
        run (fn [index seed]
              (let [signer (ed/did-key-from-seed seed)]
                {:ci.run/id (str "logical-1.replica." index)
                 :ci.run/logical-id "logical-1"
                 :ci.run/source {:source/type :github :source/repo "o/r"
                                 :source/ref "refs/heads/main"
                                 :source/revision "0123456789abcdef0123456789abcdef01234567"}
                 :ci.run/attestation
                 {:signer signer :verdict-cid cid :verdict verdict-document
                  :sigref (sigref/sign seed "rid" (attest/result-ref "logical-1") cid 1)}}))]
    {:state (atom {:murakumo.ci/runs
                   {"logical-1.replica.0" (run 0 (first seeds))
                    "logical-1.replica.1" (run 1 (second seeds))}})
     :policy {:rid "rid" :delegates delegates :threshold 2}})))

(deftest canonical-status-is-retried-and-delivered-once
  (let [{:keys [state policy]} (fixture)
        persistent (store/memory-store)
        calls (atom 0)
        service (finalizer/create
                 {:store persistent :broker-state state
                  :quorum-policy policy
                  :public-base-url "https://murakumo.cloud"
                  :executors {:github/status
                              (fn [_]
                                (if (= 1 (swap! calls inc))
                                  (throw (ex-info "temporary" {}))
                                  {:ok? true}))}})]
    (is (= :pending (:ci.delivery/state (first ((:tick! service))))))
    (is (= :delivered (:ci.delivery/state (first ((:tick! service))))))
    ((:tick! service))
    (is (= 2 @calls))
    (is (= 1 (count ((:read persistent) finalizer/outbox-stream 0))))
    (is (= :delivered
           (get-in ((:status! service) "logical-1")
                   [0 :delivery :ci.delivery/state])))))

(deftest non-github-and-pending-runs-do-not-enqueue-actions
  (let [{broker-state :state policy :policy} (fixture)
        persistent (store/memory-store)
        _ (swap! broker-state assoc-in
                 [:murakumo.ci/runs "logical-1.replica.0" :ci.run/source :source/type]
                 :radicle)
        _ (swap! broker-state assoc-in
                 [:murakumo.ci/runs "logical-1.replica.1" :ci.run/source :source/type]
                 :radicle)
        service (finalizer/create
                 {:store persistent :broker-state broker-state
                  :quorum-policy policy
                  :public-base-url "https://murakumo.cloud" :executors {}})]
    (is (empty? ((:tick! service))))
    (is (empty? ((:read persistent) finalizer/outbox-stream 0)))))

(deftest passed-release-quorum-enqueues-a-separate-deployment-action
  (let [release-verdict (assoc verdict :verdict/artifacts
                               [{:path "release.bundle.edn" :cid "bafybundle"
                                 :type :murakumo/release-bundle}])
        {broker-state :state policy :policy} (fixture release-verdict)
        persistent (store/memory-store)
        actions (atom [])
        service (finalizer/create
                 {:store persistent :broker-state broker-state
                  :quorum-policy policy
                  :deployment-policies {"o/r" {:environment "prod"
                                                :deploy-refs #{"refs/heads/main"}}}
                  :executors {:cd/deploy #(swap! actions conj %)}})]
    ((:tick! service))
    (is (= 1 (count @actions)))
    (is (= :cd/deploy (:ci.action/type (first @actions))))
    (is (= "bafybundle" (:ci.action/bundle-cid (first @actions))))))
