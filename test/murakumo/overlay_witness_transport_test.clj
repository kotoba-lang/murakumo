;; murakumo.overlay-witness-transport-test — witness-quorum wired onto the
;; overlay QUIC RPC (ADR-2607110300 Phase 2). Fleet-mapping/handler logic is
;; tested directly; the network hop itself is tested by stubbing
;; `quic-driver/request!` with `with-redefs` (same class of isolation the
;; rest of this repo's overlay tests use to avoid real sockets), so the
;; queueing/polling wiring in `create-overlay-witness-transport` is
;; exercised without opening a real QUIC connection.

(ns murakumo.overlay-witness-transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.witness-quorum.orchestrator :as orchestrator]
            [kotoba.lang.witness-quorum.selector :as selector]
            [murakumo.overlay.quic-driver :as quic-driver]
            [murakumo.overlay.witness-transport :as witness-transport]))

(def sample-fleet-edn
  {:fleet/name "test-fleet"
   :fleet/p2p-port 4001
   :nodes [{:name "naphtali" :host "naphtali" :rpc-ip "192.168.1.25"}
           {:name "simeon" :host "simeon" :rpc-ip "192.168.1.24" :p2p-port 4003}]})

(deftest fleet-edn->witness-fleet-one-cell-per-node
  (let [fleet (witness-transport/fleet-edn->witness-fleet sample-fleet-edn)]
    (is (= 2 (count fleet)))
    (is (= #{"naphtali::witness" "simeon::witness"} (set (map :key fleet))))))

(deftest node-connect-target-prefers-rpc-ip-and-per-node-port
  (is (= {:host "192.168.1.25" :port 4001}
         (witness-transport/node-connect-target
          {:name "naphtali" :host "naphtali" :rpc-ip "192.168.1.25"} 4001)))
  (is (= {:host "192.168.1.24" :port 4003}
         (witness-transport/node-connect-target
          {:name "simeon" :host "simeon" :rpc-ip "192.168.1.24" :p2p-port 4003} 4001))))

(deftest fleet-edn->node-lookup-resolves-by-name-or-nil
  (let [lookup (witness-transport/fleet-edn->node-lookup sample-fleet-edn)]
    (is (= {:host "192.168.1.25" :port 4001} (lookup "naphtali")))
    (is (= {:host "192.168.1.24" :port 4003} (lookup "simeon")))
    (is (nil? (lookup "nonexistent")))))

(deftest witness-request-handler-produces-a-signed-attestation
  (let [cell (selector/fleet-cell "naphtali" "witness")
        handler (witness-transport/witness-request-handler
                 {:cell cell
                  :signer (orchestrator/make-deterministic-test-signer (:cell-id cell))})
        response (handler {:cell-id "witness"
                            :record-uri "at://did:web:test.example.com/x/1"
                            :record-cid "bafy-1"
                            :record {:v 1 :hello "world"}
                            :rule {:v 1 :nsid "test.example.foo"
                                   :schema-ref {:content-hash (apply str (repeat 64 "a"))}
                                   :policy-ref {:content-hash (apply str (repeat 64 "b"))}
                                   :cell-ref {:content-hash (apply str (repeat 64 "c"))}}})]
    (is (= :accept (:verdict response)))
    (is (= "naphtali" (:cell-node response)))
    (is (= "witness" (:cell-id response)))
    (is (some? (:signature response)))))

(deftest create-overlay-witness-transport-round-trips-through-the-network-hop
  (testing "request-attestation dials via quic-driver/request!; the response lands on the matching quorum-group queue"
    (let [captured-request (atom nil)
          fake-attestation {:quorum-group "abc123" :verdict :accept :cell-id "witness" :cell-node "naphtali"}
          transport (witness-transport/create-overlay-witness-transport
                     {:node-lookup (fn [node] (when (= node "naphtali") {:host "192.168.1.25" :port 4001}))
                      :session {:overlay "ov" :node "local" :name "local"}
                      :timeout-ms 1000})]
      (with-redefs [quic-driver/request!
                    (fn [request payload _timeout-ms]
                      (reset! captured-request {:request request :payload payload})
                      {:ok? true :request request :response fake-attestation})]
        ((:request-attestation transport)
         {:cell (selector/fleet-cell "naphtali" "witness")
          :record-uri "at://x/1" :record-cid "bafy-1" :record {:v 1} :rule {}})
        (let [poll-fn ((:subscribe-attestations transport) "abc123")
              {:keys [status value]} (poll-fn 2000)]
          (is (= :value status))
          (is (= fake-attestation value))
          (is (= {:host "192.168.1.25" :port 4001} (get-in @captured-request [:request :connect])))
          (is (= "witness" (:cell-id (:payload @captured-request))))))))

  (testing "unknown node -> node-lookup returns nil -> no request is made, nothing is queued (times out)"
    (let [transport (witness-transport/create-overlay-witness-transport
                     {:node-lookup (fn [_] nil)
                      :session {:overlay "ov" :node "local" :name "local"}})]
      ((:request-attestation transport)
       {:cell (selector/fleet-cell "ghost" "witness")
        :record-uri "at://x/2" :record-cid "bafy-2" :record {:v 1} :rule {}})
      (let [poll-fn ((:subscribe-attestations transport) (selector/quorum-group "bafy-2"))]
        (is (= :timeout (:status (poll-fn 200))))))))
