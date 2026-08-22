(ns murakumo.ci-artifact-upload-test
  (:require [clojure.test :refer [deftest is]]
            [multiformats.core :as multiformats]
            [murakumo.artifact-store :as artifact-store]
            [murakumo.ci.artifact-upload :as upload]
            [murakumo.ci.protocol :as protocol]))

(defn temp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(def lease {:ci.lease/run-id "run.0" :ci.lease/runner-id "did:key:runner"
            :ci.lease/token "token" :ci.lease/expires-at 1000})

(defn running-state []
  (atom {:murakumo.ci/runs
         {"run.0" {:ci.run/state :running :ci.run/lease lease}}}))

(deftest uploader-stages-chunks-under-active-lease-and-cas-verifies
  (let [root (temp-dir "murakumo-ci-upload")
        cas (artifact-store/adapter (str root "/cas"))
        server (upload/handler {:broker-state (running-state)
                                :clock-ms (constantly 10)
                                :temp-dir (str root "/tmp")
                                :put! (:put! cas) :get-bytes (:get cas)
                                :max-chunk-bytes 4})
        rpc server
        put! (upload/uploader {:rpc rpc :lease-fn (constantly lease)
                               :chunk-bytes 4})
        bytes (.getBytes "verified artifact" "UTF-8")
        cid (multiformats/cidv1-raw bytes)]
    (is (= cid (put! cid bytes)))
    (is (= (seq bytes) (seq ((:get cas) cid))))
    (is (= cid (put! cid bytes)))))

(deftest expired-lease-cannot-stage
  (let [root (temp-dir "murakumo-ci-upload-denied")
        cas (artifact-store/adapter (str root "/cas"))
        server (upload/handler {:broker-state (running-state)
                                :clock-ms (constantly 1000)
                                :temp-dir (str root "/tmp")
                                :put! (:put! cas) :get-bytes (:get cas)})
        bytes (.getBytes "x" "UTF-8")
        cid (multiformats/cidv1-raw bytes)
        result (get-in (server (protocol/message
                                :ci/artifact-begin
                                {:lease lease :transfer-id "0123456789abcdef"
                                 :object-cid cid :size 1}))
                       [:murakumo.ci/body :result])]
    (is (= {:ok? false :reason :unauthorized-lease} result))
    (is (nil? ((:get cas) cid)))))

(deftest abandoned-transfer-is-expired-before-more-bytes-are-accepted
  (let [root (temp-dir "murakumo-ci-upload-ttl")
        cas (artifact-store/adapter (str root "/cas"))
        now (atom 10)
        server (upload/handler {:broker-state (running-state) :clock-ms #(deref now)
                                :transfer-ttl-ms 5 :temp-dir (str root "/tmp")
                                :put! (:put! cas) :get-bytes (:get cas)})
        bytes (.getBytes "x" "UTF-8")
        cid (multiformats/cidv1-raw bytes)
        fields {:lease lease :transfer-id "fedcba9876543210"
                :object-cid cid}]
    (is (true? (get-in (server (protocol/message :ci/artifact-begin
                                                  (assoc fields :size 1)))
                       [:murakumo.ci/body :result :ok?])))
    (reset! now 15)
    (is (= :unknown-transfer
           (get-in (server (protocol/message :ci/artifact-chunk
                                             (assoc fields :sequence 0 :data "eA==")))
                   [:murakumo.ci/body :result :reason])))))
