;; W6 pure-planner oracle: murakumo.secret names + env/path policy
;; vs kotoba/secret_core.kotoba.

(ns murakumo.secret-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.secret :as secret]))

(def port-source (slurp "kotoba/secret_core.kotoba"))

(def export-prefix
  (str "token-secret-name token-secret-env service-token-name service-token-env "
       "metrics-token-name metrics-token-env quic-cert-path-name quic-cert-path-env "
       "quic-key-path-name quic-key-path-env max-env-name max-path-ref "
       "blank? ws? valid-env-var-name? valid-path-ref-unix? "
       "env-for-secret-name known-secret-name? reply-tag classify-fetched "
       "secret-error-code secret-error-message reply-is-value? "
       "class-value class-not-found class-empty class-fetch class-unknown "
       "error-code-prefix msg-empty msg-not-found msg-fetch msg-unknown "
       "pem-begin-marker"))
(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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

(deftest secret-name-constants-match
  (let [actual (compile-string-cases
                {"tn" "(token-secret-name)"
                 "te" "(token-secret-env)"
                 "sn" "(service-token-name)"
                 "se" "(service-token-env)"
                 "mn" "(metrics-token-name)"
                 "me" "(metrics-token-env)"
                 "cn" "(quic-cert-path-name)"
                 "ce" "(quic-cert-path-env)"
                 "kn" "(quic-key-path-name)"
                 "ke" "(quic-key-path-env)"})]
    (is (= secret/token-secret-name (get actual "tn")))
    (is (= secret/token-secret-env (get actual "te")))
    (is (= secret/service-token-name (get actual "sn")))
    (is (= secret/service-token-env (get actual "se")))
    (is (= secret/metrics-token-name (get actual "mn")))
    (is (= secret/metrics-token-env (get actual "me")))
    (is (= secret/quic-cert-path-name (get actual "cn")))
    (is (= secret/quic-cert-path-env (get actual "ce")))
    (is (= secret/quic-key-path-name (get actual "kn")))
    (is (= secret/quic-key-path-env (get actual "ke")))))

(deftest known-env-secrets-lookup
  (let [names [secret/token-secret-name
               secret/service-token-name
               secret/metrics-token-name
               secret/quic-cert-path-name
               secret/quic-key-path-name
               "unknown"]
        cases (into {} (map-indexed
                        (fn [i n]
                          [(str "e_" i)
                           (str "(env-for-secret-name " (kotoba-literal n) ")")])
                        names))
        known (into {} (map-indexed
                        (fn [i n]
                          [(str "k_" i)
                           (str "(if (known-secret-name? " (kotoba-literal n) ") 1 0)")])
                        names))
        actual (compile-string-cases cases)
        kn (compile-i64-cases known)]
    (doseq [[i n] (map-indexed vector names)]
      (testing n
        (is (= (or (get secret/known-env-secrets n) "")
               (get actual (str "e_" i))))
        (is (= (if (contains? secret/known-env-secrets n) 1 0)
               (get kn (str "k_" i))))))))

(deftest valid-env-var-name-matches-secret
  (let [corpus ["MURAKUMO_OVERLAY_AUTH" "tok*" "" "a/b" "has space"
                "ok_NAME-1" (apply str (repeat 257 "A"))]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "v_" i)
                           ;; Profile 5: valid-env-var-name? is :bool
                           (str "(if (valid-env-var-name? " (kotoba-literal s) ") 1 0)")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (if (secret/valid-env-var-name? s) 1 0)
               (get actual (str "v_" i))))))))

(deftest valid-path-ref-unix-matches-secret
  (let [corpus ["/var/murakumo/quic/node.cert.pem"
                "relative/cert.pem"
                "-----BEGIN CERTIFICATE-----\nabc"
                ""
                "/tmp/c.pem"
                "/x*y"
                (str "/tmp/" (apply str (repeat 1020 "a")))]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "p_" i)
                           (str "(if (valid-path-ref-unix? " (kotoba-literal s) ") 1 0)")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str (if (< (count s) 80) s (str (subs s 0 40) "…")))
        (is (= (if (secret/valid-path-ref? s) 1 0)
               (get actual (str "p_" i))))))))

(deftest classify-fetched-matches-map-fetch-shape
  (let [actual (compile-string-cases
                {"v" "(classify-fetched false false)"
                 "e" "(classify-fetched false true)"
                 "m" "(classify-fetched true false)"
                 "mb" "(classify-fetched true true)"
                 "tv" (str "(reply-tag " (kotoba-literal "value") ")")
                 "tn" (str "(reply-tag " (kotoba-literal "not-found") ")")
                 "ce" (str "(secret-error-code " (kotoba-literal "empty") ")")
                 "cn" (str "(secret-error-code " (kotoba-literal "not-found") ")")
                 "me" (str "(secret-error-message " (kotoba-literal "empty") ")")})
        iv (compile-i64-cases
            {"rv" (str "(if (reply-is-value? " (kotoba-literal "value") ") 1 0)")
             "rn" (str "(if (reply-is-value? " (kotoba-literal "empty") ") 1 0)")})]
    (is (= "value" (get actual "v") (get actual "tv")))
    (is (= "empty" (get actual "e")))
    (is (= "not-found" (get actual "m") (get actual "tn")))
    (is (= "not-found" (get actual "mb")))
    (is (= "secret/empty" (get actual "ce")))
    (is (= "secret/not-found" (get actual "cn")))
    (is (= "empty" (get actual "me")))
    (is (= 1 (get iv "rv")))
    (is (= 0 (get iv "rn")))
    (let [f (secret/map-fetch {"murakumo-token" "hmac" "empty" ""})]
      (is (= :value (:tag (f {:name "murakumo-token"}))))
      (is (= :secret/empty (:code (f {:name "empty"}))))
      (is (= :secret/not-found (:code (f {:name "missing"})))))))

(deftest secret-reply-tokens-match
  (let [s (compile-string-cases
           {"cv" "(class-value)"
            "cn" "(class-not-found)"
            "ce" "(class-empty)"
            "cf" "(class-fetch)"
            "cu" "(class-unknown)"
            "ep" "(error-code-prefix)"
            "me" "(msg-empty)"
            "mn" "(msg-not-found)"
            "mf" "(msg-fetch)"
            "mu" "(msg-unknown)"
            "pb" "(pem-begin-marker)"
            "cl" "(classify-fetched false false)"
            "sc" (str "(secret-error-code " (kotoba-literal "empty") ")")})]
    (is (= secret/class-value (get s "cv")))
    (is (= "value" (get s "cv")))
    (is (= secret/class-not-found (get s "cn")))
    (is (= "not-found" (get s "cn")))
    (is (= secret/class-empty (get s "ce")))
    (is (= secret/class-fetch (get s "cf")))
    (is (= secret/class-unknown (get s "cu")))
    (is (= secret/error-code-prefix (get s "ep")))
    (is (= "secret/" (get s "ep")))
    (is (= secret/msg-empty (get s "me")))
    (is (= secret/msg-not-found (get s "mn")))
    (is (= secret/msg-fetch (get s "mf")))
    (is (= secret/msg-unknown (get s "mu")))
    (is (= secret/pem-begin-marker (get s "pb")))
    (is (= "-----BEGIN" (get s "pb")))
    (is (= secret/class-value (get s "cl")))
    (is (= (str secret/error-code-prefix secret/class-empty) (get s "sc")))))
