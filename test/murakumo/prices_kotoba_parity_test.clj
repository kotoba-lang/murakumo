;; Pure-planner oracle: murakumo.infer.prices' calendar + staleness judgement
;; vs kotoba/prices_core.kotoba.
;;
;; `days-between` is private in the cljc ns and stays private -- the point of
;; the port is that the two agree, not that the boundary moves.

(ns murakumo.prices-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.prices :as prices]))

(def port-source (slurp "kotoba/prices_core.kotoba"))

(def export-prefix "civil-days days-between stale? epoch-shift")

(def ^:private span-ty
  (str "[:record :prices/span [[:from-y :i64] [:from-m :i64] [:from-d :i64] "
       "[:to-y :i64] [:to-m :i64] [:to-d :i64]]]"))

(def ^:private staleness-ty
  "[:record :prices/staleness [[:elapsed :i64] [:limit :i64]]]")

(defn- compile-cases
  "Each case is a zero-arg wrapper around one call, the same shape the other
  `*_kotoba_parity_test` namespaces use."
  [result-type cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] " result-type " " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(def ^:private cljc-days-between
  ;; The cljc side is private on purpose; reach it by var rather than widening
  ;; its interface for a test.
  @#'prices/days-between)

(defn- iso [[y m d]]
  (format "%04d-%02d-%02d" y m d))

(def ^:private date-corpus
  ;; Chosen for what the algorithm actually has to get right, not for coverage
  ;; theatre: the epoch itself, both sides of a March boundary (the algorithm
  ;; shifts the year there), a leap day, the 100-year rule, the 400-year rule
  ;; that overrides it, and a pre-epoch date so the negative-year branch of
  ;; `era` is exercised rather than assumed.
  [[1970 1 1]    ; epoch
   [1970 3 1]    ; first day of the shifted year
   [1970 2 28]   ; last day before it
   [2000 2 29]   ; leap day, 400-year rule
   [1900 3 1]    ; 1900 is NOT a leap year (100-year rule)
   [2024 2 29]   ; ordinary leap year
   [2026 8 11]
   [2099 12 31]
   [2100 3 1]    ; next 100-year rule
   [1969 12 31]  ; pre-epoch: days-from-civil goes negative
   [1600 1 1]])  ; deep pre-epoch, exercises the negative-era branch

(deftest civil-days-matches-the-cljc-calendar
  ;; `days-between x 1970-01-01` recovers `civil-days x` exactly, because the
  ;; cljc helper is the same algorithm with the epoch already subtracted.
  (let [cases (into {} (map-indexed
                        (fn [i [y m d]]
                          [(str "c" i) (str "(civil-days " y " " m " " d ")")])
                        date-corpus))
        actual (compile-cases ":i64" cases)]
    (doseq [[i [y m d :as date]] (map-indexed vector date-corpus)]
      (testing (iso date)
        (is (= (- (cljc-days-between "1970-01-01" (iso date)))
               (- (get actual (str "c" i)))))
        ;; and state the absolute value too, so a sign error in BOTH
        ;; implementations could not pass by cancelling out
        (is (= (cljc-days-between "1970-01-01" (iso date))
               (get actual (str "c" i))))))))

(deftest days-between-matches-across-every-pair
  (let [pairs (for [from date-corpus to date-corpus] [from to])
        cases (into {} (map-indexed
                        (fn [i [[fy fm fd] [ty tm td]]]
                          [(str "d" i)
                           (str "(days-between (record-new " span-ty " "
                                fy " " fm " " fd " " ty " " tm " " td "))")])
                        pairs))
        actual (compile-cases ":i64" cases)]
    (is (= 121 (count pairs)))
    (doseq [[i [from to]] (map-indexed vector pairs)]
      (testing (str (iso from) " -> " (iso to))
        (is (= (cljc-days-between (iso from) (iso to))
               (get actual (str "d" i))))))))

(deftest stale-matches-the-cljc-boundary
  ;; The boundary itself is the interesting case: a registry verified exactly
  ;; `limit` days ago is fresh on its last day. Off-by-one here silently either
  ;; quotes from stale costs or refuses a registry that is still good.
  (let [corpus [[0 30] [29 30] [30 30] [31 30] [1000 30] [-5 30] [0 0] [1 0]]
        cases (into {} (map-indexed
                        (fn [i [elapsed limit]]
                          [(str "s" i)
                           (str "(stale? (record-new " staleness-ty " "
                                elapsed " " limit "))")])
                        corpus))
        actual (compile-cases ":bool" cases)]
    (doseq [[i [elapsed limit]] (map-indexed vector corpus)]
      (testing (str elapsed "/" limit)
        (is (= (> elapsed limit) (get actual (str "s" i))))))))

(deftest stale-matches-the-registry-path-end-to-end
  ;; The composition the cljc `stale?` actually performs, against the shipped
  ;; registry rather than a fixture -- so a change to `:staleness-days` or
  ;; `:verified-at` is exercised by this test instead of going unnoticed.
  (let [registry (prices/load-registry)
        verified (:verified-at registry)
        limit (:staleness-days registry)]
    (is (string? verified) "the shipped registry carries :verified-at")
    (is (integer? limit) "the shipped registry carries :staleness-days")
    (let [[vy vm vd] (map parse-long (str/split verified #"-"))
          todays [[2026 8 11] [2026 1 1] [2030 1 1] verified]
          todays (remove string? todays)
          cases (into {} (map-indexed
                          (fn [i [ty tm td]]
                            [(str "e" i)
                             (str "(stale? (record-new " staleness-ty " "
                                  "(days-between (record-new " span-ty " "
                                  vy " " vm " " vd " " ty " " tm " " td ")) "
                                  limit "))")])
                          todays))
          actual (compile-cases ":bool" cases)]
      (doseq [[i today] (map-indexed vector todays)]
        (testing (str verified " -> " (iso today))
          (is (= (prices/stale? registry (iso today))
                 (get actual (str "e" i)))))))))
