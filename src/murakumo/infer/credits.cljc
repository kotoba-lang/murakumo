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


(def unit-prices
  "Media-first pricing keys (Civitai-Buzz-style per-job units) alongside
   per-token text. A model's registry entry carries whichever apply."
  {:tokens :credit/per-token
   :images :credit/per-image
   :video-seconds :credit/per-video-second
   :audio-seconds :credit/per-audio-second
   :training-steps :credit/per-training-step})

(defn job-cost
  "Σ units×price for a media/text job. `units` e.g. {:images 4} or
   {:tokens 300} or {:video-seconds 5}. Unknown unit keys are an error —
   silence would mean free inference."
  [model units]
  (reduce (fn [acc [u n]]
            (let [price-key (or (unit-prices u)
                                (throw (ex-info "unknown billing unit" {:unit u})))]
              (+ acc (* (double (get model price-key
                                      (if (= u :tokens) default-per-token 0)))
                        (double n)))))
          0.0 units))


(defn settle
  "One run → its credit distribution (pure).
   run: {:model {…prices…} (:tokens n | :units {:images 1 …}) :duration-ms ms
         :plan {:assignments [...]}}
   → {:run/total t :run/treasury x :run/head y :run/head-name n
      :run/shares {node credits}}
   Shares are proportional to memory-time; the head's OWN layer share rides
   the same rule, PLUS it earns head-frac off the top for terminating the API
   — credited under :run/head-name, WHATEVER the :head? node's real :name is
   (an mlx-moe plan's sole node keeps its own fleet name, e.g. \"asher\", not
   the literal \"head\" the llama.cpp/mlx-ring head is conventionally aliased
   to — balances/1 must credit the SAME name settle reports here, not assume
   the literal string \"head\").
   Total is Σ units×price (job-cost) — text (:tokens) and media (:units) alike."
  [{:keys [model tokens units duration-ms plan] :as _run}]
  (let [head-frac (:credit/head-frac model default-head-frac)
        proto-frac (:credit/protocol-frac model default-protocol-frac)
        units (or units (when tokens {:tokens tokens}))
        total (job-cost model units)
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
     :run/head-name head-name
     :run/shares (if (pos? mt-sum)
                   (into {} (map (fn [[n w]] [n (* pool (/ w mt-sum))]) mt))
                   {head-name pool})}))

(defn spend
  "A demand-side ledger entry: `who` redeems `credits` for inference (or fiat
   payout). Same append-only feed as settlements — balances/1 folds both, so
   the account is one number. Pure; the caller appends it to the signed feed."
  [who credits {:keys [for] :as _meta}]
  {:run/spend {(name who) (double credits)}
   :run/spend-for for})

(defn balance-of [balances who]
  (get balances (name who) 0.0))

(defn charge
  "Demand-side admission: can `who` afford this job of `model` at its registry
   prices, given folded `balances`? → {:allow? bool :cost c :entry spend-entry}.
   Text passes {:tokens n}; media passes {:images n} / {:video-seconds s} /
   {:audio-seconds s} — Civitai's Buzz shape, one ledger."
  [balances who {:keys [model tokens units]}]
  (let [units (or units (when tokens {:tokens tokens}))
        cost (job-cost model units)
        bal (balance-of balances who)]
    (if (>= bal cost)
      {:allow? true :cost cost
       :entry (spend who cost {:for {:model (:model/id model) :units units}})}
      {:allow? false :cost cost :balance bal})))

(defn receipt
  "A verifiable inference receipt — the blockchain-facing artifact. Pure data:
   the settled run + the shard-verification reports (kotodama.inference.shard
   rank reports: owned bytes, contract tensors byte-checked) + the previous
   receipt's hash, ready for the actor's kotoba key (CACAO) to sign and for a
   chain gateway to mint against. No consensus here: tamper-evidence comes
   from the hash chain + signature, disputes replay the feed."
  [{:keys [settled shard-reports prev-hash hash-fn]}]
  (let [body {:receipt/v 1
              :receipt/run (select-keys settled [:run/total :run/shares
                                                 :run/head :run/treasury])
              :receipt/shards (mapv #(select-keys % [:shard/rank :shard/owned-bytes
                                                     :shard/owned-tensors :shard/host
                                                     :shard/ok])
                                    shard-reports)
              :receipt/prev (or prev-hash "genesis")}]
    (assoc body :receipt/hash
           (if hash-fn (hash-fn (pr-str body)) :receipt.hash/host-injected))))

(defn balances
  "Fold a run ledger (seq of settled runs or raw runs) → account balances.
   Accepts either pre-settled maps (with :run/shares) or raw runs (settled
   here), so the CF Worker can fold whatever the feed contains."
  [runs]
  (reduce (fn [acc run]
            (if-let [sp (:run/spend run)]
              (reduce (fn [a [n c]] (update a (name n) (fnil - 0.0) c)) acc sp)
              (let [s (if (:run/shares run) run (settle run))]
                (-> (reduce (fn [a [n c]] (update a n (fnil + 0.0) c)) acc (:run/shares s))
                    (update (:run/head-name s "head") (fnil + 0.0) (:run/head s))
                    (update :treasury (fnil + 0.0) (:run/treasury s))))))
          {} runs))
