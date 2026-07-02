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

(deftest degenerate-runs
  (testing "a run with no serving assignments pays the head, not /0"
    (let [s (credits/settle {:model {} :tokens 10 :duration-ms 1 :plan {:assignments []}})]
      (is (= 10.0 (:run/total s)))
      (is (pos? (get-in s [:run/shares "head"]))))))
