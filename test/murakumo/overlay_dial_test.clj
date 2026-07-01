;; murakumo.overlay-dial-test — host dial reachability checks.

(ns murakumo.overlay-dial-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.dial :as dial]
            [murakumo.overlay.relay :as relay])
  (:import [java.net ServerSocket SocketTimeoutException]))

(defn with-test-listener [f]
  (let [server (ServerSocket. 0)]
    (.setSoTimeout server 3000)
    (future
      (try
        (with-open [socket (.accept server)]
          (.write (.getOutputStream socket)
                  (.getBytes "murakumo test\n" "UTF-8")))
        (catch SocketTimeoutException _ nil)
        (finally
          (.close server))))
    (f (.getLocalPort server))))

(deftest dial-check-connects-to-reachable-endpoint
  (with-test-listener
    (fn [port]
      (let [result (dial/check! {:type "murakumo.overlay.session"
                                 :overlay "bafyOverlay"
                                 :node "bafyNode"
                                 :name "local"
                                 :principal {:from "operator"
                                             :to "fleet"
                                             :capability "ssh"}
                                 :direct {:transport "quic"
                                          :kind :quic
                                          :endpoint (str "quic://127.0.0.1:" port)}}
                                {:timeout-ms 1000})]
        (is (true? (:ok? result)))
        (is (= :connected (:mode result)))
        (is (= port (get-in result [:connect :port])))
        (is (= "127.0.0.1" (get-in result [:connect :host])))))))

(deftest dial-check-handshakes-with-relay-endpoint
  (let [{:keys [server] :as listener}
        (relay/open-listener! {:type "murakumo.overlay.relay"
                               :overlay "bafyOverlay"
                               :name "local-relay"
                               :region "test"
                               :url "relay://127.0.0.1:0"
                               :bind-host "127.0.0.1"
                               :port 0
                               :transports ["quic"]})
        port (get-in listener [:listen :bound-port])
        worker (future
                 (with-open [socket (.accept server)]
                   (relay/handle-connection!
                    {:overlay "bafyOverlay"
                     :name "local-relay"
                     :region "test"}
                    socket)))]
    (try
      (let [result (dial/check! {:type "murakumo.overlay.session"
                                 :overlay "bafyOverlay"
                                 :node "bafyNode"
                                 :name "local"
                                 :principal {:from "operator"
                                             :to "fleet"
                                             :capability "ssh"}
                                 :direct {:transport "quic"
                                          :kind :quic
                                          :endpoint "quic://unreachable.invalid:4001"}
                                 :relay {:transport "quic"
                                         :kind :relay
                                         :endpoint (str "relay://127.0.0.1:" port "/bafyNode")}}
                                {:endpoint :relay
                                 :timeout-ms 1000})]
        @worker
        (is (true? (:ok? result)))
        (is (= :relay-stream (:mode result)))
        (is (= "murakumo.overlay.relay-ack" (get-in result [:relay-ack :type])))
        (is (= "murakumo.overlay.relay-frame-ack"
               (get-in result [:relay-frame-ack :type])))
        (is (= "bafyNode" (get-in result [:relay-ack :node])))
        (is (= "/bafyNode" (get-in result [:relay-ack :target])))
        (is (= "murakumo overlay ping"
               (get-in result [:relay-frame-ack :payload])))
        (is (true? (get-in result [:relay-frame-ack :digest-ok?])))
        (is (true? (get-in result [:relay-frame-ack :mac-ok?]))))
      (finally
        (relay/close-listener! listener)))))

(deftest dial-check-streams-multiple-frames-through-relay
  (let [{:keys [server] :as listener}
        (relay/open-listener! {:type "murakumo.overlay.relay"
                               :overlay "bafyOverlay"
                               :name "local-relay"
                               :region "test"
                               :url "relay://127.0.0.1:0"
                               :bind-host "127.0.0.1"
                               :port 0
                               :transports ["quic"]})
        port (get-in listener [:listen :bound-port])
        worker (future
                 (with-open [socket (.accept server)]
                   (relay/handle-connection!
                    {:overlay "bafyOverlay"
                     :name "local-relay"
                     :region "test"}
                    socket)))]
    (try
      (let [result (dial/check! {:type "murakumo.overlay.session"
                                 :overlay "bafyOverlay"
                                 :node "bafyNode"
                                 :name "local"
                                 :principal {:from "operator"
                                             :to "fleet"
                                             :capability "ssh"}
                                 :direct {:transport "quic"
                                          :kind :quic
                                          :endpoint "quic://unreachable.invalid:4001"}
                                 :relay {:transport "quic"
                                         :kind :relay
                                         :endpoint (str "relay://127.0.0.1:" port "/bafyNode")}}
                                {:endpoint :relay
                                 :frames ["one" "two" "three"]
                                 :timeout-ms 1000})]
        @worker
        (is (true? (:ok? result)))
        (is (= :relay-stream (:mode result)))
        (is (= [0 1 2] (mapv :seq (:relay-frame-acks result))))
        (is (= ["one" "two" "three"] (mapv :payload (:relay-frame-acks result))))
        (is (= [true true true] (mapv :digest-ok? (:relay-frame-acks result))))
        (is (= [true true true] (mapv :mac-ok? (:relay-frame-acks result)))))
      (finally
        (relay/close-listener! listener)))))

