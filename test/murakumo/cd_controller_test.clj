(ns murakumo.cd-controller-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.artifact-store :as artifact-store]
            [murakumo.canonical :as canonical]
            [murakumo.cd.bundle :as bundle]
            [murakumo.cd.capability :as capability]
            [murakumo.cd.controller :as controller]
            [murakumo.cd.rollout :as rollout]
            [murakumo.cd.service :as service]))

(defn blob [text]
  (let [bytes (.getBytes text java.nio.charset.StandardCharsets/UTF_8)]
    [(second (object/write-blob (repo/empty-repo) bytes)) bytes]))

(deftest controller-stages-each-node-before-canary-and-promotion
  (let [[component-cid component-bytes] (blob "component-v2")
        [previous-component-cid previous-component-bytes] (blob "component-v1")
        make-bundle (fn [revision cid]
                      (bundle/create
                       {:revision revision
                        :manifest (pr-str {:kotoba.app/components [{:name "app" :cid cid}]})
                        :components [{:path "components/app.wasm" :cid cid}]}))
        current (make-bundle "v2" component-cid)
        previous (make-bundle "v1" previous-component-cid)
        current-cid (bundle/cid current)
        previous-cid (bundle/cid previous)
        source {current-cid (canonical/encode-bytes current)
                previous-cid (canonical/encode-bytes previous)
                component-cid component-bytes
                previous-component-cid previous-component-bytes}
        seed (byte-array (repeat 32 (byte 2)))
        issuer (ed/did-key-from-seed seed)
        issued (capability/issue
                seed "rid" {:verdict-cid "verdict" :artifact-cid current-cid
                            :previous-artifact-cid previous-cid :environment "prod"
                            :revision "v2" :previous-revision "v1"
                            :now 10 :ttl 30 :nonce "controller"})
        nodes ["canary" "node-b"]
        events (atom [])
        handlers
        (into {}
              (for [node nodes
                    :let [root (str (java.nio.file.Files/createTempDirectory
                                     (str "controller-" node)
                                     (make-array java.nio.file.attribute.FileAttribute 0)))
                          cas (artifact-store/adapter (str root "/cas"))
                          operations
                          {:deploy-fn (fn [{:keys [artifact-cid]}]
                                        (swap! events conj [:deploy node
                                                            (boolean ((:get cas) artifact-cid))])
                                        {:ok? (boolean ((:get cas) artifact-cid))})
                           :health-fn (fn [_] (swap! events conj [:health node]) {:ok? true})
                           :rollback-fn (fn [_] (swap! events conj [:rollback node]) {:ok? true})}
                          trust {:node-id node :rid "rid" :issuers #{issuer}
                                 :clock-fn (constantly 11)}]]
                [node (service/create-node-handler-with-operations
                       trust operations
                       {:temp-dir (str root "/tmp")
                        :put! (:put! cas) :get-bytes (:get cas)})]))
        node-lookup (fn [node] {:host node :port 4433})
        request-fn (fn [request payload _]
                     {:ok? true
                      :response ((get handlers (get-in request [:connect :host])) payload)})
        plan (rollout/plan {:capability-cid (:capability-cid issued)
                            :artifact-cid current-cid
                            :previous-artifact-cid previous-cid
                            :environment "prod" :revision "v2" :previous-revision "v1"
                            :targets nodes :batch-size 1})
        result (controller/execute-release!
                {:node-lookup node-lookup :session {:overlay "prod" :node "controller"}
                 :issued-capability issued :source-get source :request-fn request-fn
                 :chunk-bytes 16 :clock-fn (constantly 11) :rollout-plan plan
                 :verification-policy
                 {:rid "rid" :issuers #{issuer} :environment "prod"
                  :artifact-cid current-cid :previous-artifact-cid previous-cid
                  :verdict-cid "verdict" :revision "v2" :previous-revision "v1"}})]
    (is (= :succeeded (get-in result [:rollout :cd.rollout/state])))
    (is (= [[:deploy "canary" true] [:health "canary"]
            [:deploy "node-b" true] [:health "node-b"]]
           @events))))
