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
  (testing "a KNOWN media unit (:images/:video-seconds) whose price key is
            simply absent from this model's registry entry must error, same
            as an unrecognized unit -- silently defaulting to 0 would mean
            free inference for any model onboarded before its media pricing
            is backfilled. Only :tokens has a documented global default"
    (let [incomplete-model {:model/id "no-media-pricing-yet"}]
      (is (thrown? Exception (credits/job-cost incomplete-model {:images 500})))
      (is (thrown? Exception (credits/job-cost incomplete-model {:video-seconds 300})))
      (is (thrown? Exception (credits/job-cost incomplete-model {:audio-seconds 10})))
      (is (thrown? Exception (credits/job-cost incomplete-model {:training-steps 50})))
      (testing "tokens alone still falls back to the documented default"
        (is (= (double credits/default-per-token)
               (credits/job-cost incomplete-model {:tokens 1}))))
      (testing "charge propagates the error too -- never silently approves at cost 0"
        (is (thrown? Exception (credits/charge {} "broke-user"
                                                {:model incomplete-model :units {:images 500}})))))))

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
    (let [s (credits/settle {:model {} :tokens 10 :duration-ms 1 :plan {:assignments []}})]
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
