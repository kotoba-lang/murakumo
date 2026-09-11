;; murakumo.overlay-quic-driver-live-test — REAL localhost QUIC round-trip
;; (closes part of the ADR-2607110300 Phase 2 honesty gap: "real
;; end-to-end QUIC verification is deferred"). Two real UDP sockets, real
;; self-signed cert material via murakumo.overlay.cert
;; (ensure-quic-material!, not a fake/pre-baked cert), a real kwik QUIC
;; handshake -- not a with-redefs stub like
;; overlay_witness_transport_test.clj uses. This is still
;; single-process/single-machine (localhost), so it proves the WIRE
;; PROTOCOL (request-envelope framing, handler dispatch, response
;; round-trip) actually works end to end -- it does NOT prove multi-machine
;; reachability across the real fleet.edn hosts, which stays deferred: this
;; sandboxed environment has no network path to those Tailscale hosts.

(ns murakumo.overlay-quic-driver-live-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.overlay.quic-driver :as quic-driver]))

;; High, unlikely-to-collide localhost-only port. Each deftest below uses
;; its own port so a slow-to-close prior listener can't interfere.
(defn- test-request [port]
  {:type "murakumo.overlay.adapter-request"
   :version 1
   :transport :quic
   :session {:overlay "test-overlay" :node "witness-a" :name "test"}
   :connect {:host "127.0.0.1" :port port}})

(deftest live-rpc-round-trip-over-real-quic
  (testing "request! dials a real QUIC listener (real cert, real UDP socket,
            real kwik handshake) and gets a real response back"
    (let [port 18443
          req (test-request port)
          server (future (quic-driver/serve-once-rpc!
                          req
                          (fn [payload] {:echo payload :answered-by "witness-a"})
                          5000))]
      (Thread/sleep 400) ;; let the listener bind (incl. self-signed cert generation) before dialing
      (let [{:keys [ok? response]} (quic-driver/request! req {:ping "hello"} 3000)
            server-result @server]
        (is (true? ok?) "client-side round trip succeeded")
        (is (= {:ping "hello"} (:echo response)) "server saw the exact payload the client sent")
        (is (= "witness-a" (:answered-by response)))
        (is (true? (:ok? server-result)) "server-side accept+respond succeeded")))))

(deftest live-rpc-carries-a-witness-attestation-shaped-payload
  (testing "the same round trip, but with a payload shaped like a real
            witness-quorum attestation request (record-cid/record/rule) --
            proves the wire format this session's witness-transport.clj
            actually sends survives a real QUIC hop, not just the pure
            envelope-building logic overlay_witness_transport_test.clj
            already covers with a stub"
    (let [port 18444
          req (test-request port)
          payload {:cell-id "witness"
                   :record-uri "at://did:web:test.example.com/x/1"
                   :record-cid "bafy-live-1"
                   :record {:v 1 :hello "world"}
                   :rule {:v 1 :nsid "test.example.foo"}}
          server (future (quic-driver/serve-once-rpc!
                          req
                          (fn [received] {:received-cid (:record-cid received) :verdict :accept})
                          5000))]
      (Thread/sleep 400)
      (let [{:keys [ok? response]} (quic-driver/request! req payload 3000)]
        (is (true? ok?))
        (is (= "bafy-live-1" (:received-cid response))
            "the exact record-cid round-tripped through a real socket")
        (is (= :accept (:verdict response)))
        @server))))

(deftest live-request-to-nothing-listening-fails-cleanly
  (testing "no server on this port -> request! returns {:ok? false ...}, not an exception"
    (let [req (test-request 18445)
          {:keys [ok? reason]} (quic-driver/request! req {:ping "nobody-home"} 500)]
      (is (false? ok?))
      (is (some? reason)))))
