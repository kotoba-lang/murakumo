;; Offline unit tests for the pure inference-credit ledger math.
(ns murakumo.infer-credits-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.credits :as credits]))

(def plan
  {:assignments [{:node {:name "naphtali"} :span 8 :est-bytes 11450000000}
                 {:node {:name "levi"} :span 6 :est-bytes 11000000000}
                 {:node {:name "head" :head? true} :span 10 :est-bytes 17580000000}]})

(def run {:model {:credit/per-token 2} :tokens 100 :duration-ms 60000 :plan plan})

(deftest settle-conserves-credits
  (let [{:run/keys [total treasury head shares]} (credits/settle run)]
    (testing "total = per-token × tokens; every credit lands somewhere"
      (is (= 200.0 total))
      (is (< (Math/abs (- total (+ treasury head (reduce + (vals shares))))) 1e-9)))
    (testing "shares follow memory-time: the biggest resident shard earns most"
      (is (> (shares "head") (shares "naphtali") (shares "levi"))))
    (testing "treasury and head cut off the top"
      (is (= 10.0 treasury))
      (is (= 20.0 head)))))

(deftest balances-fold
  (let [b (credits/balances [run run])]
    (testing "two identical runs → double everything, plus the head's conductor cut"
      (is (= 20.0 (b :treasury)))
      (is (< (Math/abs (- 400.0 (+ (b :treasury) (reduce + (vals (dissoc b :treasury)))))) 1e-9))))
  (testing "pre-settled entries fold identically to raw runs"
    (is (= (credits/balances [run]) (credits/balances [(credits/settle run)])))))

(deftest head-cut-follows-the-real-conducting-node
  (testing "an mlx-moe-shaped plan (single :head? node, NOT literally named
            \"head\") credits its conductor cut under its own real name, not
            a phantom \"head\" account — same node earns 100% of the pool,
            not ~90%"
    (let [moe-plan {:assignments [{:node {:name "asher" :head? true}
                                   :span 48 :est-bytes 18700000000}]}
          moe-run {:model {:credit/per-token 2} :tokens 100 :duration-ms 60000 :plan moe-plan}
          settled (credits/settle moe-run)
          b (credits/balances [moe-run])]
      (is (= "asher" (:run/head-name settled)))
      (is (nil? (b "head")) "no orphan \"head\" account for a plan with no node literally named head")
      (is (= (:run/head settled) (- (b "asher") (get-in settled [:run/shares "asher"])))
          "the head-frac cut lands on the SAME account as the shard share"))))

(deftest redemption
  (let [ledger [run run]                                    ; naphtali earns twice
        bal (credits/balances ledger)
        model {:model/id "gemma-4-26b-a4b" :credit/per-token 2}]
    (testing "an earner can spend up to the balance, and the fold debits it"
      (let [{:keys [allow? entry cost]} (credits/charge bal "naphtali" {:model model :tokens 30})]
        (is allow?)
        (is (= 60.0 cost))
        (let [bal2 (credits/balances (conj ledger entry))]
          (is (< (credits/balance-of bal2 "naphtali")
                 (credits/balance-of bal "naphtali"))))))
    (testing "beyond the balance → denied with the price quoted"
      (let [r (credits/charge bal "levi" {:model model :tokens 100000})]
        (is (not (:allow? r)))
        (is (= 200000.0 (:cost r)))))
    (testing "credits are conserved through a spend (spend burns from the account)"
      (let [{:keys [entry]} (credits/charge bal "naphtali" {:model model :tokens 30})
            before (reduce + (vals (dissoc bal :treasury)))
            after (reduce + (vals (dissoc (credits/balances (conj ledger entry)) :treasury)))]
        (is (= 60.0 (- before after)))))))

(deftest sla-tier-availability-gate
  (let [bal {"artist" 500.0}
        model {:model/id "storage-pin" :credit/per-gb-month 10}
        job {:model model :units {:gb-months 5}}
        ok-verdicts [{:kotobase.availability/node "naphtali" :kotobase.availability/cid "bafy1"
                      :kotobase.availability/epoch 1 :kotobase.availability/verdict :ok}
                     {:kotobase.availability/node "levi" :kotobase.availability/cid "bafy1"
                      :kotobase.availability/epoch 1 :kotobase.availability/verdict :ok}]
        failed-verdicts [{:kotobase.availability/node "naphtali" :kotobase.availability/cid "bafy1"
                          :kotobase.availability/epoch 1 :kotobase.availability/verdict :ok}
                         {:kotobase.availability/node "levi" :kotobase.availability/cid "bafy1"
                          :kotobase.availability/epoch 1 :kotobase.availability/verdict :failed}]]
    (testing "sla tier + all-:ok verdicts + sufficient balance -> allowed"
      (let [{:keys [allow? cost]} (credits/charge bal "artist"
                                                   (assoc job :tier :sla
                                                          :availability-verdicts ok-verdicts))]
        (is allow?)
        (is (= 50.0 cost))))
    (testing "sla tier + a :failed verdict + sufficient balance -> denied, hard gate not a balance check"
      (let [r (credits/charge bal "artist"
                              (assoc job :tier :sla :availability-verdicts failed-verdicts))]
        (is (not (:allow? r)))
        (is (= :availability-proof-failed (:reason r)))
        (is (= 50.0 (:cost r)))))
    (testing "sla tier + missing availability-verdicts entirely -> denied, fails closed"
      (let [r (credits/charge bal "artist" (assoc job :tier :sla))]
        (is (not (:allow? r)))
        (is (= :availability-proof-failed (:reason r)))))
    (testing ":standard tier / no tier, no verdicts at all -> unchanged, gated only by balance"
      (let [{:keys [allow? cost]} (credits/charge bal "artist" (assoc job :tier :standard))]
        (is allow?)
        (is (= 50.0 cost)))
      (let [{:keys [allow? cost]} (credits/charge bal "artist" job)]
        (is allow?)
        (is (= 50.0 cost)))
      (testing "and :standard/no-tier still denies on balance exactly as before, unaffected by the sla gate"
        (let [broke (credits/charge {} "penniless" job)]
          (is (not (:allow? broke)))
          (is (nil? (:reason broke)))
          (is (= 50.0 (:cost broke))))))))

