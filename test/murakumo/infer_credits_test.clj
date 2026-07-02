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

(deftest receipts
  (let [settled (credits/settle run)
        r1 (credits/receipt {:settled settled
                             :shard-reports [{:shard/rank {:layers [0 21]} :shard/owned-bytes 1745007264
                                              :shard/owned-tensors 358 :shard/host "main-2" :shard/ok true}]
                             :hash-fn (fn [s] (str "h" (hash s)))})
        r2 (credits/receipt {:settled settled :shard-reports [] :prev-hash (:receipt/hash r1)
                             :hash-fn (fn [s] (str "h" (hash s)))})]
    (testing "receipts chain and carry the shard evidence"
      (is (= "genesis" (:receipt/prev r1)))
      (is (= (:receipt/hash r1) (:receipt/prev r2)))
      (is (= 358 (get-in r1 [:receipt/shards 0 :shard/owned-tensors]))))
    (testing "identical bodies hash identically (deterministic, signable)"
      (is (= (:receipt/hash r1)
             (:receipt/hash (credits/receipt {:settled settled
                                              :shard-reports [{:shard/rank {:layers [0 21]} :shard/owned-bytes 1745007264
                                                               :shard/owned-tensors 358 :shard/host "main-2" :shard/ok true}]
                                              :hash-fn (fn [s] (str "h" (hash s)))})))))))

(deftest degenerate-runs
  (testing "a run with no serving assignments pays the head, not /0"
    (let [s (credits/settle {:model {} :tokens 10 :duration-ms 1 :plan {:assignments []}})]
      (is (= 10.0 (:run/total s)))
      (is (pos? (get-in s [:run/shares "head"]))))))
