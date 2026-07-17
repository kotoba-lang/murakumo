(ns murakumo.cd-automation-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.artifact-store :as artifact-store]
            [murakumo.canonical :as canonical]
            [murakumo.cd.automation :as automation]
            [murakumo.cd.bundle :as bundle]
            [murakumo.cd.receipt :as receipt]
            [murakumo.cd.service :as service]
            [murakumo.ci.store :as store]))

(defn blob [text]
  (let [bytes (.getBytes text "UTF-8")]
    [(second (object/write-blob (repo/empty-repo) bytes)) bytes]))

(deftest canonical-action-replicates-rolls-out-and-advances-environment-once
  (let [[current-component current-bytes] (blob "v2")
        [previous-component previous-bytes] (blob "v1")
        make-bundle (fn [revision cid]
                      (bundle/create
                       {:revision revision
                        :manifest (pr-str {:kotoba.app/components [{:cid cid}]})
                        :components [{:path "app.wasm" :cid cid}]}))
        current (make-bundle "rev2" current-component)
        previous (make-bundle "rev1" previous-component)
        current-cid (bundle/cid current)
        previous-cid (bundle/cid previous)
        objects (atom {current-cid (canonical/encode-bytes current)
                       previous-cid (canonical/encode-bytes previous)
                       current-component current-bytes previous-component previous-bytes})
        seed (byte-array (repeat 32 (byte 7)))
        issuer (ed/did-key-from-seed seed)
        nodes ["canary" "node-b"]
        deploys (atom [])
        handlers
        (into {}
              (for [node nodes
                    :let [root (str (java.nio.file.Files/createTempDirectory
                                     (str "automation-" node)
                                     (make-array java.nio.file.attribute.FileAttribute 0)))
                          cas (artifact-store/adapter (str root "/cas"))]]
                [node (service/create-node-handler-with-operations
                       {:node-id node :rid "rid" :issuers #{issuer}
                        :clock-fn (constantly 11)}
                       {:deploy-fn (fn [request]
                                     (swap! deploys conj [node (:artifact-cid request)])
                                     {:ok? true})
                        :health-fn (constantly {:ok? true})
                        :rollback-fn (constantly {:ok? true})}
                       {:temp-dir (str root "/tmp")
                        :put! (:put! cas) :get-bytes (:get cas)})]))
        request-fn (fn [request payload _]
                     {:ok? true
                      :response ((get handlers (get-in request [:connect :host])) payload)})
        persistent (store/memory-store)
        policy {:environment "prod" :deploy-refs #{"refs/heads/main"}
                :targets nodes :batch-size 1
                :nodes {"canary" {:host "canary" :port 4433}
                        "node-b" {:host "node-b" :port 4433}}
                :previous-artifact-cid previous-cid :previous-revision "rev1"
                :capability-ttl-seconds 60
                :session {:overlay "prod" :node "coordinator"}}
        executor (automation/create-executor
                  {:store persistent :source-get #(get @objects %)
                   :store-artifact! (fn [cid bytes] (swap! objects assoc cid bytes) cid)
                   :rid "rid"
                   :policies {"o/r" policy} :issuer-seeds {"o/r" seed}
                   :request-fn request-fn :clock-seconds (constantly 11)})
        action {:ci.action/deployment "o/r" :ci.action/verdict-cid "verdict"
                :ci.action/bundle-cid current-cid :ci.action/revision "rev2"
                :ci.action/verdict
                {:verdict/status :passed
                 :verdict/artifacts [{:cid current-cid
                                      :type :murakumo/release-bundle}]}}
        first-result (executor action)
        second-result (executor action)]
    (is (= :deployed (:status first-result)))
    (is (= :already-deployed (:status second-result)))
    (is (receipt/valid? {:rid "rid" :executors #{issuer}}
                        (:receipt-attestation first-result)))
    (is (some? (get @objects
                    (get-in first-result
                            [:receipt-attestation :receipt-snapshot-cid]))))
    (is (= [["canary" current-cid] ["node-b" current-cid]] @deploys))
    (is (= current-cid
           (:cd.environment/artifact-cid
            ((:get persistent) automation/state-bucket
             (automation/policy-key "o/r")))))))
