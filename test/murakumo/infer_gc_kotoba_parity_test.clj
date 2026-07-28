;; W6 pure-planner oracle: murakumo.infer.gc policy math
;; vs kotoba/infer_gc_core.kotoba.

(ns murakumo.infer-gc-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.gc :as gc]))

(def port-source (slurp "kotoba/infer_gc_core.kotoba"))

(def export-prefix
  "gib default-target-free default-comfy-keep-days default-hf-keep need-bytes free-after target-met? rank-better? comfy-evictable?")

(def G gc/GiB)

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

(deftest constants-match-gc-defaults
  (let [actual (compile-i64-cases
                {"g" "(gib)"
                 "t" "(default-target-free)"
                 "c" "(default-comfy-keep-days)"
                 "h" "(default-hf-keep)"})]
    (is (= G (get actual "g")))
    (is (= (:target-free-bytes gc/default-policy) (get actual "t")))
    (is (= (:comfy-keep-days gc/default-policy) (get actual "c")))
    (is (= (:hf-keep gc/default-policy) (get actual "h")))))

(deftest need-bytes-matches-plan-need
  (let [corpus [[(* 20 G) (* 5 G)]
                [(* 20 G) (* 50 G)]
                [(* 12 G) (* 12 G)]
                [0 0]]
        cases (into {} (map-indexed
                        (fn [i [t f]]
                          [(str "n_" i) (str "(need-bytes " t " " f ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [t f]] (map-indexed vector corpus)]
      (testing (str t "/" f)
        (is (= (max 0 (- t f)) (get actual (str "n_" i))))))))

(deftest free-after-and-target-met-match-plan-fields
  (let [corpus [[(* 5 G) (* 10 G) (* 12 G)]
                [(* 5 G) (* 2 G) (* 12 G)]
                [(* 50 G) 0 (* 20 G)]]
        fa-cases (into {} (map-indexed
                           (fn [i [f r _]]
                             [(str "fa_" i) (str "(free-after " f " " r ")")])
                           corpus))
        tm-cases (into {} (map-indexed
                           (fn [i [f r t]]
                             [(str "tm_" i) (str "(target-met? " f " " r " " t ")")])
                           corpus))
        fa (compile-i64-cases fa-cases)
        tm (compile-i64-cases tm-cases)]
    (doseq [[i [f r t]] (map-indexed vector corpus)]
      (testing (pr-str [f r t])
        (is (= (+ f r) (get fa (str "fa_" i))))
        (is (= (if (>= (+ f r) t) 1 0) (get tm (str "tm_" i))))))))

(deftest rank-better-matches-sort-order
  (let [;; [atime1 bytes1 atime2 bytes2]
        pairs [[10 (* 1 G) 5 (* 9 G)]
               [5 (* 9 G) 10 (* 1 G)]
               [7 (* 3 G) 7 (* 1 G)]
               [7 (* 1 G) 7 (* 3 G)]
               [0 0 0 0]]
        cases (into {} (map-indexed
                        (fn [i [a1 b1 a2 b2]]
                          [(str "r_" i)
                           (str "(rank-better? " a1 " " b1 " " a2 " " b2 ")")])
                        pairs))
        actual (compile-i64-cases cases)]
    (doseq [[i [a1 b1 a2 b2]] (map-indexed vector pairs)]
      (testing (pr-str [a1 b1 a2 b2])
        (let [rank1 [(- a1) (- b1)]
              rank2 [(- a2) (- b2)]
              expected (if (neg? (compare rank1 rank2)) 1 0)]
          (is (= expected (get actual (str "r_" i)))))))))

(deftest comfy-evictable-matches-keep-days-rule
  (let [keep (:comfy-keep-days gc/default-policy)
        corpus [0 1 7 8 30]
        cases (into {} (map-indexed
                        (fn [i d]
                          [(str "ce_" i) (str "(comfy-evictable? " d " " keep ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i d] (map-indexed vector corpus)]
      (testing (str d)
        (is (= (if (> d keep) 1 0) (get actual (str "ce_" i))))))))
