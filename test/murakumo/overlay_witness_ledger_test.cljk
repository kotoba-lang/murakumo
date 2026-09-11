;; murakumo.overlay-witness-ledger-test — ledger-quorum-fn, the
;; :quorum-fn adapter cloud-murakumo.ledger.witness/witness-run injects
;; (ADR-2607995000 §7, third gate bullet). Same live-vs-stubbed split as
;; overlay_witness_write_test.clj: the witnessed path runs over a REAL
;; localhost QUIC round trip (two real witness listeners); the contract
;; tests stub :write-fn so the propose/commit wiring is checked without
;; sockets.

(ns murakumo.overlay-witness-ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.overlay.quic-driver :as quic-driver]
            [murakumo.overlay.witness-ledger :as witness-ledger]
            [murakumo.overlay.witness-transport :as witness-transport]
            [kotoba.lang.witness-quorum.selector :as selector]))

(defn- test-rule [& {:as overrides}]
  (merge
   {:v 1
    :nsid "test.example.ledger"
    :schema-ref {:content-hash (apply str (repeat 64 "a")) :version "1.0.0"}
    :policy-ref {:content-hash (apply str (repeat 64 "b")) :version "1.0.0"}
    :cell-ref {:content-hash (apply str (repeat 64 "c")) :version "abcdef0"}}
   overrides))

(def closed-run
  ;; the shape cloud-murakumo.ledger/close-run produces and
  ;; ledger.witness/run-cid projects — witness-ledger treats it opaquely.
  {:murakumo.run/id "run-1" :gpu/class :h100 :gpu/count 1 :node/id "asher"
   :started-at 100 :murakumo.run/gpu-seconds 60 :cost 0.5
   :gen.job/artifacts ["bafy1.image"]})

(deftest quorum-fn-contract-and-wiring
  (testing "propose gets the ledger's own cid verbatim, commit is a no-op
            marker, and the quorum STATE (not the whole result) is returned —
            exactly what ledger.witness/witness-run stores"
    (let [seen (atom nil)
          outcome (atom nil)
          qf (witness-ledger/ledger-quorum-fn
              {:fleet-edn {:nodes []}
               :session {:overlay "test" :node "caller" :name "caller"}
               :rule (test-rule)
               :write-fn (fn [opts]
                           (reset! seen {:proposed ((:propose-fn opts) (:write-opts opts))
                                         :commit ((:commit-fn opts) (:write-opts opts) nil)
                                         :record (:record (:write-opts opts))})
                           {:state {:kind :witnessed :verdict :clean}
                            :reputation-db' {:w1 {:total 1}}})
               :on-outcome! (fn [r] (reset! outcome r))})
          state (qf {:record-cid "cid-from-ledger" :record closed-run})]
      (is (= {:kind :witnessed :verdict :clean} state))
      (is (= "cid-from-ledger" (get-in @seen [:proposed :cid]))
          "witnesses sign against the ledger's own record-cid, not a re-derived one")
      (is (= :ledger/commit-deferred-to-caller (:commit @seen))
          "a :witnessed verdict must not double-write — appending stays the ledger caller's job")
      (is (= closed-run (:record @seen)))
      (is (= {:w1 {:total 1}} (:reputation-db' @outcome))
          "on-outcome! sees the FULL result so reputation/stake can be persisted")))
  (testing "a rejected quorum comes back as-is (fail-closed downstream:
            ledger.witness marks witnessed? false and witnessed-billing drops it)"
    (let [qf (witness-ledger/ledger-quorum-fn
              {:fleet-edn {:nodes []}
               :write-fn (fn [_] {:state {:kind :rejected}})})]
      (is (= :rejected (:kind (qf {:record-cid "c" :record closed-run})))))))

(deftest ledger-quorum-fn-reaches-witnessed-over-real-quic
  (testing "the composed quorum-fn drives a real 2-witness localhost QUIC
            quorum to :witnessed for a closed economic run"
    (let [fleet-edn {:fleet/name "ledger-live-test" :fleet/p2p-port 18460
                     :nodes [{:name "w1" :host "127.0.0.1" :p2p-port 18460}
                             {:name "w2" :host "127.0.0.1" :p2p-port 18461}]}
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
          outcome (atom nil)
          qf (witness-ledger/ledger-quorum-fn
              {:fleet-edn fleet-edn
               :session {:overlay "test" :node "caller" :name "caller"}
               :rule (test-rule :quorum-size 2 :quorum-threshold 2 :escalation-policy :council)
               :timeout-ms 4000
               :on-outcome! (fn [r] (reset! outcome r))})
          state (qf {:record-cid (pr-str (select-keys closed-run [:murakumo.run/id :cost]))
                     :record closed-run})]
      (doseq [s servers] @s)
      (is (= :witnessed (:kind state)))
      (is (true? (:committed? @outcome)))
      (is (= :ledger/commit-deferred-to-caller (:commit-result @outcome))
          "commit-fn ran but only as the no-op marker — nothing persisted here"))))
