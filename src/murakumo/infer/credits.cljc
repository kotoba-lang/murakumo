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

(ns murakumo.infer.credits
  "Inference economy pure ledger math.

  W6 product-shell (ADR-260728-w6-identity-credits-oracle-authority):
  defaults + memory-time weights + charge-allow? gate DELEGATE to
  infer_credits_core.kir.edn when oracle loadable (JVM or cljs/nbb).
  Float settle folds, transfer, balances remain host. cljs mirrors as fallback."
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-credits)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "JVM: require shipped KIR (T6.4). cljs: oracle when ready, else mirror."
  [thunk mirror-thunk]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid})))
       (thunk))
     :cljs
     (if (oracle-ready?)
       (try
         (thunk)
         (catch :default _
           (mirror-thunk)))
       (mirror-thunk))))

(defn- oracle-i64-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/i64->host (oracle/call oid export [])))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/i64->host (oracle/call oid export []))
         mirror)
       (catch :default _
         mirror))))

(defn- oracle-ratio-const
  "Load-time ratio from two i64 exports. JVM require; cljs mirror."
  [num-export den-export mirror]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export [num-export den-export]})))
       (/ (oracle/i64->host (oracle/call oid num-export []))
          (oracle/i64->host (oracle/call oid den-export []))))
     :cljs
     (try
       (if (oracle-ready?)
         (/ (oracle/i64->host (oracle/call oid num-export []))
            (oracle/i64->host (oracle/call oid den-export [])))
         mirror)
       (catch :default _
         mirror))))

