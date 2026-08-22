(ns murakumo.cd-quic-live-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [murakumo.cd.capability :as capability]
            [murakumo.cd.fleet-adapter :as fleet-adapter]
            [murakumo.cd.remote :as remote]
            [murakumo.overlay.quic-driver :as quic-driver]))

(deftest signed-deploy-crosses-real-localhost-quic
  (let [seed (byte-array (repeat 32 (byte 3)))
        issuer (ed/did-key-from-seed seed)
        issued (capability/issue
                seed "rid-live"
                {:verdict-cid "verdict" :artifact-cid "artifact-v2"
                 :previous-artifact-cid "artifact-v1" :environment "test"
                 :revision "v2" :previous-revision "v1"
                 :now 10 :ttl 20 :nonce "live-quic"})
        port 18446
        request {:type "murakumo.overlay.adapter-request" :version 1
                 :transport :quic
                 :session {:overlay "cd-live" :node "controller"}
                 :connect {:host "127.0.0.1" :port port}}
        observed (promise)
        handler (remote/handler
                 {:node-id "node-live" :rid "rid-live" :issuers #{issuer}
                  :clock-fn (constantly 11)
                  :deploy-fn (fn [operation]
                               (deliver observed operation) {:ok? true})
                  :health-fn (constantly {:ok? true})
                  :rollback-fn (constantly {:ok? true})})
        server (future (quic-driver/serve-once-rpc! request handler 5000))
        adapter (fleet-adapter/create-overlay-adapter
                 {:node-lookup (constantly {:host "127.0.0.1" :port port})
                  :session (:session request) :issued-capability issued
                  :timeout-ms 3000})]
    (Thread/sleep 400)
    (let [result ((:deploy-fn adapter)
                  {:node "node-live" :artifact-cid "artifact-v2" :revision "v2"})]
      (is (:ok? result) (pr-str result))
      (is (= {:node "node-live" :artifact-cid "artifact-v2"
             :environment "test" :revision "v2"}
             (deref observed 1000 nil))))
    (is (:ok? @server))))