(deftest media-units
  (let [bal {"artist" 500.0}
        sdxl {:model/id "animagine-xl-4.0" :credit/per-image 100}
        video {:model/id "wan-2.2" :credit/per-video-second 40}]
    (testing "per-image pricing, Civitai-Buzz shape"
      (let [{:keys [allow? cost]} (credits/charge bal "artist" {:model sdxl :units {:images 4}})]
        (is allow?) (is (= 400.0 cost))))
    (testing "per-second video pricing"
      (is (= 200.0 (:cost (credits/charge bal "artist" {:model video :units {:video-seconds 5}})))))
    (testing "tokens shorthand still works"
      (is (= 60.0 (:cost (credits/charge bal "artist" {:model {:credit/per-token 2} :tokens 30})))))
    (testing "unknown units are an error, not free"
      (is (thrown? Exception (credits/job-cost sdxl {:pixels 1e6}))))))

(deftest media-unit-missing-price-key-is-an-error-not-free
  (testing "a KNOWN unit whose price key is simply absent from this model's
            registry entry must error, same as an unrecognized unit --
            silently defaulting to 0 would mean free inference for any model
            onboarded before its pricing is backfilled"
    (let [incomplete-model {:model/id "no-media-pricing-yet"}]
      (is (thrown? Exception (credits/job-cost incomplete-model {:images 500})))
      (is (thrown? Exception (credits/job-cost incomplete-model {:video-seconds 300})))
      (is (thrown? Exception (credits/job-cost incomplete-model {:audio-seconds 10})))
      (is (thrown? Exception (credits/job-cost incomplete-model {:training-steps 50})))
      (testing ":tokens is no longer exempt.

                It was, until 2026-08-03, on the reasoning that per-token
                pricing is 'sane to default'. But the default was the kotoba
                oracle's `(defn default-per-token [] :i64 1)` = 1 credit/token
                = $10,000/Mtok ~ 20,000x market, and nobody chose it -- 1 is
                the smallest positive i64. Every model onboarded before its
                price was backfilled silently carried that number. An exemption
                that turns a missing price into a wrong price is worse than the
                error it was avoiding, because the error is visible."
        (is (thrown? Exception (credits/job-cost incomplete-model {:tokens 1}))))
      (testing "charge propagates the error too -- never silently approves at cost 0"
        (is (thrown? Exception (credits/charge {} "broke-user"
                                                {:model incomplete-model :units {:images 500}}))))
      (testing "EVERY unit in unit-prices, enumerated from the table itself.

                The list above is hand-written, and on 2026-08-29 :gb-months --
                the storage unit ADR-2608291009 defines YATA in terms of (1 YATA
                = 1 GB-month) -- was in `unit-prices` and in none of these
                assertions. Nothing was wrong with the guard; the guard was
                simply not being asked about that unit, and a hand-written list
                is exactly the thing that falls behind the table it mirrors.
                Enumerating the table means the next unit someone adds is
                covered on the commit that adds it, or this test fails."
        (doseq [[unit price-key] credits/unit-prices]
          (is (thrown? Exception (credits/job-cost incomplete-model {unit 1}))
              (str "unit " unit " (" price-key ") did not refuse when its price "
                   "was absent -- silence here is free service"))
          (testing "and it bills at the configured price once one exists"
            (is (= 7.0 (credits/job-cost (assoc incomplete-model price-key 7) {unit 1}))
                (str "unit " unit " did not use " price-key))))))))

