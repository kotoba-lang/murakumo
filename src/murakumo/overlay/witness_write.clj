;; murakumo.overlay.witness-write — the composition ADR-2607110300 keeps
;; describing but nothing in this repo actually assembles: a single
;; entry point that commits a record via the REAL overlay QUIC transport,
;; gated on a pre-commit witness quorum drawn from murakumo's real
;; fleet.edn, with reputation/stake automatically updated afterward.
;;
;; Every piece already existed and was independently tested before this
;; file: `witness-transport/select-reputable-witnesses` previews which
;; cells reputation would pick, `witness-transport/create-overlay-witness-
;; transport` dials real fleet nodes over QUIC, and
;; `orchestrator/write-with-witnesses-precommit-and-slash` runs the
;; propose/collect/commit/slash pipeline -- but nothing called all three
;; together. This is that call.
;;
;; Note this does NOT reuse `select-reputable-witnesses` as-is: that
;; function's final step (`selector/select-witnesses`) needs the record's
;; CID up front, which doesn't exist until `propose-fn` runs inside the
;; orchestrator. This function instead passes the orchestrator the
;; reputation-*eligible pool* (fleet-edn->witness-fleet +
;; reputation/eligible-fleet, the same two steps `select-reputable-
;; witnesses` composes, minus its own final selection) and lets
;; `write-with-witnesses-precommit`'s internal `selector/select-witnesses`
;; call do the CID-keyed pick, same as every other orchestrator caller.
;;
;; Honesty note (ADR-2607110300, same as witness-transport.clj): every
;; witness reachable through this transport is still operated by the same
;; organization (fleet.edn). This is crash-fault tolerance across
;; physical machines with reputation/stake bookkeeping, not Byzantine
;; consensus across independent third-party operators.

(ns murakumo.overlay.witness-write
  (:require [kotoba.lang.witness-quorum.orchestrator :as orchestrator]
            [kotoba.lang.witness-quorum.reputation :as reputation]
            [kotoba.lang.witness-quorum.stake :as stake]
            [murakumo.overlay.witness-transport :as witness-transport]))

(defn write-record-with-real-quorum!
  "Commit one record through a real pre-commit witness quorum over the
  overlay QUIC transport, then update reputation/stake from the outcome.

  `opts`:
    :fleet-edn          murakumo's fleet.edn map (`:nodes` + `:fleet/p2p-port`).
    :propose-fn/:commit-fn/:write-opts/:rule/:quorum-options
                        same contract as
                        orchestrator/write-with-witnesses-precommit.
    :session            overlay session map identifying the calling node,
                        threaded into every QUIC request envelope.
    :reputation-db       current reputation db. Default reputation/empty-reputation.
    :stake-ledger        current stake ledger. Default stake/empty-ledger.
    :min-score/:min-observations
                        reputation eligibility gate, passed to
                        reputation/eligible-fleet. Defaults 0.5 / 3.
    :slash-amount        passed to orchestrator/write-with-witnesses-precommit-and-slash.
                        Default 10.
    :timeout-ms          per-request QUIC timeout AND total quorum-collection
                        budget. Default murakumo.overlay.quic-driver/default-timeout-ms.

  Returns write-with-witnesses-precommit-and-slash's result map
  (:uri :cid :selected-witnesses :state :committed? :commit-result?
  :reputation-db' :stake-ledger' :slashed)."
  [{:keys [fleet-edn session reputation-db stake-ledger min-score min-observations
           slash-amount timeout-ms propose-fn commit-fn write-opts rule quorum-options]
    :or {reputation-db reputation/empty-reputation
         stake-ledger stake/empty-ledger
         min-score 0.5
         min-observations 3
         slash-amount 10}}]
  (let [eligible (reputation/eligible-fleet
                  (witness-transport/fleet-edn->witness-fleet fleet-edn)
                  reputation-db min-score min-observations)
        transport (witness-transport/create-overlay-witness-transport
                   {:node-lookup (witness-transport/fleet-edn->node-lookup fleet-edn)
                    :session session
                    :timeout-ms timeout-ms})]
    (orchestrator/write-with-witnesses-precommit-and-slash
     {:propose-fn propose-fn
      :commit-fn commit-fn
      :write-opts write-opts
      :fleet eligible
      :rule rule
      :transport transport
      :quorum-options quorum-options
      :timeout-ms timeout-ms
      :reputation-db reputation-db
      :stake-ledger stake-ledger
      :slash-amount slash-amount})))
