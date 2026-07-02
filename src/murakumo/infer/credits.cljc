;; murakumo.infer.credits — the inference economy as pure ledger math (cljc).
;;
;; What is scarce on this fleet is MEMORY×TIME: a node that holds 11 GB of a
;; model resident for an hour cannot host anything else with that memory. So a
;; run's credits flow to nodes ∝ (resident shard bytes × run duration) — the
;; same quantity the planner partitions by, which makes the economy and the
;; placement one system: the plan IS the cap table of the run.
;;
;; Design stance (ADR-2607022000):
;; - settle/1 and balances/1 are PURE folds — they run identically in bb, the
;;   JVM, the CF Worker (cloud-murakumo /infer/credits) and a kotoba WASM
;;   component reading the same Datom log.
;; - The ledger is kotoba-style: an append-only, per-actor-signed event feed
;;   (cloud-murakumo /infer/runs, kqe-assert! datoms on the mesh). That is
;;   tamper-EVIDENT, not a consensus blockchain — no global ordering war, one
;;   graph per key-derived IPNS name, CACAO-signed writes (kotoba.cacao).
;;   Cross-actor disputes settle by replaying both signed feeds.
;; - Prices are per-model in the registry (:credit/per-token). The head (API
;;   terminator) earns :credit/head-frac for conducting + its own layers; a
;;   :credit/protocol-frac accrues to the fleet treasury (upgrade fund — the
;;   "investment" loop: treasury buys RAM/Thunderbolt, which raises the
;;   fleet's servable model class, which raises demand for credits).

(ns murakumo.infer.credits)

(def default-per-token 1)          ; credits per generated token
(def default-head-frac 1/10)       ; conductor's cut
(def default-protocol-frac 1/20)   ; fleet treasury (upgrade fund)

(defn- memory-time
  "node → shard-bytes × duration-ms, the contribution weight of one run."
  [assignments duration-ms]
  (into {}
        (for [{:keys [node est-bytes span]} assignments
              :when (pos? (or span 0))]
          [(:name node) (* (double est-bytes) duration-ms)])))

(defn settle
  "One run → its credit distribution (pure).
   run: {:model {:credit/per-token n} :tokens n :duration-ms ms
         :plan {:assignments [...]}}
   → {:run/total t :run/treasury x :run/head y :run/shares {node credits}}
   Shares are proportional to memory-time; the head's OWN layer share rides
   the same rule, PLUS it earns head-frac off the top for terminating the API."
  [{:keys [model tokens duration-ms plan] :as _run}]
  (let [per-token (:credit/per-token model default-per-token)
        head-frac (:credit/head-frac model default-head-frac)
        proto-frac (:credit/protocol-frac model default-protocol-frac)
        total (* (double per-token) (or tokens 0))
        treasury (* total (double proto-frac))
        head-cut (* total (double head-frac))
        pool (- total treasury head-cut)
        mt (memory-time (:assignments plan) (or duration-ms 1))
        mt-sum (reduce + (vals mt))
        head-name (or (some #(when (get-in % [:node :head?]) (get-in % [:node :name]))
                            (:assignments plan))
                      "head")]
    {:run/total total
     :run/treasury treasury
     :run/head head-cut
     :run/shares (if (pos? mt-sum)
                   (into {} (map (fn [[n w]] [n (* pool (/ w mt-sum))]) mt))
                   {head-name pool})}))

(defn balances
  "Fold a run ledger (seq of settled runs or raw runs) → account balances.
   Accepts either pre-settled maps (with :run/shares) or raw runs (settled
   here), so the CF Worker can fold whatever the feed contains."
  [runs]
  (reduce (fn [acc run]
            (let [s (if (:run/shares run) run (settle run))]
              (-> (reduce (fn [a [n c]] (update a n (fnil + 0.0) c)) acc (:run/shares s))
                  (update "head" (fnil + 0.0) (:run/head s))
                  (update :treasury (fnil + 0.0) (:run/treasury s)))))
          {} runs))
