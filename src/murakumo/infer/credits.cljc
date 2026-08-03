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

  W6 product-shell + T6.4: defaults + memory-time weights + charge-allow? gate
  require the shipped `:infer-credits` KIR on **every** platform. Host pure
  mirrors are gone — cljs/nbb must preload shipped KIR (resources/ via nbb cwd,
  register-kir!, or set-resource-loader!) before requiring this ns
  (ADR-260731-w6-t64-infer-plan-credits-mirror-delete).
  Float settle folds, transfer, balances remain host."
  (:require [murakumo.kotoba.oracle :as oracle]
            ;; capability provenance lives with the enrollment record it
            ;; qualifies (join owns /infer/nodes); credits consumes it.
            ;; One-way -- join does not know about credits.
            [murakumo.infer.join :as join]))

(def ^:private oid :infer-credits)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(defn- o-record
  "T5.2: structural host map → call-record (requires shipped oracle)."
  [export host-map field-specs]
  (oracle/require-ready! oid)
  (oracle/call-record oid export host-map field-specs))

(def ^:private mt-work-schema
  "Guest :credits/mt-work — T5.2 native record for memory-time-weight."
  [:record :credits/mt-work
   [[:est-bytes :i64] [:duration-ms :i64] [:span :i64]]])

(def ^:private charge-schema
  "Guest :credits/charge — T5.2 native record for charge-allow? / balance-after-spend."
  [:record :credits/charge
   [[:balance :i64] [:cost :i64]]])