(deftest receipts
  (let [sign-fn (fn [s] (str "sig" (hash s)))
        settled (credits/settle run)
        r1 (credits/receipt {:settled settled
                             :shard-reports [{:shard/rank {:layers [0 21]} :shard/owned-bytes 1745007264
                                              :shard/owned-tensors 358 :shard/host "main-2" :shard/ok true}]
                             :hash-fn (fn [s] (str "h" (hash s)))
                             :sign-fn sign-fn :signer "did:key:zHead"})
        r2 (credits/receipt {:settled settled :shard-reports [] :prev-hash (:receipt/hash r1)
                             :hash-fn (fn [s] (str "h" (hash s)))
                             :sign-fn sign-fn :signer "did:key:zHead"})]
    (testing "receipts chain and carry the shard evidence"
      (is (= "genesis" (:receipt/prev r1)))
      (is (= (:receipt/hash r1) (:receipt/prev r2)))
      (is (= 358 (get-in r1 [:receipt/shards 0 :shard/owned-tensors]))))
    (testing "identical bodies hash identically (deterministic, signable)"
      (is (= (:receipt/hash r1)
             (:receipt/hash (credits/receipt {:settled settled
                                              :shard-reports [{:shard/rank {:layers [0 21]} :shard/owned-bytes 1745007264
                                                               :shard/owned-tensors 358 :shard/host "main-2" :shard/ok true}]
                                              :hash-fn (fn [s] (str "h" (hash s)))
                                              :sign-fn sign-fn :signer "did:key:zHead"})))))
    (testing "the actor signature is mandatory and covers hash + signer (ADR-2607995000 §7)"
      (is (thrown? Exception
                   (credits/receipt {:settled settled :shard-reports []
                                     :hash-fn (fn [s] (str "h" (hash s)))}))
          "unsigned receipts are rejected, not silently emitted")
      (is (thrown? Exception
                   (credits/receipt {:settled settled :shard-reports []
                                     :hash-fn (fn [s] (str "h" (hash s)))
                                     :sign-fn sign-fn}))
          ":signer (the actor did) is required alongside :sign-fn")
      (is (= "did:key:zHead" (:receipt/signer r1)))
      (is (= (sign-fn (pr-str (dissoc r1 :receipt/sig))) (:receipt/sig r1))
          "sig is over the HASHED body, so it covers the hash chain")
      (is (not= (:receipt/hash r1)
                (:receipt/hash (credits/receipt {:settled settled
                                                 :shard-reports [{:shard/rank {:layers [0 21]} :shard/owned-bytes 1745007264
                                                                  :shard/owned-tensors 358 :shard/host "main-2" :shard/ok true}]
                                                 :hash-fn (fn [s] (str "h" (hash s)))
                                                 :sign-fn sign-fn :signer "did:key:zOther"})))
          "the signer sits INSIDE the hashed body — claiming another actor changes the hash"))))

(deftest degenerate-runs
  (testing "a run with no serving assignments pays the head, not /0"
    ;; The model carries an explicit price because job-cost no longer defaults
    ;; :tokens (see media-unit-missing-price-key-is-an-error-not-free). This
    ;; test is about the /0 guard, not about pricing -- `{}` was only ever
    ;; standing in for "a model", and it happened to exercise the accidental
    ;; 1 credit/token default on the way past.
    (let [s (credits/settle {:model {:credit/per-token 1} :tokens 10
                             :duration-ms 1 :plan {:assignments []}})]
      (is (= 10.0 (:run/total s)))
      (is (pos? (get-in s [:run/shares "head"]))))))

;; ── credits transfer between holders (ADR-2607995000 amend, adr-ledger seq 73)
;;
;; The amend adds exactly one membrane row -- credits -> third-party seller,
;; credits-denominated -- to break the acceptance density of 1 that the
;; system-dynamics pass named as the binding constraint on the credits sphere.
;; These tests pin what the amend explicitly did NOT change alongside what it did.

(deftest transfer-conserves-total
  (testing "a transfer moves value, it never creates or destroys it -- issuance
            stays labor-only"
    (let [feed [{:run/shares {"asher" 100.0} :run/head 0.0 :run/head-name "asher"
                 :run/treasury 0.0}
                (credits/transfer "asher" "acme-corp" 30 {:for "dataset access"})]
          bal (credits/balances feed)]
      (is (= 70.0 (credits/balance-of bal "asher")))
      (is (= 30.0 (credits/balance-of bal "acme-corp")))
      (is (= 100.0 (+ (credits/balance-of bal "asher")
                      (credits/balance-of bal "acme-corp")))
          "the total is exactly what labor issued -- the transfer added nothing")))
  (testing "a chain of transfers still conserves"
    (let [feed [{:run/shares {"asher" 90.0} :run/head 10.0 :run/head-name "asher"
                 :run/treasury 0.0}
                (credits/transfer "asher" "b" 40 {})
                (credits/transfer "b" "c" 25 {})
                (credits/transfer "c" "asher" 5 {})]
          bal (credits/balances feed)]
      (is (= 100.0 (reduce + (vals (dissoc bal :treasury)))))
      (is (= 65.0 (credits/balance-of bal "asher")))
      (is (= 15.0 (credits/balance-of bal "b")))
      (is (= 20.0 (credits/balance-of bal "c"))))))

(deftest transfer-rejects-nonsense-eagerly
  (testing "non-positive amounts are refused at construction, not folded silently"
    (is (thrown? clojure.lang.ExceptionInfo (credits/transfer "a" "b" 0 {})))
    (is (thrown? clojure.lang.ExceptionInfo (credits/transfer "a" "b" -5 {}))))
  (testing "a self-transfer is not a transfer -- it would be a no-op that still
            shows up in the acceptance-density count"
    (is (thrown? clojure.lang.ExceptionInfo (credits/transfer "a" "a" 5 {})))))