(def default-per-token
  ;; credits per generated token
  (oracle-i64-const 'default-per-token 1))
;; NOT ratio literals (1/10, 1/20): clojure.lang.Ratio is not a valid
;; ClojureScript compile-time constant ("failed compiling constant: 1/10")
;; -- this file's own docstring promises "runs identically in bb, the JVM,
;; the CF Worker (cloud-murakumo /infer/credits) and a kotoba WASM
;; component", so cljs portability is a real requirement here, not
;; optional. (/ 1 10) evaluates to the identical Clojure ratio value at
;; runtime on bb/JVM (arithmetic, not a literal, so it's fine there too) --
;; only the *literal syntax* is the problem.
;; Numer/denom from kotoba head-num/head-den and protocol-num/protocol-den.
(def default-head-frac
  ;; conductor's cut
  (oracle-ratio-const 'head-num 'head-den (/ 1 10)))
(def default-protocol-frac
  ;; fleet treasury (upgrade fund)
  (oracle-ratio-const 'protocol-num 'protocol-den (/ 1 20)))

(defn- memory-time
  "node → shard-bytes × duration-ms, the contribution weight of one run.
   Kotoba memory-time-weight when ready (span < 1 → 0)."
  [assignments duration-ms]
  (into {}
        (for [{:keys [node est-bytes span]} assignments
              :let [w (try-oracle
                       #(oracle/i64->host
                         (o 'memory-time-weight
                            [(oracle/as-i64 (or est-bytes 0))
                             (oracle/as-i64 (or duration-ms 0))
                             (oracle/as-i64 (or span 0))]))
                       #(if (pos? (or span 0))
                          (* (double (or est-bytes 0)) duration-ms)
                          0))]
              :when (pos? w)]
          [(:name node) (double w)])))


(def unit-prices
  "Media-first pricing keys (Civitai-Buzz-style per-job units) alongside
   per-token text. A model's registry entry carries whichever apply."
  {:tokens :credit/per-token
   :images :credit/per-image
   :video-seconds :credit/per-video-second
   :audio-seconds :credit/per-audio-second
   :training-steps :credit/per-training-step
   :gb-months :credit/per-gb-month})

(defn job-cost
  "Σ units×price for a media/text job. `units` e.g. {:images 4} or
   {:tokens 300} or {:video-seconds 5}. Unknown unit keys are an error --
   silence would mean free inference. That same 'silence = free inference'
   guard also applies to a KNOWN unit whose price key is simply absent from
   this particular model's registry entry: :tokens alone has a documented
   global default (default-per-token) since per-token pricing genuinely is
   sane to default; every other unit (:images/:video-seconds/:audio-seconds/
   :training-steps) has no sane universal default (prices vary wildly by
   model) and must be configured explicitly or error, never silently 0."
  [model units]
  (reduce (fn [acc [u n]]
            (let [price-key (or (unit-prices u)
                                (throw (ex-info "unknown billing unit" {:unit u})))
                  ;; `(get model price-key default)` eagerly evaluates
                  ;; `default` even when price-key IS present -- so a bare
                  ;; `(get model price-key (if ... (throw ...)))` would throw
                  ;; unconditionally regardless of whether the key exists.
                  ;; `contains?` short-circuits that.
                  price     (if (contains? model price-key)
                              (get model price-key)
                              (if (= u :tokens)
                                default-per-token
                                (throw (ex-info "model missing price for billing unit"
                                                {:unit u :price-key price-key
                                                 :model (:model/id model)}))))]
              (+ acc (* (double price) (double n)))))
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
  "A demand-side ledger entry: `who` redeems `credits` for inference. Same
   append-only feed as settlements — balances/1 folds both, so the account
   is one number. Pure; the caller appends it to the signed feed.

   Credits are a NON-redeemable prepaid usage claim, not a deposit or a
   claim on fiat/USDC (ADR-2607995000, the credits<->fiat membrane is
   one-way: fiat/USDC mints credits, never the reverse). There is
   deliberately no fiat-payout path anywhere in this codebase — do not add
   one without revisiting that ADR first."
  [who credits {:keys [for] :as _meta}]
  {:run/spend {(name who) (double credits)}
   :run/spend-for for})

(defn balance-of [balances who]
  (get balances (name who) 0.0))

;; ── credits transfer between holders (ADR-2607995000 amend, adr-ledger seq 73)
;;
;; Until 2026-07-25 the only thing anyone could do with credits was burn them on
;; this fleet's inference, which made the ACCEPTANCE DENSITY of the unit exactly
;; 1 -- the operator's own fleet was the sole acceptor. A non-redeemable unit's
;; value is bounded by the number of distinct things it buys, so that 1 was the
;; binding constraint on the whole credits sphere, and ADR-2607995000's own
;; Consequences section already conceded it ("witness 報酬が credits 建てである
;; 限り外部第三者の参加誘因は弱い").
;;
;; The membrane table in that ADR is EXHAUSTIVE ("ここに無い流れは禁止"), and
;; holder-to-holder transfer was not in it. adr-ledger seq 73 adds exactly one
;; row -- credits -> third-party seller, credits-denominated -- and changes
;; nothing else. Specifically still forbidden, and NOT touched here:
;; credits->fiat/USDC (both directions of redemption), credits<->EN, EN<->USDC.
;;
;; Transferability is not redeemability. A transferred credit still cannot leave
;; the economy, so §1's structural non-speculation proof (neither internal unit
;; is redeemable, therefore neither can be an investment) survives intact.
;;
;; Two invariants the amend requires, both enforced here:
;;   1. CONSERVATION -- a transfer moves value, it never creates or destroys it.
;;      Issuance stays labor-only (settled runs and settled witness duty).
;;   2. NON-NEGATIVE BALANCES -- credits are a PREPAID usage claim, not a credit
;;      line. This is exactly where credits must NOT behave like EN: EN is
;;      net-zero mutual credit with a declared negative credit-limit, and if
;;      credits acquired an overdraft they would quietly become a second mutual
;;      credit system, collapsing the credits<->EN separation §1 draws.

(defn transfer
  "A holder-to-holder ledger entry: `from` pays `credits` to `to`, both inside
   the credits sphere. Pure; the caller appends it to the signed feed, exactly
   like `spend` and `settle`.

   Conserving by construction -- one map, one amount, applied as a debit and a
   credit by `balances`. There is deliberately no fee: the 5% protocol cut is
   taken at MINT (fiat/USDC -> credits) and taking a second one here would give
   the economy two fee numbers for one economy.

   This does NOT check the sender's balance, because a pure event constructor
   has no ledger to check against -- `ledger-violations` does that over the
   folded feed. Callers admitting a transfer must run it; see its docstring for
   why silence here would be a real theft path rather than a rounding issue."
  [from to credits {:keys [for] :as _meta}]
  (when-not (pos? credits)
    (throw (ex-info "transfer amount must be positive"
                    {:from (name from) :to (name to) :credits credits})))
  (when (= (name from) (name to))
    (throw (ex-info "self-transfer is not a transfer" {:who (name from)})))
  {:run/transfer {:from (name from) :to (name to) :credits (double credits)}
   :run/transfer-for for})

(defn transfer?
  "Is this ledger event a holder-to-holder transfer?"
  [event]
  (boolean (:run/transfer event)))

(defn accepting-sellers
  "The distinct accounts that have ever RECEIVED a credits transfer, i.e. the
   sellers who actually accept credits as payment.

   This is the acceptance-density measurement, answerable from the ledger
   instead of by assertion. It was 1 by construction before transfers existed
   (only the fleet could be paid), and the system-dynamics pass named that 1 as
   the binding constraint on the credits sphere -- so it is worth being able to
   count without a second system."
  [runs]
  (into #{} (comp (filter transfer?) (map (comp :to :run/transfer))) runs))

(defn- availability-proof-ok?
  "Every verdict in `verdicts` carries :kotobase.availability/verdict :ok --
   the exact keyword kotobase-peer's audit-outcome standardizes on. A nil or
   empty seq is NOT proof (an :sla job asserting nothing was checked must
   fail closed, same stance job-cost takes on silently-missing prices)."
  [verdicts]
  (and (seq verdicts)
       (every? #(= :ok (:kotobase.availability/verdict %)) verdicts)))

(defn charge
  "Demand-side admission: can `who` afford this job of `model` at its registry
   prices, given folded `balances`? → {:allow? bool :cost c :entry spend-entry}
   on success; on refusal either {:allow? false :cost c :balance b} (can't
   afford it) or {:allow? false :cost c :reason :availability-proof-failed}
   (SLA proof gate below). Text passes {:tokens n}; media passes {:images n} /
   {:video-seconds s} / {:audio-seconds s} — Civitai's Buzz shape, one ledger.
   Storage passes {:gb-months g}.

   `:tier` is one of :volatile/:standard/:sla, mirroring kotobase-peer's
   redundancy-tiers as plain data (this ns does not import kotobase-peer).
   It's optional; when absent, or :volatile/:standard, behavior is IDENTICAL
   to before this key existed -- no availability check at all, admission is
   a pure balance check. Only :sla additionally gates the charge on proof:
   the job must carry `:availability-verdicts`, a seq of
   {:kotobase.availability/node n :kotobase.availability/cid c
    :kotobase.availability/epoch e :kotobase.availability/verdict v}, and
   EVERY entry's :kotobase.availability/verdict must be :ok. A missing,
   empty, or partially-failed verdict seq is a hard gate -- charge denies
   with :reason :availability-proof-failed even when the balance would
   otherwise cover the job (the balance is never consulted in that case);
   an unprovable SLA-tier job is refused before affordability is asked."
  [balances who {:keys [model tokens units tier availability-verdicts]}]
  (let [units (or units (when tokens {:tokens tokens}))
        cost (job-cost model units)
        bal (balance-of balances who)
        ;; integer gate for whole balances/costs when oracle ready; float compare fallback
        allow? (if (and (== (double (long bal)) (double bal))
                        (== (double (long cost)) (double cost)))
                 (try-oracle
                  #(oracle/bool->host
                    (o 'charge-allow?
                       [(oracle/as-i64 (long bal))
                        (oracle/as-i64 (long cost))]))
                  #(>= bal cost))
                 (>= bal cost))]
    (cond
      (and (= tier :sla) (not (availability-proof-ok? availability-verdicts)))
      {:allow? false :cost cost :reason :availability-proof-failed}

      allow?
      {:allow? true :cost cost
       :entry (spend who cost {:for {:model (:model/id model) :units units}})}

      :else
      {:allow? false :cost cost :balance bal})))

(defn receipt
  "A verifiable inference receipt — the blockchain-facing artifact. Pure data:
   the settled run + the shard-verification reports (kotodama.inference.shard
   rank reports: owned bytes, contract tensors byte-checked) + the previous
   receipt's hash, SIGNED by the acting node's kotoba key. No consensus here:
   tamper-evidence comes from the hash chain + the actor signature, disputes
   replay the feed.

   :sign-fn and :signer are REQUIRED (ADR-2607995000 §7: receipts carry an
   actor signature in addition to the hash chain — an unsigned receipt is not
   a receipt). :signer (the actor's did) sits INSIDE the hashed body, so the
   hash chain covers who claims the run; :receipt/sig is sign-fn over the
   pr-str of the hashed body, so the signature covers hash + chain position
   too. Fail-closed, same stance as ledger.witness/witness-run's :quorum-fn:
   a missing signer never silently degrades to the old unsigned v1 shape."
  [{:keys [settled shard-reports prev-hash hash-fn sign-fn signer]}]
  (when-not (and sign-fn signer)
    (throw (ex-info "receipt: :sign-fn and :signer are required — receipts must be actor-signed in addition to hash-chained (ADR-2607995000 §7)"
                    {:signer signer :sign-fn? (some? sign-fn)})))
  (let [body {:receipt/v 2
              :receipt/run (select-keys settled [:run/total :run/shares
                                                 :run/head :run/treasury])
              :receipt/shards (mapv #(select-keys % [:shard/rank :shard/owned-bytes
                                                     :shard/owned-tensors :shard/host
                                                     :shard/ok])
                                    shard-reports)
              :receipt/signer (name signer)
              :receipt/prev (or prev-hash "genesis")}
        hashed (assoc body :receipt/hash
                      (if hash-fn (hash-fn (pr-str body)) :receipt.hash/host-injected))]
    (assoc hashed :receipt/sig (sign-fn (pr-str hashed)))))

(defn balances-step
  "Apply ONE ledger event to a balances map. Extracted from `balances` so that
   `ledger-violations` replays with byte-identical semantics rather than a
   second implementation that could drift from it."
  [acc run]
  (cond
    ;; holder-to-holder transfer: debit and credit in the same step, so the fold
    ;; cannot lose or invent value even on a truncated feed
    (:run/transfer run)
    (let [{:keys [from to credits]} (:run/transfer run)]
      (-> acc
          (update from (fnil - 0.0) credits)
          (update to (fnil + 0.0) credits)))

    (:run/spend run)
    (reduce (fn [a [n c]] (update a (name n) (fnil - 0.0) c)) acc (:run/spend run))

    :else
    ;; A PRE-SETTLED run carries whatever the writer put in it, and a feed
    ;; written before head/treasury were split out (or by any producer that
    ;; only records shares) has neither. `settle` always emits all three, so
    ;; this only bites on the `(:run/shares run)` branch -- which is exactly
    ;; the branch a stored feed takes. Unguarded it NPEs on `(+ 0.0 nil)` and
    ;; takes the whole balances endpoint down with it.
    ;;
    ;; `cond->` and NOT `(or ... 0)`: defaulting to 0 would ADD a "head" 0.0
    ;; row to every balances map that has no head, changing the shape of
    ;; /infer/credits for every existing feed. local-murakumo's copy of this
    ;; fold used cond-> for exactly that reason and its routes-test pins it.
    ;; The divergence between the two copies is how the NPE stayed invisible;
    ;; converging on the copy that was already right is the fix.
    (let [s (if (:run/shares run) run (settle run))]
      (cond-> (reduce (fn [a [n c]] (update a n (fnil + 0.0) c)) acc (:run/shares s))
        (:run/head s) (update (:run/head-name s "head") (fnil + 0.0) (:run/head s))
        (:run/treasury s) (update :treasury (fnil + 0.0) (:run/treasury s))))))

(defn balances
  "Fold a run ledger (seq of settled runs or raw runs) → account balances.
   Accepts either pre-settled maps (with :run/shares) or raw runs (settled
   here), so the CF Worker can fold whatever the feed contains.

   Permissive by design: it reports what the feed says, including a negative
   balance. `ledger-violations` is the fn that judges — see its docstring for
   why the check is beside this fold rather than inside it."
  [runs]
  (reduce balances-step {} runs))

(defn ledger-violations
  "Replay `runs` in order and report every point where an account went NEGATIVE,
   as data -- never throwing, the same discipline `engi.core/fold-balance` holds
   for the EN side.

   Why this is a separate fn rather than a check inside `balances`: `balances`
   is the historical fold that existing feeds and the CF Worker already run, and
   changing what it returns would retroactively reinterpret feeds already
   written. So the invariant is added alongside it, not inside it.

   Why it matters more than it did yesterday, stated plainly: `spend` has never
   checked that the spender could afford the spend. While the only payee was the
   operator's own fleet, an overdraft cost the operator and nobody else. Once a
   THIRD-PARTY seller can be paid in credits (adr-ledger seq 73), an unchecked
   overdraft is a path to taking real goods and services from a stranger with
   credits that were never earned. Any admission path for a transfer must run
   this and refuse on a non-empty result.

   Returns [{:index i :account a :balance b :event e}], empty when clean.
   `:treasury` is exempt: it is an accrual bucket that only ever receives."
  [runs]
  (:violations
   (reduce
    (fn [{:keys [bal violations] :as acc} [i run]]
      (let [next-bal (balances-step bal run)
            newly-negative (for [[a b] next-bal
                                 :when (and (not= a :treasury)
                                            (neg? b)
                                            (not (neg? (get bal a 0.0))))]
                             {:index i :account a :balance b :event run})]
        (assoc acc
               :bal next-bal
               :violations (into violations newly-negative))))
    {:bal {} :violations []}
    (map-indexed vector runs))))
