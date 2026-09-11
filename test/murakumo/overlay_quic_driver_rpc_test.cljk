;; murakumo.overlay-quic-driver-rpc-test — generalized request/response RPC
;; (ADR-2607110300 Phase 2) added alongside the fixed hello/ack handshake.
;; Exercises the pure protocol logic (envelope shape, handler dispatch,
;; error handling) without a real QuicStream -- same scope discipline as
;; the rest of this repo's overlay tests, which don't open real QUIC
;; sockets either (quic-driver.clj's connect!/dial!/serve! themselves have
;; no existing test coverage; real network verification is a
;; real-production-gate step, not a unit test).

(ns murakumo.overlay-quic-driver-rpc-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.overlay.quic-driver :as quic-driver]))

(def sample-request
  {:type "murakumo.overlay.adapter-request"
   :version 1
   :transport :quic
   :session {:overlay "bafyOverlay" :node "bafyNode" :name "local"}
   :connect {:host "127.0.0.1" :port 4001 :path "/witness"}})

(deftest request-envelope-carries-arbitrary-payload
  (let [envelope (quic-driver/request-envelope sample-request {:hello "world" :n 1})]
    (is (= "murakumo.overlay.rpc-request" (:type envelope)))
    (is (= :quic (:transport envelope)))
    (is (= "bafyOverlay" (:overlay envelope)))
    (is (= "bafyNode" (:node envelope)))
    (is (= "local" (:name envelope)))
    (is (= "/witness" (:target envelope)))
    (is (= {:hello "world" :n 1} (:payload envelope)))))

(deftest handle-request-envelope-applies-handler-and-serializes-response
  (testing "happy path: handler result becomes the response and its pr-str the response-line"
    (let [req-line (pr-str (quic-driver/request-envelope sample-request {:op :ping}))
          {:keys [request response response-line]}
          (quic-driver/handle-request-envelope req-line (fn [payload] {:pong (:op payload)}))]
      (is (= {:op :ping} (:payload request)))
      (is (= {:pong :ping} response))
      (is (= (pr-str {:pong :ping}) response-line))))

  (testing "handler throwing produces an :error response instead of propagating"
    (let [req-line (pr-str (quic-driver/request-envelope sample-request {:op :boom}))
          {:keys [response]}
          (quic-driver/handle-request-envelope req-line (fn [_] (throw (ex-info "kaboom" {}))))]
      (is (= "kaboom" (:error response)))))

  (testing "nil request line yields a nil envelope; handler is still invoked, with a nil payload"
    (let [{:keys [request response]}
          (quic-driver/handle-request-envelope nil (fn [payload] {:got payload}))]
      (is (nil? request))
      (is (= {:got nil} response)))))