(deftest overdraft-is-reported-as-data
  (testing "credits are a PREPAID claim, not a credit line -- this is exactly
            where they must not behave like EN, which has a declared negative
            credit-limit"
    (let [feed [{:run/shares {"asher" 10.0} :run/head 0.0 :run/head-name "asher"
                 :run/treasury 0.0}
                (credits/transfer "asher" "acme-corp" 30 {})]
          v (credits/ledger-violations feed)]
      (is (= 1 (count v)))
      (is (= "asher" (:account (first v))))
      (is (= -20.0 (:balance (first v))))
      (is (= 1 (:index (first v))) "the offending event's position in the feed")
      (testing "and it is reported, never thrown -- same discipline as
                engi.core/fold-balance"
        (is (vector? v)))))
  (testing "a clean feed has no violations"
    (is (empty? (credits/ledger-violations
                 [{:run/shares {"asher" 100.0} :run/head 0.0 :run/head-name "asher"
                   :run/treasury 0.0}
                  (credits/transfer "asher" "acme-corp" 30 {})]))))
  (testing "the pre-existing spend path is covered too -- it never checked
            affordability, which was survivable only while the operator's own
            fleet was the sole payee"
    (let [v (credits/ledger-violations [(credits/spend "nobody" 5 {:for "run-1"})])]
      (is (= 1 (count v)))
      (is (= "nobody" (:account (first v))))))
  (testing ":treasury is exempt -- it is an accrual bucket that only receives"
    (is (empty? (credits/ledger-violations
                 [{:run/shares {"asher" 1.0} :run/head 0.0 :run/head-name "asher"
                   :run/treasury 0.0}])))))

(deftest balances-step-is-the-single-source-of-fold-semantics
  (testing "ledger-violations replays through the same step fn balances uses, so
            the two can never disagree about what an event means"
    (let [feed [{:run/shares {"a" 50.0} :run/head 0.0 :run/head-name "a" :run/treasury 0.0}
                (credits/transfer "a" "b" 20 {})
                (credits/spend "b" 5 {:for "inference"})]
          folded (credits/balances feed)
          stepped (reduce credits/balances-step {} feed)]
      (is (= folded stepped)))))

