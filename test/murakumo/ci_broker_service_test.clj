(ns murakumo.ci-broker-service-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba-rad.sigref :as sigref]
            [murakumo.ci.attest :as attest]
            [murakumo.ci.broker-service :as service]
            [murakumo.ci.file-store :as file-store]
            [murakumo.ci.protocol :as protocol]
            [murakumo.ci.store :as store]))

(def run {:ci.run/id "run-1" :ci.run/source {:source/revision "abc"}
          :ci.run/pipeline-digest "pipeline" :ci.run/requires #{:linux}})
(def runner {:runner/id "did:key:zRunner" :runner/capabilities #{:linux}
             :runner/environment-digest "env"})

(deftest persistent-rpc-service-drives-full-lease-lifecycle
  (let [now (atom 100)
        root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-broker"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        persistent (file-store/create root)
        coordinator (service/create {:store persistent :clock-ms #(long @now)
                                     :lease-ttl-ms 50 :token-fn (constantly "token")})
        _ ((:submit! coordinator) run)
        offer ((:handler coordinator) (protocol/lease-request runner))
        lease (get-in offer [:murakumo.ci/body :lease])]
    (is (= :ci/lease-offer (:murakumo.ci/type offer)))
    (is (= "token" (:ci.lease/token lease)))
    (is (= :ci/ack
           (:murakumo.ci/type
            ((:handler coordinator)
             (protocol/message :ci/run-started {:lease lease})))))
    (reset! now 120)
    (let [heartbeat ((:handler coordinator)
                     (protocol/message :ci/heartbeat {:lease lease}))
          renewed (get-in heartbeat [:murakumo.ci/body :lease])]
      (is (= 170 (:ci.lease/expires-at renewed)))
      (is (= :ci/ack
             (:murakumo.ci/type
              ((:handler coordinator)
               (protocol/completion renewed :passed "bafyreceipt"))))))
    (let [restored (service/create {:store (file-store/create root)})]
      (is (= :passed
             (get-in @(:state restored) [:murakumo.ci/runs "run-1" :ci.run/state])))
      (is (= 5 (count (store/events-since persistent 0)))))))

(deftest rejected-transition-does-not-replace-durable-state
  (let [persistent (store/memory-store)
        coordinator (service/create {:store persistent :clock-ms (constantly 1)})
        before @(:state coordinator)
        response ((:handler coordinator)
                  (protocol/message :ci/run-started
                                    {:lease {:ci.lease/token "fake"
                                             :ci.lease/run-id "missing"}}))]
    (is (= :ci/error (:murakumo.ci/type response)))
    (is (= before @(:state coordinator)))
    (is (= before (store/restore persistent)))))

(deftest lease-expiry-is-persisted-before-new-work-is-offered
  (let [now (atom 0)
        coordinator (service/create {:store (store/memory-store)
                                     :clock-ms #(long @now) :lease-ttl-ms 10
                                     :token-fn (let [n (atom 0)] #(str "t" (swap! n inc)))})
        _ ((:submit! coordinator) run)
        first-offer ((:handler coordinator) (protocol/lease-request runner))
        first-lease (get-in first-offer [:murakumo.ci/body :lease])]
    ((:handler coordinator) (protocol/message :ci/run-started {:lease first-lease}))
    (reset! now 10)
    (let [next-offer ((:handler coordinator) (protocol/lease-request runner))]
      (is (= :ci/lease-offer (:murakumo.ci/type next-offer)))
      (is (= "t2" (get-in next-offer [:murakumo.ci/body :lease :ci.lease/token]))))))

(deftest logical-run-replicas-require-distinct-signed-runners
  (let [seeds [(byte-array (repeat 32 (byte 1)))
               (byte-array (repeat 32 (byte 2)))]
        runners (mapv (fn [seed]
                        {:runner/id (ed/did-key-from-seed seed)
                         :runner/capabilities #{:linux}})
                      seeds)
        delegates (set (map :runner/id runners))
        coordinator (service/create
                     {:store (store/memory-store) :clock-ms (constantly 10)
                      :lease-ttl-ms 100 :replicas 2 :rid "rid-ci"
                      :authorized-runners delegates :require-attestation? true
                      :token-fn (let [n (atom 0)] #(str "token-" (swap! n inc)))})
        submitted ((:submit! coordinator) run)
        logical-id (:ci.run/logical-id submitted)
        first-offer ((:handler coordinator) (protocol/lease-request (first runners)))
        first-lease (get-in first-offer [:murakumo.ci/body :lease])]
    (is (= 2 (count (:ci.run/replicas submitted))))
    (is (= :ci/no-work
           (:murakumo.ci/type
            ((:handler coordinator) (protocol/lease-request (first runners))))))
    (doseq [[seed runner offer]
            [[(first seeds) (first runners) first-offer]
             [(second seeds) (second runners)
              ((:handler coordinator) (protocol/lease-request (second runners)))]]]
      (let [lease (get-in offer [:murakumo.ci/body :lease])
            verdict {:verdict/version 1 :verdict/run-id logical-id
                     :verdict/status :passed :verdict/artifacts []}
            verdict-cid (attest/verdict-cid verdict)
            sr (sigref/sign seed "rid-ci" (attest/result-ref logical-id)
                            verdict-cid 10)]
        ((:handler coordinator) (protocol/message :ci/run-started {:lease lease}))
        (is (= :ci/ack
               (:murakumo.ci/type
                ((:handler coordinator)
                 (protocol/attested-completion
                  lease :passed "receipt"
                  {:verdict-cid verdict-cid :verdict verdict :sigref sr})))))))
    (let [status ((:handler coordinator)
                  (protocol/message :ci/run-status {:run-id logical-id}))
          attestations (get-in status [:murakumo.ci/body :attestations])]
      (is (= 2 (count attestations)))
      (is (= (get-in attestations [0 :verdict-cid])
             (attest/canonical-verdict
              {:rid "rid-ci" :run-id logical-id
               :delegates delegates :threshold 2}
              (mapv :sigref attestations)))))))

(deftest signed-completion-is-rejected-until-attested-objects-are-in-cas
  (let [seed (byte-array (repeat 32 (byte 4)))
        runner-id (ed/did-key-from-seed seed)
        coordinator (service/create
                     {:store (store/memory-store) :clock-ms (constantly 10)
                      :lease-ttl-ms 100 :rid "rid-ci"
                      :authorized-runners #{runner-id} :require-attestation? true
                      :artifact-exists? (constantly false)})
        _ ((:submit! coordinator) run)
        offer ((:handler coordinator)
               (protocol/lease-request {:runner/id runner-id
                                        :runner/capabilities #{:linux}}))
        lease (get-in offer [:murakumo.ci/body :lease])
        logical-id (get-in offer [:murakumo.ci/body :run :ci.run/logical-id])
        verdict {:verdict/version 1 :verdict/run-id logical-id
                 :verdict/status :passed :verdict/artifacts []}
        cid (attest/verdict-cid verdict)
        sr (sigref/sign seed "rid-ci" (attest/result-ref logical-id) cid 10)]
    ((:handler coordinator) (protocol/message :ci/run-started {:lease lease}))
    (let [reply ((:handler coordinator)
                 (protocol/attested-completion
                  lease :passed "receipt"
                  {:verdict-cid cid :verdict verdict
                   :receipt-commit-cid "bafycommit"
                   :receipt-snapshot-cid "bafysnapshot" :sigref sr}))]
      (is (= :ci/error (:murakumo.ci/type reply)))
      (is (= :attested-objects-unavailable
             (get-in reply [:murakumo.ci/body :reason])))
      (is (= :running
             (get-in @(:state coordinator)
                     [:murakumo.ci/runs "run-1" :ci.run/state]))))))
