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
  "default-per-token head-num head-den protocol-num protocol-den token-cost cut pool memory-time-weight charge-allow? balance-after-spend")

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
