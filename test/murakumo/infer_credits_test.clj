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
