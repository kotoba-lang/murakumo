;; murakumo.overlay-witness-write-test — write-record-with-real-quorum!
;; had zero coverage before this file (the new composition itself). Same
;; live-vs-stubbed split as overlay_quic_driver_live_test.clj /
;; overlay_witness_transport_test.clj: the full witnessed path runs over
;; a REAL localhost QUIC round trip (two real witness listeners); the
;; rejected/slashing path stubs quic-driver/request! (same isolation the
;; rest of this repo's overlay tests use) so a deterministic 2-reject/
;; 1-accept split is reachable without racing real validator wiring
;; across sockets.

(ns murakumo.overlay-witness-write-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.witness-quorum.reputation :as reputation]
            [kotoba.lang.witness-quorum.selector :as selector]
            [murakumo.overlay.quic-driver :as quic-driver]
            [murakumo.overlay.witness-transport :as witness-transport]
            [murakumo.overlay.witness-write :as witness-write]))

(defn- test-rule [& {:as overrides}]
  (merge
   {:v 1
    :nsid "test.example.foo"
    :schema-ref {:content-hash (apply str (repeat 64 "a")) :version "1.0.0"}
    :policy-ref {:content-hash (apply str (repeat 64 "b")) :version "1.0.0"}
    :cell-ref {:content-hash (apply str (repeat 64 "c")) :version "abcdef0"}}
   overrides))

(defn- mock-propose+commit []
  (let [committed (atom nil)]
    {:propose-fn (fn [write-opts] {:uri "at://did:web:test.example.com/x/1"
                                    :cid (str "bafy-" (:rkey write-opts))})
     :commit-fn (fn [write-opts receipt] (reset! committed {:write-opts write-opts :receipt receipt}) :committed)
     :committed committed}))

(deftest write-record-with-real-quorum-reaches-witnessed-over-real-quic
  (testing "two real localhost QUIC witness listeners both accept -> committed,
            and reputation'/stake' record both cells as agreeing (no slashing,
            no minority)"
    (let [fleet-edn {:fleet/name "live-test" :fleet/p2p-port 18450
                      :nodes [{:name "w1" :host "127.0.0.1" :p2p-port 18450}
                              {:name "w2" :host "127.0.0.1" :p2p-port 18451}]}
          servers (doall
                   (for [{:keys [name p2p-port]} (:nodes fleet-edn)]
                     (let [cell (selector/fleet-cell name "witness")
                           req {:type "murakumo.overlay.adapter-request" :version 1 :transport :quic
                                :session {:overlay "test" :node name :name name}
                                :connect {:host "127.0.0.1" :port p2p-port}}
                           handler (witness-transport/witness-request-handler
                                    {:cell cell :signer (fn [_bytes] (byte-array 64))})]
                       (future (quic-driver/serve-once-rpc! req handler 5000)))))
          _ (Thread/sleep 400)
          {:keys [propose-fn commit-fn committed]} (mock-propose+commit)
          result (witness-write/write-record-with-real-quorum!
                  {:fleet-edn fleet-edn
                   :session {:overlay "test" :node "caller" :name "caller"}
                   :propose-fn propose-fn
                   :commit-fn commit-fn
                   :write-opts {:rkey "1" :record {:v 1 :hello "world"}}
                   :rule (test-rule :quorum-size 2 :quorum-threshold 2 :escalation-policy :council)
                   :timeout-ms 4000})]
      (doseq [s servers] @s)
      (is (= :witnessed (:kind (:state result))))
      (is (true? (:committed? result)))
      (is (= :committed (:commit-result result)))
      (is (some? @committed) "commit-fn actually ran")
      (is (= 2 (count (:selected-witnesses result))))
      (is (empty? (:slashed result)) "unanimous accept has no minority to slash")
      (is (every? #(= 1 (:total (get (:reputation-db' result) %)))
                  (map :key (:selected-witnesses result)))
          "both real witnesses got a reputation observation recorded"))))