(def default-per-token
  ;; credits per generated token
  (oracle/i64->host (o 'default-per-token [])))
;; NOT ratio literals (1/10, 1/20): clojure.lang.Ratio is not a valid
;; ClojureScript compile-time constant ("failed compiling constant: 1/10")
;; -- this file's own docstring promises "runs identically in bb, the JVM,
;; the CF Worker (cloud-murakumo /infer/credits) and a kotoba WASM
;; component", so cljs portability is a real requirement here, not
;; optional. (/ num den) from kotoba head-num/head-den and protocol-num/
;; protocol-den (required oracle).
(def default-head-frac
  ;; conductor's cut
  (/ (oracle/i64->host (o 'head-num []))
     (oracle/i64->host (o 'head-den []))))
(def default-protocol-frac
  ;; fleet treasury (upgrade fund)
  (/ (oracle/i64->host (o 'protocol-num []))
     (oracle/i64->host (o 'protocol-den []))))

(defn- memory-time
  "node → shard-bytes × duration-ms, the contribution weight of one run.
   Kotoba memory-time-weight (required; span < 1 → 0).
   T5.2: structural map → call-record."
  [assignments duration-ms]
  (into {}
        (for [{:keys [node est-bytes span]} assignments
              :let [w (oracle/i64->host
                       (o-record 'memory-time-weight
                                 {:work (oracle/record
                                         mt-work-schema
                                         {:est-bytes (or est-bytes 0)
                                          :duration-ms (or duration-ms 0)
                                          :span (or span 0)})}
                                 [[:work :raw]]))]
              :when (pos? w)]
          [(:name node) (double w)])))

(def unit-prices
  "Media-first pricing keys (Civitai-Buzz-style per-job units) alongside
   per-token text. A model's registry entry carries whichever apply."
  {:tokens :credit/per-token
   ;; ADR-2608026500: text の課金単位は :mtokens（100万トークン）。:tokens は
   ;; 整数で表現できず、kotoba oracle の i64 経由で最小値 1 がそのまま価格に
   ;; なる事故（$10,000/Mtok = 実勢の 20,000 倍）を起こした。新規は :mtokens。
   :mtokens-in :credit/per-mtoken-in
   :mtokens-out :credit/per-mtoken-out
   :ktext :credit/per-ktext
   :assets :credit/per-asset
   :images :credit/per-image
   :video-seconds :credit/per-video-second
   :audio-seconds :credit/per-audio-second
   ;; ADR-2608036900: ベンダが分単位・切り上げで課金する経路（fal の
   ;; elevenlabs-music は "rounded up to the closest minute"）を、秒割りに
   ;; 潰さないための単位。秒で値付けすると 60 秒ちょうど以外は必ずずれ、
   ;; 短いジョブほど原価割れする。**課金する側の次元に合わせる。**
   :audio-minutes :credit/per-audio-minute
   :training-steps :credit/per-training-step
   :gb-months :credit/per-gb-month})

(defn job-cost
  "Σ units×price for a media/text job. `units` e.g. {:images 4} or
   {:mtokens-out 1.2} or {:video-seconds 5}. Unknown unit keys are an error --
   silence would mean free inference. That same guard applies to a KNOWN unit
   whose price key is absent from this model's registry entry: EVERY unit,
   :tokens included, must be configured explicitly or error.

   :tokens used to be exempt, on the reasoning that per-token pricing is 'sane
   to default'. That reasoning was wrong in a way that only measurement showed.
   The default came from the kotoba oracle as `(defn default-per-token [] :i64 1)`
   -- 1 credit/token, i.e. $10,000 per million tokens, roughly 20,000x market.
   Nobody chose that number: 1 is simply the smallest positive i64, and it
   became the price of every model onboarded before its pricing was backfilled.
   A default nobody picked is not a default; it is an accident with a fallback's
   syntax. Prices now come from `murakumo.infer.prices` (resources/murakumo/
   prices.edn), and text bills in :mtokens-in/:mtokens-out -- a unit whose
   natural magnitude cannot be produced by an integer floor.

   `default-per-token` is retained for the kotoba-oracle parity suite, which
   asserts cljc/guest agreement on the exported constant. It is no longer a
   price."
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
                              (throw (ex-info "model missing price for billing unit"
                                              {:unit u :price-key price-key
                                               :model (:model/id model)})))]
              (+ acc (* (double price) (double n)))))
          0.0 units))


;; ---------------------------------------------------------------------------
;; Capability policy (ADR-2607319500 D3)
;;
;; memory-time weights come from what a node ADVERTISES. Measured 2026-07-31,
;; 44 of the 45 enrolled nodes advertised a byte-identical 32 GiB, which makes
;; every weight equal and quietly turns "the plan IS the cap table of the run"
;; into a flat split. :measured-only lets a caller settle against probed
;; capability only.
;;
;; DEFAULT IS :permissive, i.e. exactly the historical behaviour. `settle` is
;; a pure fold that stored feeds replay through, so changing its default would
;; retroactively rewrite what past runs mean -- the same reason `balances` was
;; left permissive and `ledger-violations` added beside it rather than folded
;; in (see the transfer/non-negative-balance work).
;; ---------------------------------------------------------------------------

(def capability-policies
  "#{:permissive :measured-only}. :permissive weights every assignment
   (historical behaviour). :measured-only weights only assignments whose node
   declares :capability/source :measured."
  #{:permissive :measured-only})

(defn capability-exclusions
  "Which assignments :measured-only would drop, and why. Pure; returns DATA so
   a caller can report the gap without changing what anyone gets paid.

   → [{:node name :provenance :declared}]"
  [assignments]
  (->> assignments
       (remove #(join/measured-capability? (:node %)))
       (mapv (fn [{:keys [node]}]
               {:node (:name node)
                :provenance (join/capability-provenance node)}))))

(defn settle
  "One run → its credit distribution (pure).
   run: {:model {…prices…} (:tokens n | :units {:images 1 …}) :duration-ms ms
         :plan {:assignments [...]}}
   opts: {:capability-policy :permissive|:measured-only} (default :permissive)
   → {:run/total t :run/treasury x :run/head y :run/head-name n
      :run/shares {node credits} :run/unallocated u}

   `:run/unallocated` closes the accounting identity in every case:
     total = treasury + head + Σshares + unallocated
   It is 0.0 whenever anything was distributable. It is the whole pool when
   NO assignment carries measured capability under :measured-only -- that pool
   is :uncomputable, not zero and not the head's. Handing it to the head (what
   the mt-sum=0 fallback does) would pay the one node whose share is least
   justified by measurement, so :measured-only declines to allocate instead.
   Shares are proportional to memory-time; the head's OWN layer share rides
   the same rule, PLUS it earns head-frac off the top for terminating the API
   — credited under :run/head-name, WHATEVER the :head? node's real :name is
   (an mlx-moe plan's sole node keeps its own fleet name, e.g. \"asher\", not
   the literal \"head\" the llama.cpp/mlx-ring head is conventionally aliased
   to — balances/1 must credit the SAME name settle reports here, not assume
   the literal string \"head\").
   Total is Σ units×price (job-cost) — text (:tokens) and media (:units) alike."
  ([run] (settle run nil))
  ([{:keys [model tokens units duration-ms plan] :as _run}
    {:keys [capability-policy] :or {capability-policy :permissive}}]
   (let [head-frac (:credit/head-frac model default-head-frac)
         proto-frac (:credit/protocol-frac model default-protocol-frac)
         units (or units (when tokens {:tokens tokens}))
         total (job-cost model units)
         treasury (* total (double proto-frac))
         head-cut (* total (double head-frac))
         pool (- total treasury head-cut)
         assignments (:assignments plan)
         measured-only? (= :measured-only capability-policy)
         weighted (if measured-only?
                    (filter #(join/measured-capability? (:node %)) assignments)
                    assignments)
         mt (memory-time weighted (or duration-ms 1))
         mt-sum (reduce + (vals mt))
         head-name (or (some #(when (get-in % [:node :head?]) (get-in % [:node :name]))
                             assignments)
                       "head")
         ;; mt-sum = 0 has two different meanings and they must not be merged.
         ;; :permissive -> nobody carried weight (zero span/bytes); the head
         ;; conducted the run, so it keeps the pool. Historical behaviour.
         ;; :measured-only -> nobody's capability was ever measured, so the
         ;; weights are UNKNOWN, not zero. Nothing here justifies giving the
         ;; pool to the head, so it goes unallocated and says so.
         distributable? (pos? mt-sum)]
     (cond-> {:run/total total
              :run/treasury treasury
              :run/head head-cut
              :run/head-name head-name
              :run/shares (cond
                            distributable?
                            (into {} (map (fn [[n w]] [n (* pool (/ w mt-sum))]) mt))
                            measured-only? {}
                            :else {head-name pool})
              :run/unallocated (if (or distributable? (not measured-only?)) 0.0 pool)}
       measured-only?
       (assoc :run/capability-policy :measured-only
              :run/capability-excluded (capability-exclusions assignments))))))

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

(defn refund
  "`spend` の反転。失敗したジョブに払われた credits を口座へ戻す。

  ── なぜ transfer ではなく専用の事象なのか ──

  『treasury から payer へ transfer する』でも数字は合うが、意味が違う。返金は
  **第三者からの支払いではなく、起きなかった消費の取り消し**で、原資も要らない
  （fal は自社エラーを課金しないので、murakumo は最初から払っていない）。
  transfer で表すと、原資を持つ口座が必要になり、その口座が枯れた日に
  『返金できない』が起きる —— 会計の形が運用の可用性に化ける。

  ── mint ではないことを、どう保証するか ──

  この関数は純粋な構成子なので、額の正しさは検査できない。**返金額が元の
  spend を超えないことと、同じジョブが二度返金されないことは
  `refundable` が台帳を畳んで判定する。** ここで額を自由に取れる以上、
  呼び出し側が `refundable` を通さずに使えば、これは credits の mint 経路に
  なる —— `transfer` の docstring が『ledger-violations を通さない呼び出しは
  窃盗経路』と書いているのと同じ構図。"
  [who credits {:keys [for reason] :as _meta}]
  (when-not (pos? credits)
    (throw (ex-info "refund amount must be positive" {:who (name who) :credits credits})))
  {:run/refund {(name who) (double credits)}
   :run/refund-for for
   :run/refund-reason reason})

(defn refundable
  "台帳 + ジョブ id → `{:ok? true :who a :credits n}` か `{:ok? false :error kw ...}`。

  **返金が mint にならない唯一の場所。** 3 つを台帳から確かめる:

  1. そのジョブの spend が実在するか（`:run/spend-for` の `:job`）
  2. 既に返金されていないか（`:run/refund-for` の `:job`）—— 冪等性はここ。
     呼び出し側（Worker）は再試行するし、状態ポーリングは何度でも来るので、
     『2 回目は拒否』を台帳側に置かないと、ポーリングの回数だけ credits が湧く
  3. 額は **台帳が持っている spend そのもの**。呼び出し側に額を渡させない ——
     渡させた瞬間、過大返金が API の引数になる"
  [feed job-id]
  (let [job-id (str job-id)
        spend-of (fn [r] (or (:run/spend r) (get r "run/spend")))
        for-of (fn [r k] (let [m (or (get r k) (get r (subs (str k) 1)))]
                           (str (or (:job m) (get m "job")))))
        spends (filter #(and (spend-of %) (= job-id (for-of % :run/spend-for))) feed)
        refunds (filter #(and (or (:run/refund %) (get % "run/refund"))
                              (= job-id (for-of % :run/refund-for)))
                        feed)]
    (cond
      (empty? spends)
      {:ok? false :error :no-such-spend :job job-id}

      (seq refunds)
      {:ok? false :error :already-refunded :job job-id
       :refunded (reduce + 0.0 (mapcat vals (map #(or (:run/refund %) (get % "run/refund")) refunds)))}

      :else
      (let [entries (mapcat seq (map spend-of spends))
            who (name (ffirst entries))
            total (reduce + 0.0 (map second entries))]
        (if (pos? total)
          {:ok? true :who who :credits total}
          {:ok? false :error :nothing-to-refund :job job-id})))))

(defn refund?
  "この台帳事象は返金か。"
  [event]
  (boolean (:run/refund event)))

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
        ;; integer gate for whole balances/costs when possible; float compare otherwise
        allow? (if (and (== (double (long bal)) (double bal))
                        (== (double (long cost)) (double cost)))
                 (oracle/bool->host
                  (o-record 'charge-allow?
                            {:charge (oracle/record
                                      charge-schema
                                      {:balance (long bal)
                                       :cost (long cost)})}
                            [[:charge :raw]]))
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

    ;; 返金は spend の符号違い。同じ形にしてあるのは、fold が 2 つの別々の
    ;; 解釈を持たないようにするため（`ledger-violations` はこの step を
    ;; そのまま再生する）。
    (:run/refund run)
    (reduce (fn [a [n c]] (update a (name n) (fnil + 0.0) c)) acc (:run/refund run))

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
