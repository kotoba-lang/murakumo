(ns murakumo.ci-worker-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [murakumo.artifact-store :as artifact-store]
            [murakumo.ci.broker-service :as broker-service]
            [murakumo.ci.protocol :as protocol]
            [murakumo.ci.store :as store]
            [murakumo.ci.worker :as worker]))

(defn temp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn execution [run runner-id]
  (let [receipt {:receipt/version 1 :receipt/run-id (:ci.run/id run)
                 :receipt/source (:ci.run/source run)
                 :receipt/pipeline-digest (:ci.run/pipeline-digest run)
                 :receipt/runner-id runner-id :receipt/status :passed
                 :receipt/jobs []}]
    {:result :passed :receipt receipt :receipt-cid "logical"}))

(deftest worker-materializes-verifies-attests-persists-and-completes
  (let [seed (byte-array (repeat 32 (byte 8)))
        runner-id (ed/did-key-from-seed seed)
        logical-run {:ci.run/id "logical-run" :ci.run/source
                     {:source/repo "o/r"
                      :source/revision "0123456789abcdef0123456789abcdef01234567"}
                     :ci.run/pipeline-digest "pipeline" :ci.run/requires #{:linux}}
        root (temp-dir "murakumo-worker")
        coordinator-cas (artifact-store/adapter (str root "/coordinator-cas"))
        broker (broker-service/create
                {:store (store/memory-store) :clock-ms (constantly 10)
                 :lease-ttl-ms 100000 :rid "rid-ci"
                 :authorized-runners #{runner-id} :require-attestation? true
                 :artifact-upload-opts
                 {:temp-dir (str root "/coordinator-transfers")
                  :put! (:put! coordinator-cas) :get-bytes (:get coordinator-cas)}})
        _ ((:submit! broker) logical-run)
        rpc (:handler broker)
        result (worker/poll-once!
                {:rpc rpc :runner {:runner/id runner-id :runner/capabilities #{:linux}}
                 :runner-id runner-id :signer-seed seed :rid "rid-ci"
                 :workspace-root (str root "/work") :artifact-root (str root "/cas")
                 :mirror-artifacts? true
                 :source-remotes {"o/r" "https://example.invalid/o/r.git"}
                 :heartbeat-ms 100000
                 :checkout-fn (fn [_ _ _ workspace]
                                (spit (io/file workspace "source.clj") "(+ 1 2)")
                                {:workspace workspace})
                 :load-pipeline-fn (fn [_]
                                     {:pipeline {:ci/jobs {}} :pipeline-digest "pipeline"
                                      :waves []})
                 :run-fn (fn [_ _ run _ _ runner] (execution run runner))})
        status (rpc (protocol/message :ci/run-status {:run-id "logical-run"}))
        run (first (get-in status [:murakumo.ci/body :runs]))]
    (is (= :completed (:status result)))
    (is (= :passed (:ci.run/state run)))
    (is (= runner-id (get-in run [:ci.run/attestation :signer])))
    (is (string? (get-in run [:ci.run/attestation :receipt-snapshot-cid])))
    (is (some? ((:get coordinator-cas)
                (get-in run [:ci.run/attestation :receipt-snapshot-cid]))))))

(deftest pipeline-digest-mismatch-becomes-a-signed-failed-completion
  (let [seed (byte-array (repeat 32 (byte 9)))
        runner-id (ed/did-key-from-seed seed)
        run {:ci.run/id "bad-pipeline" :ci.run/source
             {:source/repo "o/r"
              :source/revision "0123456789abcdef0123456789abcdef01234567"}
             :ci.run/pipeline-digest "expected"}
        broker (broker-service/create
                {:store (store/memory-store) :clock-ms (constantly 10)
                 :lease-ttl-ms 100000 :rid "rid-ci"
                 :authorized-runners #{runner-id} :require-attestation? true})
        _ ((:submit! broker) run)
        root (temp-dir "murakumo-worker-fail")
        result (worker/poll-once!
                {:rpc (:handler broker) :runner {:runner/id runner-id}
                 :runner-id runner-id :signer-seed seed :rid "rid-ci"
                 :workspace-root (str root "/work") :artifact-root (str root "/cas")
                 :source-remotes {"o/r" "https://example.invalid/o/r.git"}
                 :heartbeat-ms 100000
                 :checkout-fn (fn [_ _ _ workspace]
                                (spit (io/file workspace "source") "x"))
                 :load-pipeline-fn (constantly {:pipeline {} :pipeline-digest "different"
                                                :waves []})})]
    (is (= :failed (get-in result [:result :execution :result])))
    (is (= :pipeline-digest-mismatch
           (get-in result [:result :execution :receipt :receipt/failure :reason])))))
