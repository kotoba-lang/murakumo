(ns murakumo.artifact-replication-test
  (:require [clojure.test :refer [deftest is testing]]
            [ed25519.core :as ed]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.artifact-protocol :as protocol]
            [murakumo.artifact-remote :as remote]
            [murakumo.artifact-replication :as replication]
            [murakumo.artifact-store :as store]
            [murakumo.canonical :as canonical]
            [murakumo.cd.bundle :as bundle]
            [murakumo.cd.capability :as capability]))

(defn blob [text]
  (let [bytes (.getBytes text java.nio.charset.StandardCharsets/UTF_8)]
    [(second (object/write-blob (repo/empty-repo) bytes)) bytes]))

(deftest signed-release-is-chunked-and-replicated-to-remote-cas
  (let [[component-cid component-bytes] (blob (apply str (repeat 100 "wasm")))
        document (bundle/create
                  {:revision "v2"
                   :manifest (pr-str {:kotoba.app/components
                                      [{:name "demo" :cid component-cid}]})
                   :components [{:path "components/demo.wasm" :cid component-cid}]})
        bundle-cid (bundle/cid document)
        bundle-bytes (canonical/encode-bytes document)
        seed (byte-array (repeat 32 (byte 5)))
        issuer (ed/did-key-from-seed seed)
        issued (capability/issue
                seed "rid" {:verdict-cid "verdict" :artifact-cid bundle-cid
                            :previous-artifact-cid bundle-cid :environment "prod"
                            :revision "v2" :previous-revision "v2"
                            :now 10 :ttl 20 :nonce "replicate"})
        source {bundle-cid bundle-bytes component-cid component-bytes}
        root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-remote-cas"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        cas (store/adapter root)
        handler (remote/handler
                 {:node-id "node-a" :rid "rid" :issuers #{issuer}
                  :clock-fn (constantly 11)
                  :temp-dir (str root "/tmp") :put! (:put! cas) :get-bytes (:get cas)
                  :max-chunk-bytes 32})
        calls (atom [])
        replicate! (replication/create-overlay-replicator
                    {:node-lookup (constantly {:host "node-a" :port 4433})
                     :session {:overlay "prod" :node "controller"}
                     :issued-capability issued :source-get source :chunk-bytes 32
                     :request-fn (fn [_ payload _]
                                   (swap! calls conj (:murakumo.artifact/operation payload))
                                   {:ok? true :response (handler payload)})})
        result (replicate! {:node "node-a" :artifact-cid bundle-cid
                            :revision "v2" :action :deploy})]
    (is (:ok? result) (pr-str result))
    (is (= (set [bundle-cid component-cid]) (set (:replicated result))))
    (is (= (seq bundle-bytes) (seq ((:get cas) bundle-cid))))
    (is (= (seq component-bytes) (seq ((:get cas) component-cid))))
    (is (> (count (filter #{:chunk} @calls)) 2))
    (testing "a valid capability cannot upload an object absent from its bundle"
      (let [[other-cid _] (blob "other")
            response (handler
                      (protocol/request
                       :begin
                       {:murakumo.artifact/node "node-a"
                        :murakumo.artifact/issued-capability issued
                        :murakumo.artifact/environment "prod"
                        :murakumo.artifact/verdict-cid "verdict"
                        :murakumo.artifact/release-action :deploy
                        :murakumo.artifact/bundle-cid bundle-cid
                        :murakumo.artifact/revision "v2"
                        :murakumo.artifact/transfer-id "abcdefghijklmnop"
                        :murakumo.artifact/object-cid other-cid
                        :murakumo.artifact/size 5}))]
        (is (= :object-out-of-scope
               (get-in response [:murakumo.artifact/result :reason])))))))

(deftest expired-capability-cannot-begin-transfer
  (let [[cid _] (blob "x")
        seed (byte-array (repeat 32 (byte 6)))
        issued (capability/issue seed "rid"
                                 {:verdict-cid "v" :artifact-cid cid
                                  :previous-artifact-cid cid :environment "prod"
                                  :revision "r" :previous-revision "r"
                                  :now 1 :ttl 1 :nonce "expired"})
        handler (remote/handler
                 {:node-id "n" :rid "rid" :issuers #{(ed/did-key-from-seed seed)}
                  :clock-fn (constantly 3)
                  :temp-dir (str (java.nio.file.Files/createTempDirectory
                                  "expired-transfer"
                                  (make-array java.nio.file.attribute.FileAttribute 0)))
                  :put! (fn [& _]) :get-bytes (constantly nil)})
        response (handler
                  (protocol/request
                   :begin {:murakumo.artifact/node "n"
                           :murakumo.artifact/issued-capability issued
                           :murakumo.artifact/environment "prod"
                           :murakumo.artifact/verdict-cid "v"
                           :murakumo.artifact/release-action :deploy
                           :murakumo.artifact/bundle-cid cid
                           :murakumo.artifact/revision "r"
                           :murakumo.artifact/transfer-id "abcdefghijklmnop"
                           :murakumo.artifact/object-cid cid
                           :murakumo.artifact/size 1}))]
    (is (= :unauthorized (get-in response [:murakumo.artifact/result :reason])))))
