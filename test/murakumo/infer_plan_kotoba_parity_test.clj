;; W6 pure-planner oracle: murakumo.infer.plan usable-bytes + choose-strategy
;; vs kotoba/infer_plan_core.kotoba.

(ns murakumo.infer-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.plan :as plan]))

(def port-source (slurp "kotoba/infer_plan_core.kotoba"))
(def GiB plan/GiB)

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [gib default-os-reserve default-headroom usable-bytes "
                      "choose-strategy-name " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [gib default-os-reserve default-headroom usable-bytes "
                      "choose-strategy-name " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- wired-arg [node]
  (if-let [w (:wired-limit-bytes node)] w -1))

(defn- usable-call [node]
  (let [os (or (:os-reserve-bytes node) plan/default-os-reserve)
        head (or (:headroom-bytes node) plan/default-headroom)
        wired (wired-arg node)]
    (str "(usable-bytes " (:mem-bytes node) " " os " " head " " wired ")")))

(deftest constants-match-plan-cljc
  (let [actual (compile-i64-cases
                {"g" "(gib)"
                 "os" "(default-os-reserve)"
                 "hd" "(default-headroom)"})]
    (is (= GiB (get actual "g")))
    (is (= plan/default-os-reserve (get actual "os")))
    (is (= plan/default-headroom (get actual "hd")))))

(deftest usable-bytes-matches-plan-cljc
  (let [nodes [{:name "a" :mem-bytes (* 16 GiB) :os-reserve-bytes (* 5/2 GiB)}
               {:name "b" :mem-bytes (* 16 GiB) :os-reserve-bytes (* 5/2 GiB)
                :wired-limit-bytes (* 10 GiB)}
               {:name "c" :mem-bytes (* 2 GiB)}
               {:name "d" :mem-bytes (* 32 GiB) :os-reserve-bytes (* 12 GiB)}]
        cases (into {} (map-indexed (fn [i n] [(str "u_" i) (usable-call n)]) nodes))
        actual (compile-i64-cases cases)]
    (doseq [[i n] (map-indexed vector nodes)]
      (testing (:name n)
        (is (= (plan/usable-bytes n)
               (get actual (str "u_" i))))))))

(deftest choose-strategy-name-matches-plan-cljc
  (let [corpus [{:link-gbps 1 :ranks 12 :model {:model/experts 128 :model/kv-heads 4}}
                {:link-gbps 40 :ranks 4 :model {:model/experts 128 :model/kv-heads 8}}
                {:link-gbps 40 :ranks 5 :model {:model/experts 128 :model/kv-heads 8}}
                {:link-gbps nil :ranks 8 :model {:model/experts 64 :model/kv-heads 8}}]
        ;; nil link → treat as 0 (conservative pipeline), matching missing measurement
        call (fn [{:keys [link-gbps ranks model]}]
               (let [link (long (or link-gbps 0))
                     exp (long (or (:model/experts model) 0))
                     kv (long (or (:model/kv-heads model) 0))
                     r (long (or ranks 0))]
                 (str "(choose-strategy-name " link " " r " " exp " " kv ")")))
        cases (into {} (map-indexed (fn [i m] [(str "s_" i) (call m)]) corpus))
        actual (compile-string-cases cases)]
    (doseq [[i m] (map-indexed vector corpus)]
      (testing (pr-str m)
        (is (= (name (:strategy (plan/choose-strategy m)))
               (get actual (str "s_" i))))))))
