;; W6 pure-planner oracle: murakumo.overlay.crypto packaging constants
;; vs kotoba/overlay_crypto_core.kotoba (AES-GCM seal/open stay host).

(ns murakumo.overlay-crypto-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.overlay.crypto :as crypto]))

(def port-source (slurp "kotoba/overlay_crypto_core.kotoba"))

(def export-prefix
  (str "alg-name cipher-transform nonce-bytes gcm-tag-bits "
       "field-alg field-nonce field-ciphertext "
       "strip-b64-pad sealed-alg-ok? sealed-fields-present?"))

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

(deftest packaging-constants-match-host-seal
  (let [s (compile-string-cases
           {"alg" "(alg-name)"
            "xform" "(cipher-transform)"
            "fa" "(field-alg)"
            "fn" "(field-nonce)"
            "fc" "(field-ciphertext)"})
        n (compile-i64-cases
           {"nb" "(nonce-bytes)"
            "tb" "(gcm-tag-bits)"})
        sealed (crypto/seal "shared-secret" "payload")]
    (is (= "aes-256-gcm" (get s "alg")))
    (is (= "AES/GCM/NoPadding" (get s "xform")))
    (is (= 12 (get n "nb")))
    (is (= 128 (get n "tb")))
    (is (= "alg" (get s "fa")))
    (is (= "nonce" (get s "fn")))
    (is (= "ciphertext" (get s "fc")))
    ;; host keyword name ⇔ pure string alg-name
    (is (= (keyword (get s "alg")) (:alg sealed)))
    (is (string? (get sealed (keyword (get s "fn")))))
    (is (string? (get sealed (keyword (get s "fc")))))
    (is (nil? (:payload sealed)))
    (is (= "payload" (crypto/open "shared-secret" sealed)))))

(deftest strip-b64-pad-matches-host-replace
  (let [corpus ["abc="
                "abc=="
                "abcd"
                ""
                "===="
                "a=b=c="]
        cases (into {}
                    (map-indexed
                     (fn [i s]
                       [(str "p_" i)
                        (str "(strip-b64-pad " (kotoba-literal s) ")")])
                     corpus))
        actual (compile-string-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (str/replace s "=" "")
               (get actual (str "p_" i))))))))

(deftest sealed-shape-gates
  (let [alg-ok (compile-i64-cases
                {"ok" "(sealed-alg-ok? \"aes-256-gcm\")"
                 "bad" "(sealed-alg-ok? \"aes-128-gcm\")"
                 "empty" "(sealed-alg-ok? \"\")"})
        fields (compile-i64-cases
                {"full" "(sealed-fields-present? 1 1 1)"
                 "no-alg" "(sealed-fields-present? 0 1 1)"
                 "no-n" "(sealed-fields-present? 1 0 1)"
                 "no-ct" "(sealed-fields-present? 1 1 0)"
                 "none" "(sealed-fields-present? 0 0 0)"})
        sealed (crypto/seal "k" "p")]
    (is (= 1 (get alg-ok "ok")))
    (is (= 0 (get alg-ok "bad")))
    (is (= 0 (get alg-ok "empty")))
    (is (= 1 (get fields "full")))
    (is (= 0 (get fields "no-alg")))
    (is (= 0 (get fields "no-n")))
    (is (= 0 (get fields "no-ct")))
    (is (= 0 (get fields "none")))
    (is (= 1 (get alg-ok "ok")))
    (is (= (name (:alg sealed)) "aes-256-gcm"))
    (is (every? #(contains? sealed %) [:alg :nonce :ciphertext]))))
