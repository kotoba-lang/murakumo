;; W6 pure-planner oracle: murakumo.infer.credits integer core
;; vs kotoba/infer_credits_core.kotoba.

(ns murakumo.infer-credits-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.credits :as credits]))

(def port-source (slurp "kotoba/infer_credits_core.kotoba"))

(def export-prefix
  (str "default-per-token head-num head-den protocol-num protocol-den token-cost cut pool "
       "memory-time-weight charge-allow? balance-after-spend "
       "unit-cost job-cost-2 job-cost-3 share-floor share-pack-2 settle-pool-shares-2 "
       "mt-sum-2 mt-sum-3"))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest defaults-match-credits-cljc
  (let [actual (compile-i64-cases
                {"p" "(default-per-token)"
                 "hn" "(head-num)" "hd" "(head-den)"
                 "pn" "(protocol-num)" "pd" "(protocol-den)"})]
    (is (= credits/default-per-token (get actual "p")))
    (is (= 1 (get actual "hn")))
    (is (= 10 (get actual "hd")))
    (is (= 1 (get actual "pn")))
    (is (= 20 (get actual "pd")))
    (is (= (double (/ (get actual "hn") (get actual "hd")))
           (double credits/default-head-frac)))
    (is (= (double (/ (get actual "pn") (get actual "pd")))
           (double credits/default-protocol-frac)))))

(deftest token-cost-and-cuts-match-settle-totals
  (let [price 2 tokens 100
        total (* price tokens)
        settled (credits/settle {:model {:credit/per-token price}
                                 :tokens tokens
                                 :duration-ms 60000
                                 :plan {:assignments
                                        [{:node {:name "head" :head? true}
                                          :span 1 :est-bytes 1}]}})
        cases {"tc" (str "(token-cost " price " " tokens ")")
               "tr" (str "(cut " total " 1 20)")
               "hd" (str "(cut " total " 1 10)")
               "pl" (str "(pool " total " "
                         (long (:run/treasury settled)) " "
                         (long (:run/head settled)) ")")}
        actual (compile-i64-cases cases)]
    (is (= (long (:run/total settled)) (get actual "tc")))
    (is (= (long (:run/treasury settled)) (get actual "tr")))
    (is (= (long (:run/head settled)) (get actual "hd")))
    (is (= (long (- (:run/total settled)
                    (:run/treasury settled)
                    (:run/head settled)))
           (get actual "pl")))
    (testing "integer conservation"
      (is (= total (+ (get actual "tr") (get actual "hd") (get actual "pl")))))))

(deftest memory-time-weight-respects-span
  (let [est 11450000000 dur 60000
        cases {"w1" (str "(memory-time-weight " est " " dur " 8)")
               "w0" (str "(memory-time-weight " est " " dur " 0)")
               "wn" (str "(memory-time-weight " est " " dur " -1)")}
        actual (compile-i64-cases cases)]
    (is (= (* est dur) (get actual "w1")))
    (is (= 0 (get actual "w0")))
    (is (= 0 (get actual "wn")))))

(deftest charge-allow-matches-balance-gate
  (let [corpus [[500 50] [50 50] [49 50] [0 1] [100 0]]
        cases (into {} (map-indexed
                        (fn [i [b c]]
                          [(str "a_" i) (str "(charge-allow? " b " " c ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [b c]] (map-indexed vector corpus)]
      (testing (str b ">=" c)
        (is (= (if (< b c) 0 1) (get actual (str "a_" i))))))))

(deftest multi-unit-and-shares
  (let [actual (compile-i64-cases
                {"u1" "(unit-cost 5 4)"
                 "j2" "(job-cost-2 2 100 5 4)"
                 "j3" "(job-cost-3 2 100 5 4 30)"
                 "sf" "(share-floor 100 3 10)"
                 "sp" "(share-pack-2 100 3 7)"
                 "ss" "(settle-pool-shares-2 200 1 1)"
                 "m2" "(mt-sum-2 10 20)"
                 "m3" "(mt-sum-3 1 2 3)"})
        s0 (mod (get actual "sp") 65536)
        s1 (quot (get actual "sp") 65536)
        ;; settle 200 → treasury 10 head 20 pool 170 → equal w → 85 each
        ss0 (mod (get actual "ss") 65536)
        ss1 (quot (get actual "ss") 65536)]
    (is (= 20 (get actual "u1")))
    (is (= (+ 200 20) (get actual "j2")))
    (is (= (+ 200 20 30) (get actual "j3")))
    (is (= 30 (get actual "sf")))
    (is (= 30 s0))
    (is (= 70 s1))
    (is (= 85 ss0))
    (is (= 85 ss1))
    (is (= 30 (get actual "m2")))
    (is (= 6 (get actual "m3")))
    (testing "cljc job-cost multi-unit"
      (is (= 220.0 (credits/job-cost {:credit/per-token 2 :credit/per-image 5}
                                     {:tokens 100 :images 4}))))))

