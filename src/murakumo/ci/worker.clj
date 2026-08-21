(ns murakumo.ci.worker
  "One remote runner's lease-to-signed-completion execution path."
  (:require [clojure.java.io :as io]
            [ed25519.core :as ed]
            [kotoba-git.repo :as repo]
            [murakumo.artifact-store :as artifact-store]
            [murakumo.canonical :as canonical]
            [murakumo.ci.attest :as attest]
            [murakumo.ci.artifact-upload :as artifact-upload]
            [murakumo.ci.git :as git]
            [murakumo.ci.host :as host]
            [murakumo.ci.import :as source-import]
            [murakumo.ci.pipeline :as pipeline]
            [murakumo.ci.protocol :as protocol]
            [murakumo.ci.release-bundle :as release-bundle]
            [murakumo.ci.runner :as runner]
            [murakumo.identity :as identity]))

(defn rpc-client
  [{:keys [broker-request timeout-ms request-fn]
    :or {timeout-ms 10000}}]
  (let [request-fn (or request-fn
                       (requiring-resolve 'murakumo.overlay.quic-driver/request!))]
    (fn [message]
      (let [{:keys [ok? response error]}
            (request-fn broker-request message timeout-ms)]
        (when-not (and ok? (protocol/valid? response))
          (throw (ex-info "murakumo-ci: broker RPC failed"
                          {:reason :broker-rpc-failed :error error})))
        (when (= :ci/error (:murakumo.ci/type response))
          (throw (ex-info "murakumo-ci: broker rejected request"
                          (:murakumo.ci/body response))))
        response))))

(defn- stable-receipt-cid [receipt]
  (identity/graph-cid (canonical/string receipt)))

(defn- failure-execution [run runner-id failure]
  (let [receipt {:receipt/version 1
                 :receipt/run-id (:ci.run/logical-id run)
                 :receipt/source (:ci.run/source run)
                 :receipt/pipeline-digest (:ci.run/pipeline-digest run)
                 :receipt/runner-id runner-id :receipt/status :failed
                 :receipt/jobs [] :receipt/failure failure}]
    {:result :failed :receipt receipt :receipt-cid (stable-receipt-cid receipt)}))

(defn- package-release [execution manifest-path cas]
  (if (and (= :passed (:result execution)) manifest-path)
    (let [receipt (release-bundle/build! (:receipt execution) manifest-path
                                         (:get cas) (:put! cas))]
      (assoc execution :receipt receipt :receipt-cid (stable-receipt-cid receipt)))
    execution))

(defn execute-offer!
  [{:keys [rpc runner-id signer-seed rid workspace-root source-remotes
           artifact-root runtime release-manifest-path heartbeat-ms
           mirror-artifacts?
           checkout-fn import-fn load-pipeline-fn run-fn attest-fn persist-fn
           clock-seconds]
    :or {heartbeat-ms 20000
         checkout-fn git/checkout!
         import-fn source-import/import-worktree
         load-pipeline-fn pipeline/load-pipeline
         run-fn runner/run
         attest-fn attest/attest
         clock-seconds #(quot (System/currentTimeMillis) 1000)}}
   offer]
  (when-not (= :ci/lease-offer (:murakumo.ci/type offer))
    (throw (ex-info "murakumo-ci: worker requires a lease offer"
                    {:reason :invalid-lease-offer})))
  (let [{:keys [lease run]} (:murakumo.ci/body offer)
        logical-id (:ci.run/logical-id run)
        remote (get source-remotes (get-in run [:ci.run/source :source/repo]))
        revision (get-in run [:ci.run/source :source/revision])
        attempt-id (:ci.lease/token lease)
        workspace (str (io/file workspace-root (:ci.run/id run) attempt-id "source"))
        output-root (str (io/file workspace-root (:ci.run/id run) attempt-id "outputs"))
        local-cas (artifact-store/adapter artifact-root)
        source-db (atom (repo/empty-repo))
        current-lease (atom lease)
        remote-put (when mirror-artifacts?
                     (artifact-upload/uploader
                      {:rpc rpc :lease-fn #(deref current-lease)}))
        cas {:get (:get local-cas)
             :put! (fn [cid bytes]
                     ((:put! local-cas) cid bytes)
                     (when remote-put (remote-put cid bytes))
                     cid)}
        stop-heartbeat (promise)
        heartbeat-error (atom nil)]
    (when-not (re-matches #"[A-Za-z0-9._-]+" (:ci.run/id run))
      (throw (ex-info "murakumo-ci: unsafe broker run id" {:reason :unsafe-run-id})))
    (when-not (re-matches #"[A-Za-z0-9._-]+" attempt-id)
      (throw (ex-info "murakumo-ci: unsafe lease token" {:reason :unsafe-lease-token})))
    (.mkdirs (io/file workspace))
    (rpc (protocol/message :ci/run-started {:lease lease}))
    (let [heartbeat
          (future
            (loop []
              (when (= ::timeout (deref stop-heartbeat heartbeat-ms ::timeout))
                (let [continue?
                      (try
                        (let [response (rpc (protocol/message :ci/heartbeat
                                                              {:lease @current-lease}))]
                          (reset! current-lease
                                  (get-in response [:murakumo.ci/body :lease]))
                          true)
                        (catch Throwable t
                          (reset! heartbeat-error t)
                          false))]
                  (when continue? (recur))))))
          execution
          (try
            (when-not remote
              (throw (ex-info "murakumo-ci: source remote is not configured"
                              {:reason :source-remote-missing})))
            (checkout-fn host/execute-command! remote revision workspace)
            (let [imported (import-fn workspace revision)
                  _ (reset! source-db (:db imported))
                  enriched (-> run
                               (assoc :ci.run/id logical-id)
                               (source-import/attach imported))
                  loaded (load-pipeline-fn workspace)]
              (when-not (= (:ci.run/pipeline-digest run) (:pipeline-digest loaded))
                (throw (ex-info "murakumo-ci: checked-out pipeline digest mismatch"
                                {:reason :pipeline-digest-mismatch})))
              (package-release
               (run-fn host/execute!
                       {:source-dir workspace :output-dir output-root
                        :runtime (or runtime "podman")
                        :store-artifact! (:put! cas)}
                       enriched (:pipeline loaded) (:waves loaded) runner-id)
               release-manifest-path cas))
            (catch Throwable t
              (failure-execution (assoc run :ci.run/logical-id logical-id)
                                 runner-id
                                 {:reason (or (:reason (ex-data t)) :worker-failure)
                                  :message (.getMessage t)})))
          attestation (attest-fn @source-db rid signer-seed execution
                                 (clock-seconds))
          snapshot-cid ((or persist-fn repo/persist!)
                        (:put! cas) (:db attestation) nil)]
      (deliver stop-heartbeat true)
      @heartbeat
      (when-let [error @heartbeat-error]
        (throw (ex-info "murakumo-ci: lease heartbeat failed"
                        {:reason :heartbeat-failed} error)))
      (let [completion
            (protocol/attested-completion
             @current-lease (:result execution) (:receipt-blob-cid attestation)
             {:verdict-cid (:verdict-cid attestation)
              :verdict (attest/verdict (:receipt execution))
              :receipt-commit-cid (:receipt-commit-cid attestation)
              :receipt-snapshot-cid snapshot-cid
              :sigref (:sigref attestation)})]
        (rpc completion)
        {:execution execution :attestation attestation
         :snapshot-cid snapshot-cid :lease @current-lease}))))

(defn poll-once!
  [{:keys [rpc runner] :as opts}]
  (let [response (rpc (protocol/lease-request runner))]
    (if (= :ci/no-work (:murakumo.ci/type response))
      {:status :idle}
      {:status :completed :result (execute-offer! opts response)})))
