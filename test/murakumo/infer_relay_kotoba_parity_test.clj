(ns murakumo.infer-relay-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.relay :as relay]))

(def port-source (slurp "kotoba/infer_relay_core.kotoba"))

(def ^:private id-ty
  "[:record :relay/id [[:prefix :string] [:n :i64]]]")

(def ^:private lease-ty
  "[:record :relay/lease [[:now-ms :i64] [:at-ms :i64] [:ttl-ms :i64]]]")
(def export-prefix "digit-char nat-str i64-str make-id lease-expired? msg-idle msg-job msg-settled")

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest make-id-matches-gen-id-shape
  (let [actual (compile-string-cases
                {"j" (str "(make-id (record-new " id-ty " " (kotoba-literal "job") " 0))")
                 "w" (str "(make-id (record-new " id-ty " " (kotoba-literal "w") " 3))")
                 "i" "(msg-idle)" "jo" "(msg-job)" "s" "(msg-settled)"})]
    (is (= "job-0" (get actual "j")))
    (is (= "w-3" (get actual "w")))
    (is (= "idle" (get actual "i")))
    (is (= "job" (get actual "jo")))
    (is (= "settled" (get actual "s")))
    ;; first enqueue produces job-0
    (let [[jid _] (relay/enqueue (relay/init) {:kind :x :input {} :price 1})]
      (is (= "job-0" jid)))))

(deftest lease-expired-matches-expire-predicate
  (let [corpus [[70001 1000 60000]   ; expired
                [61000 1000 60000]   ; equal? (> (- now at) ttl) → 61000-1000=60000 > 60000? false
                [1000 1000 60000]
                [70000 1000 60000]]
        cases (into {} (map-indexed
                        (fn [i [now at ttl]]
                          [(str "e_" i)
                           (str "(if (lease-expired? (record-new " lease-ty " "
                                now " " at " " ttl ")) 1 0)")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [now at ttl]] (map-indexed vector corpus)]
      (testing (pr-str [now at ttl])
        (is (= (if (> (- now at) ttl) 1 0)
               (get actual (str "e_" i))))))))
