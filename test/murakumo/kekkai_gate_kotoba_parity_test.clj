;; W6 pure-planner oracle: byte/string equality between murakumo.kekkai.gate
;; (cljc host oracle) and kotoba/kekkai_gate_core.kotoba (portable guest).

(ns murakumo.kekkai-gate-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.kekkai.gate :as gate]))

(def port-source (slurp "kotoba/kekkai_gate_core.kotoba"))

(def ^:private denial-ty
  "[:record :kekkai/denial [[:name :string] [:status :string]]]")

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-cases
  "Compile port + zero-arg case fns; return {case-name -> string result}."
  [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        src (str port-source "\n" (str/join "\n" defs))
        ;; merge exports for case fns
        src (str/replace-first
             src
             #"\(:export \[[^\]]+\]\)"
             (str "(:export [default-ledger-path strip-trailing-newlines parse-status-out "
                  "authorized? denial-line-of default-kekkai-dir-under "
                  "cli-bin cli-alias-flag cli-main-flag cli-main-ns "
                  "status-authorized status-unknown "
                  "denial-prefix denial-mid denial-suffix kekkai-dir-suffix "
                  (str/join " " (map first cases)) "])"))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [[name _]] [name (ir/execute kir (symbol name) [])]) cases))))

(deftest default-ledger-path-matches
  (let [actual (compile-cases {"case_default" "(default-ledger-path)"})]
    (is (= (gate/ledger-path (constantly nil))
           (get actual "case_default")))))

(deftest parse-status-out-matches-gate-parse-status
  (let [corpus ["authorized\n" "pending\n" "revoked\n" "" "unknown\n"]
        cases (into {} (map-indexed (fn [i out]
                                      [(str "ps_" i)
                                       (str "(parse-status-out " (kotoba-literal out) ")")])
                                    corpus))
        actual (compile-cases cases)]
    (doseq [[i out] (map-indexed vector corpus)]
      (testing (pr-str out)
        (is (= (gate/parse-status {:exit 0 :out out})
               (get actual (str "ps_" i))))))))

(deftest denial-line-of-matches-gate-denial-line
  (let [nodes [{:name "judah" :kekkai/status "pending"}
               {:name "simeon" :kekkai/status "unknown"}
               {:name "naphtali" :kekkai/status "revoked"}]
        cases (into {} (map-indexed
                        (fn [i n]
                          [(str "dl_" i)
                           (str "(denial-line-of (record-new " denial-ty " "
                                (kotoba-literal (:name n)) " "
                                (kotoba-literal (:kekkai/status n)) "))")])
                        nodes))
        actual (compile-cases cases)]
    (doseq [[i n] (map-indexed vector nodes)]
      (testing (:name n)
        (is (= (gate/denial-line n)
               (get actual (str "dl_" i))))))))

(deftest default-kekkai-dir-under-matches
  (let [home "/home/jun"
        actual (compile-cases
                {"case_dir" (str "(default-kekkai-dir-under " (kotoba-literal home) ")")})]
    (is (= (gate/default-kekkai-dir home)
           (get actual "case_dir")))))

(deftest authorized-predicate-matches-partition-rule
  (let [statuses ["authorized" "pending" "unknown" "revoked"]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "az_" i)
                           (str "(if (authorized? " (kotoba-literal s) ") \"yes\" \"no\")")])
                        statuses))
        actual (compile-cases cases)]
    (doseq [[i s] (map-indexed vector statuses)]
      (testing s
        (is (= (if (= gate/status-authorized s) "yes" "no")
               (get actual (str "az_" i))))))))


(deftest cli-argv-fragments-parity
  (let [actual (compile-cases
                {"bin" "(cli-bin)"
                 "alias" "(cli-alias-flag)"
                 "mflag" "(cli-main-flag)"
                 "mainns" "(cli-main-ns)"})]
    (is (= gate/cli-bin (get actual "bin")))
    (is (= "clojure" (get actual "bin")))
    (is (= gate/cli-alias-flag (get actual "alias")))
    (is (= "-M" (get actual "alias")))
    (is (= gate/cli-main-flag (get actual "mflag")))
    (is (= "-m" (get actual "mflag")))
    (is (= gate/cli-main-ns (get actual "mainns")))
    (is (= "kekkai.cli" (get actual "mainns")))
    (is (= [gate/cli-bin gate/cli-alias-flag gate/cli-main-flag gate/cli-main-ns
            "led.edn" "n1"]
           (gate/cli-argv "led.edn" "n1")))))

(deftest kekkai-denial-tokens-match
  (let [actual (compile-cases
                {"sa" "(status-authorized)"
                 "su" "(status-unknown)"
                 "dp" "(denial-prefix)"
                 "dm" "(denial-mid)"
                 "ds" "(denial-suffix)"
                 "ks" "(kekkai-dir-suffix)"
                 "dl" (str "(denial-line-of (record-new " denial-ty " "
                           (kotoba-literal "judah") " "
                           (kotoba-literal "pending") "))")})]
    (is (= gate/status-authorized (get actual "sa")))
    (is (= "authorized" (get actual "sa")))
    (is (= gate/status-unknown (get actual "su")))
    (is (= "unknown" (get actual "su")))
    (is (= gate/denial-prefix (get actual "dp")))
    (is (= "[kekkai] " (get actual "dp")))
    (is (= gate/denial-mid (get actual "dm")))
    (is (= gate/denial-suffix (get actual "ds")))
    (is (= gate/kekkai-dir-suffix (get actual "ks")))
    (is (= (str gate/denial-prefix "judah" gate/denial-mid "pending" gate/denial-suffix)
           (get actual "dl")))
    (is (= (get actual "dl")
           (gate/denial-line {:name "judah" :kekkai/status "pending"})))))
