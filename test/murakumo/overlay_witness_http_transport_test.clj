;; murakumo.overlay-witness-http-transport-test — the "deployment glue"
;; witness_rpc.clj's and verify/compute.cljc's docstrings deferred, now
;; built and tested. A real local HTTP server (via JDK
;; com.sun.net.httpserver.HttpServer, no cloud-murakumo dependency --
;; it speaks the same wire contract by construction, see the ns
;; docstring) stands in for a cloud-murakumo witness-rpc endpoint, and a
;; real Ed25519 keypair (kotoba.lang.witness-quorum.signer, not the
;; deterministic test signer) proves the produced attestation's
;; signature actually verifies.

(ns murakumo.overlay-witness-http-transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [kotoba.lang.witness-quorum.attestation :as attestation]
            [kotoba.lang.witness-quorum.signer :as signer]
            [murakumo.overlay.witness-http-transport :as glue])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

;; ── a minimal stand-in cloud-murakumo witness-rpc endpoint ────────────
;; Deliberately reimplements just enough of witness_rpc.clj's server side
;; to prove glue.clj's CLIENT correctly speaks that wire contract,
;; without murakumo taking a dependency on cloud-murakumo.

(defn- fake-witness-server! [port handler]
  (let [server (HttpServer/create (InetSocketAddress. (int port)) 0)]
    (.createContext server "/witness"
      (reify HttpHandler
        (handle [_ exchange]
          (let [^HttpExchange ex exchange
                body (slurp (.getRequestBody ex))
                envelope (edn/read-string body)
                response (handler (:payload envelope))
                resp-bytes (.getBytes (pr-str {:type "cloud-murakumo.witness-rpc-response"
                                               :version 1 :payload response}) "UTF-8")]
            (.sendResponseHeaders ex 200 (alength resp-bytes))
            (with-open [os (.getResponseBody ex)]
              (.write os resp-bytes))))))
    (.setExecutor server nil)
    (.start server)
    server))

(defn- honest-recompute-handler [payload]
  ;; matches what cloud_murakumo.verify.compute/recompute-verdict would
  ;; return for a claim that matches the "recomputed" value
  (if (= (:claimed-cids payload) ["bafy-x" "bafy-y"])
    {:layer :deterministic :verdict :accept}
    {:layer :deterministic :verdict :reject :reason "recompute mismatch"}))

(deftest http-dial-round-trips-the-wire-contract
  (let [server (fake-witness-server! 28643 (fn [payload] {:echo payload}))]
    (try
      (let [{:keys [ok? response]} (glue/http-dial! "http://127.0.0.1:28643/witness" {:x 1} 2000)]
        (is (true? ok?))
        (is (= {:echo {:x 1}} response)))
      (finally (.stop server 0)))))

(deftest deterministic-validator-fails-closed-when-endpoint-unreachable
  (testing "no server listening -> :reject, not an exception, not a silent :accept"
    (let [validator (glue/http-deterministic-validator "http://127.0.0.1:28644/witness" 500)
          verdict (validator {:inv {} :claimed-cids ["x"]} {})]
      (is (= :reject (:verdict verdict))))))

(deftest produce-http-witnessed-attestation-end-to-end-with-real-signature
  (testing "real HTTP dial to a real local server + real Ed25519 signing, both directions verified"
    (let [server (fake-witness-server! 28645 honest-recompute-handler)
          seed (byte-array (range 32)) ;; deterministic test seed, real ed25519 math
          pubkey (signer/ed25519-public-key-bytes seed)
          cell {:node "cloud-murakumo-worker-1" :cell-id "witness" :key "cloud-murakumo-worker-1::witness"}]
      (try
        (let [att (glue/produce-http-witnessed-attestation
                   {:record-uri "at://did:web:test/x/1"
                    :record-cid "bafy-live-glue-1"
                    :record {:inv {:job/id "j1"} :claimed-cids ["bafy-x" "bafy-y"]}
                    :cell cell
                    :rule {:v 1 :nsid "test.example.compute"
                           :schema-ref {:content-hash (apply str (repeat 64 "a"))}
                           :policy-ref {:content-hash (apply str (repeat 64 "b"))}
                           :cell-ref {:content-hash (apply str (repeat 64 "c"))}}
                    :signer (signer/make-ed25519-cell-signer seed)
                    :witness-url "http://127.0.0.1:28645/witness"})]
          (is (= :accept (:verdict att)) "the real HTTP-dialed recompute check said :accept")
          (is (= "cloud-murakumo-worker-1" (:cell-node att)))
          (let [canonical (attestation/canonical-attestation-bytes
                            {:record-cid (:record-cid att) :cell-id (:cell-id att)
                             :verdict (:verdict att) :reason (or (:reason att) "")
                             :membrane-version (:membrane-version att) :attested-at (:attested-at att)})]
            (is (true? (signer/verify-ed25519-signature canonical (:signature att) pubkey))
                "the attestation's signature is a REAL, independently-verifiable Ed25519 signature")))
        (finally (.stop server 0))))))

(deftest produce-http-witnessed-attestation-rejects-on-mismatched-recompute
  (testing "the slashing signal survives the full glue composition, still validly signed"
    (let [server (fake-witness-server! 28646 honest-recompute-handler)
          seed (byte-array (repeat 32 7))
          pubkey (signer/ed25519-public-key-bytes seed)
          cell {:node "cloud-murakumo-worker-2" :cell-id "witness" :key "cloud-murakumo-worker-2::witness"}]
      (try
        (let [att (glue/produce-http-witnessed-attestation
                   {:record-uri "at://did:web:test/x/2"
                    :record-cid "bafy-live-glue-2"
                    :record {:inv {:job/id "j2"} :claimed-cids ["bafy-x" "bafy-FRAUD"]}
                    :cell cell
                    :rule {:v 1 :nsid "test.example.compute"
                           :schema-ref {:content-hash (apply str (repeat 64 "a"))}
                           :policy-ref {:content-hash (apply str (repeat 64 "b"))}
                           :cell-ref {:content-hash (apply str (repeat 64 "c"))}}
                    :signer (signer/make-ed25519-cell-signer seed)
                    :witness-url "http://127.0.0.1:28646/witness"})]
          (is (= :reject (:verdict att)))
          (let [canonical (attestation/canonical-attestation-bytes
                            {:record-cid (:record-cid att) :cell-id (:cell-id att)
                             :verdict (:verdict att) :reason (or (:reason att) "")
                             :membrane-version (:membrane-version att) :attested-at (:attested-at att)})]
            (is (true? (signer/verify-ed25519-signature canonical (:signature att) pubkey))
                "a :reject verdict is signed just as validly as an :accept -- the slashing evidence is authentic")))
        (finally (.stop server 0))))))