(deftest dial-check-streams-mac-authenticated-frames-through-relay
  (let [{:keys [server] :as listener}
        (relay/open-listener! {:type "murakumo.overlay.relay"
                               :overlay "bafyOverlay"
                               :name "local-relay"
                               :region "test"
                               :url "relay://127.0.0.1:0"
                               :bind-host "127.0.0.1"
                               :port 0
                               :auth-key "shared-secret"
                               :transports ["quic"]})
        port (get-in listener [:listen :bound-port])
        worker (future
                 (with-open [socket (.accept server)]
                   (relay/handle-connection!
                    {:overlay "bafyOverlay"
                     :name "local-relay"
                     :region "test"
                     :auth-key "shared-secret"}
                    socket)))]
    (try
      (let [result (dial/check! {:type "murakumo.overlay.session"
                                 :overlay "bafyOverlay"
                                 :node "bafyNode"
                                 :name "local"
                                 :auth-key "shared-secret"
                                 :principal {:from "operator"
                                             :to "fleet"
                                             :capability "ssh"}
                                 :direct {:transport "quic"
                                          :kind :quic
                                          :endpoint "quic://unreachable.invalid:4001"}
                                 :relay {:transport "quic"
                                         :kind :relay
                                         :endpoint (str "relay://127.0.0.1:" port "/bafyNode")}}
                                {:endpoint :relay
                                 :frames ["one" "two"]
                                 :timeout-ms 1000})]
        @worker
        (is (true? (:ok? result)))
        (is (= [true true] (mapv :mac-ok? (:relay-frame-acks result))))
        (is (= [true true] (mapv :open-ok? (:relay-frame-acks result))))
        (is (= [true true] (mapv :sealed? (:relay-frame-acks result))))
        (is (every? some? (mapv :mac (:relay-frame-acks result)))))
      (finally
        (relay/close-listener! listener)))))

(deftest relay-frame-ack-rejects-bad-digest
  (let [ack (relay/relay-frame-ack
             {:overlay "bafyOverlay"
              :name "local-relay"
              :region "test"}
             {:type "murakumo.overlay.relay-frame"
              :overlay "bafyOverlay"
              :node "bafyNode"
              :name "local"
              :target "/bafyNode"
              :transport "quic"
              :seq 0
              :digest "bad"
              :payload "tampered"})]
    (is (false? (:accepted? ack)))
    (is (false? (:digest-ok? ack)))
    (is (not= "bad" (:expected-digest ack)))))

(deftest relay-frame-ack-rejects-bad-mac
  (let [digest (dial/frame-digest {:overlay "bafyOverlay"
                                   :node "bafyNode"
                                   :name "local"}
                                  {:path "/bafyNode"
                                   :transport "quic"}
                                  0
                                  "payload")
        ack (relay/relay-frame-ack
             {:overlay "bafyOverlay"
              :name "local-relay"
              :region "test"
              :auth-key "shared-secret"}
             {:type "murakumo.overlay.relay-frame"
              :overlay "bafyOverlay"
              :node "bafyNode"
              :name "local"
              :target "/bafyNode"
              :transport "quic"
              :seq 0
              :digest digest
              :mac "bad"
              :payload "payload"})]
    (is (true? (:digest-ok? ack)))
    (is (false? (:mac-ok? ack)))
    (is (false? (:accepted? ack)))))

(deftest relay-frame-ack-rejects-unopenable-sealed-frame
  (let [ack (relay/relay-frame-ack
             {:overlay "bafyOverlay"
              :name "local-relay"
              :region "test"
              :auth-key "shared-secret"}
             {:type "murakumo.overlay.relay-frame"
              :overlay "bafyOverlay"
              :node "bafyNode"
              :name "local"
              :target "/bafyNode"
              :transport "quic"
              :seq 0
              :digest "bad"
              :mac "bad"
              :sealed? true
              :alg :aes-256-gcm
              :nonce "bad"
              :ciphertext "bad"})]
    (is (false? (:open-ok? ack)))
    (is (false? (:accepted? ack)))))
