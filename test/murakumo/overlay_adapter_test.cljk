;; murakumo.overlay-adapter-test — reference external adapter driver tests.

(ns murakumo.overlay-adapter-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.adapter :as adapter]))

(defn request [port]
  {:type "murakumo.overlay.adapter-request"
   :version 1
   :action :dial
   :transport :quic
   :connect {:endpoint :direct
             :kind :quic
             :transport "quic"
             :host "127.0.0.1"
             :port port
             :path "/bafyNode"
             :overlay "bafyOverlay"
             :node "bafyNode"
             :name "local"
             :principal {:from "operator" :to "fleet" :capability "ssh"}}
   :session {:type "murakumo.overlay.session"
             :overlay "bafyOverlay"
             :node "bafyNode"
             :name "local"
             :principal {:from "operator" :to "fleet" :capability "ssh"}}})

(deftest adapter-argv-parses-edn-request
  (let [req (request 4001)
        opts (adapter/parse-argv ["check" "--request-edn" (pr-str req)
                                  "--timeout-ms" "25"])]
    (is (= :check (:action opts)))
    (is (= 25 (:timeout-ms opts)))
    (is (= req (:request opts)))
    (is (true? (adapter/valid-request? req)))))

(deftest invalid-request-is-rejected
  (is (= {:ok? false
          :type "murakumo.overlay.adapter-driver-result"
          :mode :invalid-request
          :action :check
          :reason :missing-or-invalid-request}
         (adapter/execute {:action :check}))))

(deftest adapter-dial-handshakes-with-reference-listener
  (let [listener (adapter/open-listener! (request 0))
        port (get-in listener [:listen :bound-port])
        req (request port)]
    (try
      (.setSoTimeout (:server listener) 1000)
      (let [worker (future
                     (with-open [socket (.accept (:server listener))]
                       (adapter/handle-connection! req socket)))
            dial-result (adapter/dial! req 1000)
            ack @worker]
        (is (true? (:ok? dial-result)))
        (is (= :adapter-stream (:mode dial-result)))
        (is (= "murakumo.overlay.adapter-ack" (get-in dial-result [:ack :type])))
        (is (true? (:accepted? ack))))
      (finally
        (adapter/close-listener! listener)))))