(deftest write-record-with-real-quorum-slashes-the-minority-on-a-split-verdict
  (testing "stubbed transport: 2 reject + 1 accept, quorum-threshold 2 ->
            :rejected, commit-fn never runs, and the lone dissenting cell
            (the accepter) is the one slashed -- proves this new entry
            point actually threads apply-quorum-outcome's minority/stake
            wiring end to end, not just that the orchestrator alone does"
    (let [fleet-edn {:fleet/name "stub-test" :fleet/p2p-port 9000
                      :nodes [{:name "reject-a" :host "127.0.0.1" :p2p-port 19001}
                              {:name "reject-b" :host "127.0.0.1" :p2p-port 19002}
                              {:name "accept-c" :host "127.0.0.1" :p2p-port 19003}]}
          ;; `request!` is stubbed, so quic-driver never actually dials --
          ;; the only signal identifying WHICH witness a given call targets
          ;; is `:connect` (this transport doesn't put the node name on the
          ;; wire itself, see this ns's port->identity map below), same
          ;; signal a real dial would use to route the UDP packet.
          port->witness {19001 {:verdict :reject :node "reject-a"}
                         19002 {:verdict :reject :node "reject-b"}
                         19003 {:verdict :accept :node "accept-c"}}
          {:keys [propose-fn commit-fn committed]} (mock-propose+commit)]
      (with-redefs [quic-driver/request!
                    (fn [request payload _timeout-ms]
                      (let [{:keys [verdict node]} (get port->witness (get-in request [:connect :port]))]
                        {:ok? true :request request
                         :response {:quorum-group (selector/quorum-group (:record-cid payload))
                                    :verdict verdict
                                    :cell-id (:cell-id payload) :cell-node node}}))]
        (let [result (witness-write/write-record-with-real-quorum!
                      {:fleet-edn fleet-edn
                       :stake-ledger {"accept-c::witness" 50}
                       :session {:overlay "test" :node "caller" :name "caller"}
                       :propose-fn propose-fn
                       :commit-fn commit-fn
                       :write-opts {:rkey "2" :record {:v 1}}
                       :rule (test-rule :quorum-size 3 :quorum-threshold 2 :escalation-policy :council)
                       :timeout-ms 3000})]
          (is (= :rejected (:kind (:state result))))
          (is (false? (:committed? result)))
          (is (nil? @committed) "commit-fn must never run on a rejected quorum")
          (is (= 1 (count (:slashed result))))
          (is (= "accept-c::witness" (:cell-key (first (:slashed result))))
              "the sole dissenter from the :rejected majority is the one slashed")
          (is (= 40 (get-in (:stake-ledger' result) ["accept-c::witness"]))
              "the slashed cell's pre-posted 50-unit bond dropped by the default 10-unit slash-amount"))))))

(deftest write-record-with-real-quorum-excludes-a-below-threshold-witness-from-eligibility
  (testing "reputation gating is actually applied before selection, not just
            available for a caller to apply separately"
    (let [fleet-edn {:fleet/name "gated-test" :fleet/p2p-port 9100
                      :nodes [{:name "good" :host "127.0.0.1"}
                              {:name "bad" :host "127.0.0.1"}]}
          bad-reputation (reduce (fn [db _] (reputation/record-outcome db "bad::witness" false))
                                  reputation/empty-reputation (range 5))
          {:keys [propose-fn commit-fn]} (mock-propose+commit)]
      (with-redefs [quic-driver/request!
                    (fn [request payload _timeout-ms]
                      {:ok? true :request request
                       :response {:quorum-group (selector/quorum-group (:record-cid payload))
                                  :verdict :accept :cell-id (:cell-id payload)
                                  :cell-node (get-in request [:session :node])}})]
        (let [result (witness-write/write-record-with-real-quorum!
                      {:fleet-edn fleet-edn
                       :reputation-db bad-reputation
                       :session {:overlay "test" :node "caller" :name "caller"}
                       :propose-fn propose-fn
                       :commit-fn commit-fn
                       :write-opts {:rkey "3" :record {:v 1}}
                       :rule (test-rule :quorum-size 1 :quorum-threshold 1 :escalation-policy :council)
                       :timeout-ms 2000})]
          (is (= #{"good::witness"} (set (map :key (:selected-witnesses result))))
              "the below-threshold 'bad' cell must never enter selection at all"))))))
