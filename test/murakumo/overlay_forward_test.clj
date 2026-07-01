;; murakumo.overlay-forward-test — local forwarder checks.

(ns murakumo.overlay-forward-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.forward :as forward]
            [murakumo.overlay.relay :as relay])
  (:import [java.net Socket]))

(deftest listen-spec-parses-host-and-port
  (is (= {:bind-host "127.0.0.1" :port 1234}
         (forward/parse-listen "1234")))
  (is (= {:bind-host "0.0.0.0" :port 1234}
         (forward/parse-listen "0.0.0.0:1234"))))

(deftest local-forwarder-sends-client-lines-through-relay
  (let [relay-listener (relay/open-listener! {:type "murakumo.overlay.relay"
                                              :overlay "bafyOverlay"
                                              :name "local-relay"
                                              :region "test"
                                              :url "relay://127.0.0.1:0"
                                              :bind-host "127.0.0.1"
                                              :port 0
                                              :auth-key "shared-secret"
                                              :transports ["quic"]})
        relay-port (get-in relay-listener [:listen :bound-port])
        relay-worker (future
                       (with-open [socket (.accept (:server relay-listener))]
                         (relay/handle-connection!
                          {:overlay "bafyOverlay"
                           :name "local-relay"
                           :region "test"
                           :auth-key "shared-secret"}
                          socket)))
        forward-listener (forward/open-listener! "127.0.0.1:0")
        forward-port (get-in forward-listener [:listen :bound-port])
        session {:type "murakumo.overlay.session"
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
                         :endpoint (str "relay://127.0.0.1:" relay-port "/bafyNode")}}
        forward-worker (future
                         (with-open [socket (.accept (:server forward-listener))]
                           (forward/handle-client! session socket)))]
    (try
      (with-open [client (Socket. "127.0.0.1" forward-port)]
        (let [writer (java.io.OutputStreamWriter. (.getOutputStream client) "UTF-8")
              reader (java.io.BufferedReader.
                      (java.io.InputStreamReader. (.getInputStream client) "UTF-8"))]
          (.write writer "one\n")
          (.write writer "two\n")
          (.flush writer)
          (.shutdownOutput client)
          (is (= "one" (.readLine reader)))
          (is (= "two" (.readLine reader)))))
      (let [report @forward-worker]
        @relay-worker
        (is (true? (:ok? report)))
        (is (= 2 (:frames report)))
        (is (= 2 (:acks report)))
        (is (= [true true] (mapv :sealed? (get-in report [:report :relay-frame-acks])))))
      (finally
        (forward/close-listener! forward-listener)
        (relay/close-listener! relay-listener)))))

(deftest local-forwarder-sends-client-bytes-through-relay
  (let [relay-listener (relay/open-listener! {:type "murakumo.overlay.relay"
                                              :overlay "bafyOverlay"
                                              :name "local-relay"
                                              :region "test"
                                              :url "relay://127.0.0.1:0"
                                              :bind-host "127.0.0.1"
                                              :port 0
                                              :auth-key "shared-secret"
                                              :transports ["quic"]})
        relay-port (get-in relay-listener [:listen :bound-port])
        relay-worker (future
                       (with-open [socket (.accept (:server relay-listener))]
                         (relay/handle-connection!
                          {:overlay "bafyOverlay"
                           :name "local-relay"
                           :region "test"
                           :auth-key "shared-secret"}
                          socket)))
        forward-listener (forward/open-listener! "127.0.0.1:0")
        forward-port (get-in forward-listener [:listen :bound-port])
        session {:type "murakumo.overlay.session"
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
                         :endpoint (str "relay://127.0.0.1:" relay-port "/bafyNode")}}
        forward-worker (future
                         (with-open [socket (.accept (:server forward-listener))]
                           (forward/handle-client-bytes! session socket)))]
    (try
      (with-open [client (Socket. "127.0.0.1" forward-port)]
        (let [out (.getOutputStream client)
              in (.getInputStream client)
              payload (.getBytes "abc\ndef\u0000ghi" "UTF-8")
              received (byte-array (alength ^bytes payload))]
          (.write out payload)
          (.flush out)
          (.shutdownOutput client)
          (is (= (alength ^bytes payload) (.read in received)))
          (is (= (seq payload) (seq received)))))
      (let [report @forward-worker]
        @relay-worker
        (is (true? (:ok? report)))
        (is (= 1 (:chunks report)))
        (is (= 11 (:bytes-in report)))
        (is (= 11 (:bytes-out report)))
        (is (= [true] (mapv :sealed? (get-in report [:report :relay-frame-acks])))))
      (finally
        (forward/close-listener! forward-listener)
        (relay/close-listener! relay-listener)))))