(deftest acceptance-density-is-a-ledger-query
  (testing "the number the growth analysis called the binding constraint must be
            countable from the feed, not asserted"
    (let [feed [{:run/shares {"asher" 100.0} :run/head 0.0 :run/head-name "asher"
                 :run/treasury 0.0}
                (credits/transfer "asher" "acme-corp" 10 {})
                (credits/transfer "asher" "beta-labs" 10 {})
                (credits/transfer "asher" "acme-corp" 5 {})]]
      (is (= #{"acme-corp" "beta-labs"} (credits/accepting-sellers feed))
          "distinct RECIPIENTS, not transfer count")))
  (testing "a feed with no transfers has zero accepting sellers -- which is the
            honest reading of the state before this amend: the only acceptor was
            the fleet itself, and that is not a transfer"
    (is (empty? (credits/accepting-sellers
                 [{:run/shares {"asher" 1.0} :run/head 0.0 :run/head-name "asher"
                   :run/treasury 0.0}
                  (credits/spend "asher" 1 {:for "inference"})])))))

(deftest amend-does-not-open-a-redemption-path
  (testing "transferability is not redeemability -- there is still no fn anywhere
            in this ns that turns credits into fiat, USDC or EN"
    (let [fns (->> (ns-publics 'murakumo.infer.credits) keys (map name) set)]
      (is (not-any? #(re-find #"(?i)fiat|usdc|payout|redeem|withdraw|cash" %) fns)
          (str "unexpected redemption-shaped fn: " fns)))))

(deftest balances-step-survives-a-presettled-run-without-head-or-treasury
  (testing "a stored feed carries whatever its writer put in it. A run with
            shares but no :run/head / :run/treasury used to NPE the fold on
            (+ 0.0 nil), taking the whole balances endpoint with it.

            It stayed invisible because local-murakumo carried a SECOND copy of
            this fold that guarded the same spot with cond-> -- the divergence
            hid the defect in the canonical one. That copy is gone as of the
            local-murakumo credits-admission wiring, so this is now the only
            implementation and has to hold the shape on its own."
    (is (= {"n1" 10.0} (credits/balances [{:run/shares {"n1" 10}}]))
        "absent head/treasury are OMITTED, not defaulted to 0.0 -- defaulting
         would add a \"head\" row to every existing balances response")
    (testing "head-name is still honoured when present"
      (is (= {"n1" 10.0 "h" 2.0 :treasury 1.0}
             (credits/balances [{:run/shares {"n1" 10} :run/head-name "h"
                                 :run/head 2 :run/treasury 1}]))))
    (testing "and a transfer over such a feed conserves value"
      (let [b (credits/balances [{:run/shares {"n1" 10}}
                                 (credits/transfer "n1" "seller" 4 {})])]
        (is (= 6.0 (get b "n1")))
        (is (= 4.0 (get b "seller")))))))

;; ---------------------------------------------------------------------------
;; Capability policy (ADR-2607319500 D3)
;;
;; Measured against the live registry 2026-07-31: 45 enrolled nodes, all tier
;; :browser, 44 of them advertising a byte-identical :mem-bytes 34359738368
;; (32 GiB), and none of the ten machines that actually run work. These tests
;; pin what that does to a settlement and what :measured-only does instead.
;; ---------------------------------------------------------------------------

(def ^:private measured-node
  {:name "judah" :caps {:mem-bytes 17179869184 :capability/source :measured}})

(def ^:private declared-node
  ;; the live shape: a browser tab asserting 32 GiB with no provenance
  {:name "tab-03GbCM" :caps {:mem-bytes 34359738368}})

(def ^:private mixed-run
  {:model {:credit/per-token 2} :tokens 100 :duration-ms 60000
   :plan {:assignments [{:node measured-node :span 10 :est-bytes 10000000000}
                        {:node declared-node :span 10 :est-bytes 10000000000}]}})

(deftest settle-default-is-byte-identical-to-history
  (testing "the 1-arity fold stored feeds replay through is UNCHANGED except for
            the always-0.0 :run/unallocated accounting key"
    (is (= (dissoc (credits/settle run) :run/unallocated)
           (dissoc (credits/settle run {:capability-policy :permissive})
                   :run/unallocated))))
  (testing ":permissive never leaves credits unallocated"
    (is (= 0.0 (:run/unallocated (credits/settle run)))))
  (testing "an unmeasured node is still paid under the default policy —
            changing that retroactively would rewrite what past runs mean"
    (is (pos? (get-in (credits/settle mixed-run) [:run/shares "tab-03GbCM"])))))

(deftest measured-only-excludes-unmeasured-from-the-denominator
  (let [permissive (credits/settle mixed-run)
        strict (credits/settle mixed-run {:capability-policy :measured-only})]
    (testing "equal est-bytes/span → the flat split that a constant produces"
      (is (< (Math/abs (- (get-in permissive [:run/shares "judah"])
                          (get-in permissive [:run/shares "tab-03GbCM"])))
             1e-9)))
    (testing "the unmeasured node is dropped, not zeroed into the denominator"
      (is (nil? (get-in strict [:run/shares "tab-03GbCM"])))
      (is (= 1 (count (:run/shares strict)))))
    (testing "the whole pool goes to the one node whose capability was measured"
      (is (< (Math/abs (- (get-in strict [:run/shares "judah"])
                          (* 2.0 (get-in permissive [:run/shares "judah"]))))
             1e-9)))
    (testing "the exclusion is reported as data, with its reason"
      (is (= [{:node "tab-03GbCM" :provenance :declared}]
             (:run/capability-excluded strict))))))

(deftest measured-only-with-nothing-measured-declines-to-allocate
  (let [nobody {:model {:credit/per-token 2} :tokens 100 :duration-ms 60000
                :plan {:assignments [{:node declared-node :span 10 :est-bytes 1e10}
                                     {:node (assoc declared-node :name "tab-0BURfK")
                                      :span 10 :est-bytes 1e10}]}}
        strict (credits/settle nobody {:capability-policy :measured-only})
        permissive (credits/settle nobody)]
    (testing "unknown weights are NOT zero — nothing justifies paying anyone"
      (is (= {} (:run/shares strict)))
      (is (= 170.0 (:run/unallocated strict))))
    (testing ":permissive pays the same two nodes an equal split — which is
              exactly the failure mode: a compiled-in constant makes every
              memory-time weight identical, so the split carries no information"
      (is (= 2 (count (:run/shares permissive))))
      (is (< (Math/abs (- 85.0 (get-in permissive [:run/shares "tab-03GbCM"]))) 1e-9))
      (is (= 0.0 (:run/unallocated permissive))))
    (testing "both excluded nodes are named"
      (is (= 2 (count (:run/capability-excluded strict)))))))

(deftest permissive-head-fallback-is-unchanged
  ;; mt-sum = 0 (span 0 → weight 0) is the ONLY case that reaches the historical
  ;; head fallback. Pinned separately so the :measured-only work above cannot
  ;; quietly move it.
  (let [no-weight {:model {:credit/per-token 2} :tokens 100 :duration-ms 60000
                   :plan {:assignments [{:node {:name "head" :head? true}
                                         :span 0 :est-bytes 1e10}]}}
        r (credits/settle no-weight)]
    (is (= 170.0 (get-in r [:run/shares "head"])))
    (is (= 0.0 (:run/unallocated r)))))

(deftest accounting-identity-holds-under-every-policy
  (doseq [r [run mixed-run]
          p [:permissive :measured-only]]
    (let [{:run/keys [total treasury head shares unallocated]}
          (credits/settle r {:capability-policy p})]
      (is (< (Math/abs (- total (+ treasury head (reduce + 0.0 (vals shares)) unallocated)))
             1e-9)
          (str "total must equal treasury + head + shares + unallocated under " p)))))

;; ── 返金（ADR-2608037200）─────────────────────────────────────────────────────
;; 守りたい性質は 1 つ: **返金が credits の mint 経路にならないこと。**
;; 額を呼び出し側に渡させず台帳から引くこと、二度目を拒否すること、この 2 つが
;; それを構成している。

(deftest refund-reverses-a-spend
  (let [feed [(credits/spend :alice 300 {:for {:job "job-1" :model "veo-3.1-fast"}})]
        r (credits/refundable feed "job-1")]
    (is (:ok? r))
    (is (= "alice" (:who r)))
    (is (== 300.0 (:credits r)))
    (testing "fold すると残高が元に戻る"
      (let [after (credits/balances (conj feed (credits/refund (:who r) (:credits r)
                                                              {:for {:job "job-1"} :reason "upstream failed"})))]
        (is (== 0.0 (credits/balance-of after "alice")))))))

(deftest refund-is-idempotent-by-job
  (testing "同じジョブは二度返金されない。状態ポーリングは何度でも来るので、
            ここが緩むとポーリング回数ぶん credits が湧く"
    (let [spend (credits/spend :alice 300 {:for {:job "job-1"}})
          refunded (credits/refund "alice" 300 {:for {:job "job-1"}})
          r (credits/refundable [spend refunded] "job-1")]
      (is (false? (:ok? r)))
      (is (= :already-refunded (:error r)))
      (is (== 300.0 (:refunded r))))))

(deftest refund-refuses-what-was-never-spent
  (testing "存在しないジョブの返金は拒否 —— 通れば任意額の mint になる"
    (let [r (credits/refundable [(credits/spend :alice 300 {:for {:job "job-1"}})] "job-does-not-exist")]
      (is (false? (:ok? r)))
      (is (= :no-such-spend (:error r))))))

(deftest refund-amount-comes-from-the-ledger-not-the-caller
  (testing "refundable は額を返す。呼び出し側が額を『指定』できる API 形に
            しないことが、過大返金を引数にしないための構造"
    (let [r (credits/refundable [(credits/spend :alice 42 {:for {:job "j"}})] "j")]
      (is (== 42.0 (:credits r)))))
  (testing "0 以下の返金は構成子が拒否する"
    (is (thrown-with-msg? Exception #"must be positive"
                          (credits/refund "alice" 0 {:for {:job "j"}})))))

;; ── hold / capture / release (ADR-2608291009 実装順 2) ────────────────────────

(def ^:private earned
  "alice に 500 credits を稼がせた 1 事象。`balances-step` の pre-settled 分岐
   （`:run/shares` を持つ run）をそのまま使う。"
  {:run/shares {"alice" 500.0}})

(deftest hold-debits-like-a-spend
  (testing "hold は予約であって消費ではないが、使える残高からは外れる"
    (let [feed [earned (credits/hold :alice 120 {:for {:job "j1"}})]]
      (is (== 380.0 (credits/balance-of (credits/balances feed) :alice)))))
  (testing "hold は settle 扱いに落ちない（分岐が :else より前にある）"
    (let [b (credits/balances [(credits/hold :alice 10 {:for {:job "j1"}})])]
      (is (= #{"alice"} (set (keys b)))))))

(deftest capture-returns-the-unused-remainder
  (testing "見積 120 で予約し 70 だけ使ったら、差額 50 は同じ事象で口座に戻る"
    (let [feed [earned
                (credits/hold :alice 120 {:for {:job "j1"}})
                (credits/capture :alice {:for {:job "j1"} :held 120 :captured 70})]]
      (is (== 430.0 (credits/balance-of (credits/balances feed) :alice)))))
  (testing "実消費が見積どおりなら、hold+capture は spend と同じ残高に着く"
    (let [via-hold [earned
                    (credits/hold :alice 120 {:for {:job "j1"}})
                    (credits/capture :alice {:for {:job "j1"} :held 120 :captured 120})]
          via-spend [earned (credits/spend :alice 120 {:for {:job "j1"}})]]
      (is (== (credits/balance-of (credits/balances via-spend) :alice)
              (credits/balance-of (credits/balances via-hold) :alice))))))

(deftest release-returns-the-whole-hold
  (testing "ジョブが失敗したら予約は全額戻る。返す原資を持つ口座は要らない"
    (let [feed [earned
                (credits/hold :alice 120 {:for {:job "j1"}})
                (credits/release :alice {:for {:job "j1"} :held 120 :reason "provider-error"})]]
      (is (== 500.0 (credits/balance-of (credits/balances feed) :alice))))))

(deftest outstanding-hold-is-the-only-place-a-hold-is-consumed
  (let [held (credits/hold :alice 120 {:for {:job "j1"}})]
    (testing "額は台帳が返す。呼び出し側に held を指定させない"
      (let [r (credits/outstanding-hold [earned held] "j1")]
        (is (true? (:ok? r)))
        (is (= "alice" (:who r)))
        (is (== 120.0 (:held r)))))
    (testing "capture 済みの hold は二度 capture できない —— ポーリングの回数だけ credits が湧く形を台帳側で塞ぐ"
      (let [feed [earned held (credits/capture :alice {:for {:job "j1"} :held 120 :captured 70})]
            r (credits/outstanding-hold feed "j1")]
        (is (false? (:ok? r)))
        (is (= :already-captured (:error r)))))
    (testing "release 済みの hold も同じ"
      (let [feed [earned held (credits/release :alice {:for {:job "j1"} :held 120})]
            r (credits/outstanding-hold feed "j1")]
        (is (false? (:ok? r)))
        (is (= :already-released (:error r)))))
    (testing "存在しないジョブは拒否"
      (let [r (credits/outstanding-hold [earned held] "no-such-job")]
        (is (false? (:ok? r)))
        (is (= :no-such-hold (:error r)))))))

(deftest capture-cannot-exceed-the-hold
  (testing "過大 capture は構成子が拒否する —— refund と違い held を同じ引数で
            受け取っているので、ここで構造的に不可能にできる"
    (is (thrown-with-msg? Exception #"exceeds the hold"
                          (credits/capture :alice {:for {:job "j"} :held 100 :captured 100.01}))))
  (testing "負の captured も拒否"
    (is (thrown-with-msg? Exception #"cannot be negative"
                          (credits/capture :alice {:for {:job "j"} :held 100 :captured -1}))))
  (testing "0 以下の hold は拒否"
    (is (thrown-with-msg? Exception #"must be positive"
                          (credits/hold :alice 0 {:for {:job "j"}})))))

(deftest hold-is-covered-by-the-overdraft-check
  (testing "hold は debit なので ledger-violations がそのまま効く"
    (let [v (credits/ledger-violations [earned (credits/hold :alice 501 {:for {:job "j1"}})])]
      (is (= 1 (count v)))
      (is (= "alice" (:account (first v))))))
  (testing "残高の範囲内なら違反にならない"
    (is (empty? (credits/ledger-violations
                 [earned (credits/hold :alice 500 {:for {:job "j1"}})]))))
  (testing "capture の差戻しは違反を作らない"
    (is (empty? (credits/ledger-violations
                 [earned
                  (credits/hold :alice 500 {:for {:job "j1"}})
                  (credits/capture :alice {:for {:job "j1"} :held 500 :captured 1})])))))

(deftest ledger-checks-read-json-feeds-but-the-fold-does-not
  ;; ⚠ これは私が入れた非対称ではない。**hold 以前から在る。** 実測:
  ;;   (balances [{"run/shares" {"alice" 500.0}} {"run/spend" {"alice" 120.0}}])
  ;;     => {head 0.0, :treasury 0.0}    ← alice の 500 も 120 の spend も消える
  ;;   (refundable [{"run/spend" ... "run/spend-for" ...}] "j") => {:ok? true ...}
  ;; `balances-step` は keyword キーしか見ないので、文字列キーの事象は :else に
  ;; 落ちて **settle 済み run として読み替えられる**。`refundable` は両方読む。
  ;; つまり『refundable が読めた feed が balances では別物に畳まれる』。
  ;; 直すのは balances の意味を変える別の変更なので、この commit では触らない ——
  ;; ここでは現在の契約を明示的に固定して、次に読む者が驚かないようにする。
  (testing "outstanding-hold は refundable と同じく文字列キーの feed を読む"
    (let [json [{"run/hold" {"who" "alice" "credits" 120.0}
                 "run/hold-for" {"job" "j1"}}]
          r (credits/outstanding-hold json "j1")]
      (is (true? (:ok? r)))
      (is (= "alice" (:who r)))
      (is (== 120.0 (:held r)))))
  (testing "balances は keyword キーの事象しか畳まない（hold も spend と同じ制約）"
    (let [json [{"run/hold" {"who" "alice" "credits" 120.0}
                 "run/hold-for" {"job" "j1"}}]]
      (is (zero? (credits/balance-of (credits/balances json) :alice))
          "この 0.0 は『hold が無かった』ではなく『事象を読めなかった』である")))
  (testing "EDN 形なら hold / capture / release は正しく畳まれる"
    (let [edn [earned
               (credits/hold :alice 120 {:for {:job "j1"}})
               (credits/capture :alice {:for {:job "j1"} :held 120 :captured 70})]]
      (is (== 430.0 (credits/balance-of (credits/balances edn) :alice))))))

(deftest hold-predicates-do-not-overlap
  (let [h (credits/hold :alice 1 {:for {:job "j"}})
        c (credits/capture :alice {:for {:job "j"} :held 1 :captured 1})
        r (credits/release :alice {:for {:job "j"} :held 1})
        s (credits/spend :alice 1 {:for {:job "j"}})]
    (is (and (credits/hold? h) (not (credits/capture? h)) (not (credits/release? h))))
    (is (and (credits/capture? c) (not (credits/hold? c))))
    (is (and (credits/release? r) (not (credits/hold? r))))
    (is (not (or (credits/hold? s) (credits/capture? s) (credits/release? s))))))

;; ── 公開 feed（/infer/runs）の wire 形を読む ─────────────────────────────────
;;
;; 下の fixture は **api.murakumo.cloud/infer/runs が実際に返したバイト**を
;; 貼ったものである（実測 2026-08-31、764 事象）。手で作った形ではない ——
;; この ns の fold が自分の API を読めないという欠陥は、まさに「想定した形」と
;; 「実際に出ている形」がずれていたことで起きたので、テストの入力は後者にする。

(def ^:private wire-transfer
  {"transfer" {"from" "did:key:z6MkCustomer2"
               "to" "did:key:z6MkbbbbCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
               "credits" 1}
   "transfer-for" nil "seq" 1785316949466646})

(def ^:private wire-spend
  {"spend" {"did:key:z6MkCustomer2" 50}
   "spend-for" {"model" "glm-5.2-reap50-q2k" "tokens" 50}
   "seq" 1783055912990389})

(def ^:private wire-topup
  {"topup" {"did:key:z6MkCustomer1" 1900} "treasury" 100 "total" 2000
   "kind" "topup" "payment" {"processor" "stripe" "charge-id" "ch_demo" "usd" 20}
   "proof" "fiat-verified" "seq" 1783040599333510})

(def ^:private wire-grant
  {"grant" {"did:key:z6MkTrial1783050426" 200} "total" 200
   "kind" "grant" "grant-reason" "welcome" "seq" 1783050427509550})

(def ^:private wire-run
  {"job-id" "j" "model" "browser-swarm" "units" {"jobs" 1} "total" 8
   "shares" {"did:key:z6MkPublicTab" 7.6} "treasury" 0.4
   "proof" "verified" "seq" 1783004952621620})

(def ^:private wire-unsettled-run
  ;; shares も total も持たない —— 記録されたが精算されていない run
  {"model" "glm-5.2" "units" {"tokens" 500} "kind" "text" "seq" 1783046749613089})

(def ^:private wire-pending-topup
  {"pending-topup" {"did" "did:key:pending-abc"} "kind" "pending" "seq" 1})

(deftest the-canonical-fold-cannot-read-the-published-feed
  (testing "wire 形の事象は balances-step の分岐を全部外れ、settle 済み run として
            読み替えられる —— これが 48 口座中 2 口座しか出なかった理由"
    (let [b (credits/balances [wire-transfer wire-spend])]
      (is (not (contains? b "did:key:z6MkCustomer2"))
          "spend も transfer も見えないので、その口座は現れない")))
  (testing "同じ事象を from-wire に通せば読める"
    (let [b (credits/balances (map credits/from-wire [wire-spend]))]
      (is (== -50.0 (credits/balance-of b "did:key:z6MkCustomer2"))))))

(deftest from-wire-maps-every-shape-the-live-feed-actually-emits
  (testing "transfer"
    (let [e (credits/from-wire wire-transfer)]
      (is (credits/transfer? e))
      (is (= "did:key:z6MkbbbbCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
             (get-in e [:run/transfer :to])))))
  (testing "spend"
    (is (== 50.0 (get (:run/spend (credits/from-wire wire-spend)) "did:key:z6MkCustomer2"))))
  (testing "topup と grant は受け手の残高を増やす一方向の発行なので :run/shares に写る"
    (is (== 1900.0 (get (:run/shares (credits/from-wire wire-topup)) "did:key:z6MkCustomer1")))
    (is (== 100.0 (:run/treasury (credits/from-wire wire-topup))))
    (is (== 200.0 (get (:run/shares (credits/from-wire wire-grant)) "did:key:z6MkTrial1783050426"))))
  (testing "settle 済み run"
    (is (== 7.6 (get (:run/shares (credits/from-wire wire-run)) "did:key:z6MkPublicTab")))
    (is (== 0.4 (:run/treasury (credits/from-wire wire-run)))))
  (testing "acceptance density は from-wire を通して初めて数えられる"
    (is (= #{} (credits/accepting-sellers [wire-transfer]))
        "wire のままだと 0 —— これは『相手が居ない』ではなく『読めなかった』")
    (is (= #{"did:key:z6MkbbbbCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"}
           (credits/accepting-sellers (map credits/from-wire [wire-transfer]))))))

(deftest from-wire-is-total-and-names-what-it-could-not-read
  (testing "知らない形には unreadable を返す（例外を投げない）"
    (is (= credits/unreadable (credits/from-wire wire-unsettled-run)))
    (is (= credits/unreadable (credits/from-wire {"nothing" 1}))))
  (testing "知っているが残高を動かさない形は no-op で、unreadable と区別される"
    (is (= credits/no-op (credits/from-wire wire-pending-topup))))
  (testing "unreadable と no-op は互いに違う値でなければならない —— 同じなら
            『読めなかった』と『効かなかった』が呼び出し側で潰れる"
    (is (not= credits/unreadable credits/no-op))))

(deftest balances-of-wire-reports-what-it-could-not-read
  (let [feed [wire-run wire-topup wire-grant wire-spend wire-transfer
              wire-pending-topup wire-unsettled-run]
        {:keys [balances read skipped unreadable unreadable-keys]}
        (credits/balances-of-wire feed)]
    (testing "読めた / 効かない既知 / 読めなかった を別々に数える"
      (is (= 5 read))
      (is (= 1 skipped))
      (is (= 1 unreadable)))
    (testing "読めなかった形は key 集合で名指しされる"
      (is (contains? unreadable-keys "model"))
      (is (contains? unreadable-keys "units")))
    (testing "残高は from-wire を通した分だけを畳んだもの"
      (is (== 1900.0 (credits/balance-of balances "did:key:z6MkCustomer1")))
      (is (== -51.0 (credits/balance-of balances "did:key:z6MkCustomer2"))
          "Customer2 は spend 50 を払い、さらに transfer の送り主でもある: -50 -1")
      (is (== 1.0 (credits/balance-of balances "did:key:z6MkbbbbCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"))
          "transfer の受け手側。借方と貸方が同じ 1 事象で閉じる"))
    (testing "空の feed は空の残高だが、読めた件数 0 がそう言う"
      (let [r (credits/balances-of-wire [])]
        (is (zero? (:read r)))
        (is (zero? (:unreadable r)))))
    (testing "全部読めない feed は、空の台帳と同じ {} を返さない —— :unreadable が立つ"
      (let [r (credits/balances-of-wire [wire-unsettled-run wire-unsettled-run])]
        (is (= {} (:balances r)))
        (is (= 2 (:unreadable r)))
        (is (zero? (:read r)))))))
