;; W6 pure-planner oracle: murakumo.token claims/scope helpers
;; vs kotoba/token_core.kotoba (Product Value ABI v1 — options, no has-*).

(ns murakumo.token-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.token :as tok]))

(def port-source (slurp "kotoba/token_core.kotoba"))

(def export-prefix
  (str "version default-ttl claim-sub claim-scope claim-exp expired? scope-allows? signing-input "
       "encode-claims-json wire-token "
       "version-ok? parts-present? constant-time-eq ct-scan "
       "default-sub default-scope scope-all jwt-seg-sep wire-sep "
       "json-sub-prefix json-scope-mid json-iat-mid json-exp-mid json-close"))

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- opt-string-form [s]
  (if (nil? s)
    "(option-none-of [:option :string])"
    (str "(option-some-of [:option :string] " (kotoba-literal s) ")")))

(defn- opt-i64-form [n]
  (if (nil? n)
    "(option-none-of [:option :i64])"
    (str "(option-some-of [:option :i64] " (long n) ")")))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

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

(deftest version-and-default-ttl-match
  (let [s (compile-string-cases
           {"v" "(version)"
            "ds" "(default-sub)"
            "dsc" "(default-scope)"
            "sa" "(scope-all)"
            "js" "(jwt-seg-sep)"
            "ws" "(wire-sep)"
            "jp" "(json-sub-prefix)"
            "jm" "(json-scope-mid)"
            "ji" "(json-iat-mid)"
            "je" "(json-exp-mid)"
            "jc" "(json-close)"})
        n (compile-i64-cases {"ttl" "(default-ttl)"})]
    (is (= tok/version (get s "v")))
    (is (= "mk1" (get s "v")))
    (is (= tok/default-sub (get s "ds")))
    (is (= "anonymous" (get s "ds")))
    (is (= tok/default-scope (get s "dsc")))
    (is (= "all" (get s "dsc")))
    (is (= tok/scope-all (get s "sa")))
    (is (= tok/jwt-seg-sep (get s "js")))
    (is (= "." (get s "js")))
    (is (= tok/wire-sep (get s "ws")))
    (is (= tok/json-sub-prefix (get s "jp")))
    (is (= tok/json-scope-mid (get s "jm")))
    (is (= tok/json-iat-mid (get s "ji")))
    (is (= tok/json-exp-mid (get s "je")))
    (is (= tok/json-close (get s "jc")))
    (is (= 2592000 (get n "ttl")))
    (is (= tok/default-ttl (get n "ttl")))
    (is (= (:exp (tok/claims {:now 0})) (get n "ttl")))
    (is (= (str tok/version tok/jwt-seg-sep "PAY") (tok/signing-input "PAY")))
    (is (= (str tok/version tok/wire-sep "PAY" tok/wire-sep "SIG")
           (tok/wire-token "PAY" "SIG")))))

(deftest claim-defaults-match-claims
  (let [corpus [[nil nil]
                ["shinshi" "chat"]
                ["" "image"]
                ["x" nil]]
        sub-cases (into {}
                        (map-indexed
                         (fn [i [sub _]]
                           [(str "sub_" i)
                            (str "(claim-sub " (opt-string-form sub) ")")])
                         corpus))
        scope-cases (into {}
                          (map-indexed
                           (fn [i [_ scope]]
                             [(str "sc_" i)
                              (str "(claim-scope " (opt-string-form scope) ")")])
                           corpus))
        subs (compile-string-cases sub-cases)
        scopes (compile-string-cases scope-cases)]
    (doseq [[i [sub scope]] (map-indexed vector corpus)]
      (let [cl (tok/claims {:sub sub :scope scope :now 1000 :ttl 1})]
        (testing (pr-str [sub scope])
          (is (= (:sub cl) (get subs (str "sub_" i))))
          (is (= (:scope cl) (get scopes (str "sc_" i)))))))))

(deftest claim-exp-matches-claims
  (let [corpus [[1000 3600] [1000 nil] [0 1] [1700000000 nil]]
        cases (into {}
                    (map-indexed
                     (fn [i [now ttl]]
                       [(str "e_" i)
                        (str "(claim-exp " (long now) " " (opt-i64-form ttl) ")")])
                     corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [now ttl]] (map-indexed vector corpus)]
      (let [cl (tok/claims {:now now :ttl ttl})]
        (testing (pr-str [now ttl])
          (is (= (:exp cl) (get actual (str "e_" i)))))))))

(deftest expired-matches-token
  (let [corpus [[{:exp 1100} 1050]
                [{:exp 1100} 1100]
                [{:exp 1100} 5000]
                [{} 1000]
                [{:exp 0} 0]]
        cases (into {}
                    (map-indexed
                     (fn [i [cl now]]
                       (let [exp-form (if (contains? cl :exp)
                                        (opt-i64-form (:exp cl))
                                        (opt-i64-form nil))]
                         [(str "x_" i)
                          (str "(if (expired? " exp-form " " (long now) ") 1 0)")]))
                     corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [cl now]] (map-indexed vector corpus)]
      (testing (pr-str [cl now])
        (is (= (if (tok/expired? cl now) 1 0)
               (get actual (str "x_" i))))))))

(deftest scope-allows-matches-token
  (let [corpus [["all" "chat"]
                ["chat" "chat"]
                ["image" "chat"]
                ["all" "image"]
                ["x" "x"]
                ["x" "y"]]
        cases (into {}
                    (map-indexed
                     (fn [i [s r]]
                       [(str "sa_" i)
                        (str "(if (scope-allows? " (kotoba-literal s) " " (kotoba-literal r) ") 1 0)")])
                     corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [s r]] (map-indexed vector corpus)]
      (testing (pr-str [s r])
        (is (= (if (tok/scope-allows? s r) 1 0)
               (get actual (str "sa_" i))))))))

(deftest encode-claims-json-and-wire-match
  (let [cl (tok/claims {:sub "a" :scope "all" :now 10 :ttl 5})
        json (tok/encode-claims-json cl)
        cases (compile-string-cases
               {"j" (str "(encode-claims-json "
                         (kotoba-literal (:sub cl)) " "
                         (kotoba-literal (:scope cl)) " "
                         (:iat cl) " " (:exp cl) ")")
                "w" (str "(wire-token " (kotoba-literal "pay") " " (kotoba-literal "sig") ")")
                "si" (str "(signing-input " (kotoba-literal "pay") ")")})]
    (is (= json (get cases "j")))
    (is (= (tok/wire-token "pay" "sig") (get cases "w")))
    (is (= (tok/signing-input "pay") (get cases "si")))))

(deftest constant-time-eq-matches
  (let [cases (compile-i64-cases
               {"eq" "(constant-time-eq \"abc\" \"abc\")"
                "ne" "(constant-time-eq \"abc\" \"abd\")"
                "len" "(constant-time-eq \"ab\" \"abc\")"})]
    (is (= 1 (get cases "eq")))
    (is (= 0 (get cases "ne")))
    (is (= 0 (get cases "len")))
    (is (true? (tok/constant-time= "abc" "abc")))
    (is (false? (tok/constant-time= "abc" "abd")))))
