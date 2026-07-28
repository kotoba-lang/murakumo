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
  (str "version default-ttl claim-sub claim-scope claim-exp "
       "expired? scope-allows? signing-input "
       "digit-char nat-str i64-str encode-claims-json"))

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

(deftest encode-claims-json-matches-clj-wire
  ;; Host clj encode-claims builds fixed-order JSON then b64url-str.
  ;; Oracle owns the JSON string only (b64url stays host).
  (let [corpus [{:sub "anonymous" :scope "all" :iat 1000 :exp 2593000}
                {:sub "shinshi" :scope "chat" :iat 0 :exp 1}
                {:sub "" :scope "image" :iat 1700000000 :exp 1700003600}
                {:sub "x" :scope "y" :iat 42 :exp 99}]
        cases (into {}
                    (map-indexed
                     (fn [i {:keys [sub scope iat exp]}]
                       [(str "j_" i)
                        (str "(encode-claims-json "
                             (kotoba-literal sub) " "
                             (kotoba-literal scope) " "
                             (long iat) " " (long exp) ")")])
                     corpus))
        actual (compile-string-cases cases)]
    (doseq [[i m] (map-indexed vector corpus)]
      (let [json (get actual (str "j_" i))
            expected (str "{\"sub\":\"" (:sub m) "\",\"scope\":\"" (:scope m)
                          "\",\"iat\":" (:iat m) ",\"exp\":" (:exp m) "}")]
        (testing (pr-str m)
          (is (= expected json))
          ;; Round-trip: host encode-claims = b64url of the same JSON (clj path).
          (is (= (tok/b64url-str expected)
                 (tok/encode-claims m)))
          (is (= m (tok/decode-claims (tok/b64url-str json)))))))))
