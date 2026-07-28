;; W6 pure-planner oracle: murakumo.token claims/scope helpers
;; vs kotoba/token_core.kotoba.

(ns murakumo.token-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.token :as tok]))

(def port-source (slurp "kotoba/token_core.kotoba"))

(def export-prefix
  (str "version default-ttl claim-sub claim-scope claim-exp expired? scope-allows? signing-input "
       "digit-char nat-str i64-str encode-claims-json wire-token "
       "version-ok? parts-present? constant-time-eq ct-scan"))

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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
  (let [s (compile-string-cases {"v" "(version)"})
        n (compile-i64-cases {"ttl" "(default-ttl)"})]
    (is (= tok/version (get s "v")))
    (is (= 2592000 (get n "ttl")))
    (is (= (:exp (tok/claims {:now 0})) (get n "ttl")))))

(deftest claim-defaults-match-claims
  (let [corpus [[nil nil]
                ["shinshi" "chat"]
                ["" "image"]
                ["x" nil]]
        sub-cases (into {}
                        (map-indexed
                         (fn [i [sub _]]
                           (let [has (if (some? sub) 1 0)
                                 s (or sub "")]
                             [(str "sub_" i)
                              (str "(claim-sub " has " " (kotoba-literal s) ")")]))
                         corpus))
        scope-cases (into {}
                          (map-indexed
                           (fn [i [_ scope]]
                             (let [has (if (some? scope) 1 0)
                                   s (or scope "")]
                               [(str "sc_" i)
                                (str "(claim-scope " has " " (kotoba-literal s) ")")]))
                           corpus))
        subs (compile-string-cases sub-cases)
        scopes (compile-string-cases scope-cases)]
    (doseq [[i [sub scope]] (map-indexed vector corpus)]
      (let [cl (tok/claims {:sub sub :scope scope :now 1000 :ttl 1})]
        (testing (pr-str [sub scope])
          (is (= (:sub cl) (get subs (str "sub_" i))))
          (is (= (:scope cl) (get scopes (str "sc_" i)))))))))

(deftest claim-exp-matches-claims
  (let [corpus [[1000 3600] [1000 nil] [0 1] [1700000000 -1]]
        ;; -1 in last row means "pass -1 to kotoba as absent" and compare to nil ttl
        cases (into {}
                    (map-indexed
                     (fn [i [now ttl]]
                       (let [k-ttl (if (nil? ttl) -1 (long ttl))
                             ;; corpus last uses -1 as explicit absent sentinel for kotoba only
                             k-ttl (if (= k-ttl -1) -1 k-ttl)
                             now' (long now)]
                         [(str "e_" i) (str "(claim-exp " now' " " k-ttl ")")]))
                     corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [now ttl]] (map-indexed vector corpus)]
      (let [ttl' (if (and (some? ttl) (neg? ttl)) nil ttl)
            cl (tok/claims {:now now :ttl ttl'})]
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
                       (let [has (if (contains? cl :exp) 1 0)
                             exp (long (or (:exp cl) 0))]
                         [(str "x_" i)
                          (str "(expired? " has " " exp " " (long now) ")")]))
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
                        (str "(scope-allows? " (kotoba-literal s) " " (kotoba-literal r) ")")])
                     corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [s r]] (map-indexed vector corpus)]
      (testing (pr-str [s r])
        (is (= (if (tok/scope-allows? s r) 1 0)
               (get actual (str "sa_" i))))))))

(deftest signing-input-matches-wire-prefix
  (let [payload "abcPAYLOAD"
        actual (compile-string-cases
                {"si" (str "(signing-input " (kotoba-literal payload) ")")})]
    (is (= (str tok/version "." payload)
           (get actual "si")))))


(deftest encode-claims-and-wire-token
  (let [cl (tok/claims {:sub "shinshi" :scope "chat" :now 1000 :ttl 60})
        jvm-json (str "{\"sub\":\"" (:sub cl) "\",\"scope\":\"" (:scope cl)
                      "\",\"iat\":" (:iat cl) ",\"exp\":" (:exp cl) "}")
        actual (compile-string-cases
                {"ej" (str "(encode-claims-json "
                           (kotoba-literal (:sub cl)) " "
                           (kotoba-literal (:scope cl)) " "
                           (:iat cl) " " (:exp cl) ")")
                 "wt" (str "(wire-token " (kotoba-literal "PAY") " "
                           (kotoba-literal "SIG") ")")
                 "si" (str "(signing-input " (kotoba-literal "PAY") ")")})]
    (is (= jvm-json (get actual "ej")))
    (is (= "mk1.PAY.SIG" (get actual "wt")))
    (is (= "mk1.PAY" (get actual "si")))))

(deftest constant-time-eq-and-parts
  (let [ct @(var murakumo.token/constant-time=)
        actual (compile-i64-cases
                {"eq" (str "(constant-time-eq " (kotoba-literal "abc") " "
                           (kotoba-literal "abc") ")")
                 "ne" (str "(constant-time-eq " (kotoba-literal "abc") " "
                           (kotoba-literal "abd") ")")
                 "len" (str "(constant-time-eq " (kotoba-literal "ab") " "
                            (kotoba-literal "abc") ")")
                 "vok" (str "(version-ok? " (kotoba-literal "mk1") ")")
                 "vbad" (str "(version-ok? " (kotoba-literal "mk2") ")")
                 "pp1" "(parts-present? 1 1 1)"
                 "pp0" "(parts-present? 1 1 0)"})]
    (is (= 1 (get actual "eq")))
    (is (= 0 (get actual "ne")))
    (is (= 0 (get actual "len")))
    (is (= (if (ct "abc" "abc") 1 0) (get actual "eq")))
    (is (= (if (ct "abc" "abd") 1 0) (get actual "ne")))
    (is (= 1 (get actual "vok")))
    (is (= 0 (get actual "vbad")))
    (is (= 1 (get actual "pp1")))
    (is (= 0 (get actual "pp0")))))
