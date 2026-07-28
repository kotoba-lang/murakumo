(ns murakumo.token-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [murakumo.token :as tok]))

(def secret "test-signing-secret-0123456789")

(deftest sign-verify-roundtrip
  (testing "a freshly minted token verifies and carries its claims"
    (let [t (tok/sign secret {:sub "shinshi" :scope "chat" :now 1000 :ttl 3600})
          cl (tok/verify secret t 1500)]
      (is (str/starts-with? t "mk1."))
      (is (= "shinshi" (:sub cl)))
      (is (= "chat" (:scope cl)))
      (is (= 4600 (:exp cl))))))

(deftest rejects-tampering-and-wrong-secret
  (let [t (tok/sign secret {:sub "x" :scope "all" :now 1000 :ttl 3600})]
    (testing "wrong secret fails"
      (is (nil? (tok/verify "other-secret" t 1500))))
    (testing "tampered payload fails (sig no longer matches)"
      (let [[_ _ sig] (str/split t #"\." 3)
            forged (str "mk1." (tok/b64url-str "{\"sub\":\"admin\",\"scope\":\"all\",\"iat\":1000,\"exp\":9999999999}") "." sig)]
        (is (nil? (tok/verify secret forged 1500)))))
    (testing "garbage / wrong-version tokens fail closed"
      (is (nil? (tok/verify secret "not-a-token" 1500)))
      (is (nil? (tok/verify secret "mk9.abc.def" 1500))))))

(deftest expiry-enforced
  (let [t (tok/sign secret {:sub "x" :scope "all" :now 1000 :ttl 100})]
    (is (map? (tok/verify secret t 1050)) "valid before exp")
    (is (nil? (tok/verify secret t 1100)) "rejected at exp")
    (is (nil? (tok/verify secret t 5000)) "rejected after exp")))

(deftest scope-gate
  (is (tok/scope-allows? "all" "chat"))
  (is (tok/scope-allows? "chat" "chat"))
  (is (not (tok/scope-allows? "image" "chat"))))

(deftest live-hmac-adapter-uses-pure-wire
  (testing "encode-claims-json fixed key order (kotoba parity)"
    (is (= "{\"sub\":\"a\",\"scope\":\"chat\",\"iat\":1,\"exp\":2}"
           (tok/encode-claims-json {:sub "a" :scope "chat" :iat 1 :exp 2}))))
  (testing "signing-input + wire-token pure assembly"
    (is (= "mk1.payload" (tok/signing-input "payload")))
    (is (= "mk1.payload.sig" (tok/wire-token "payload" "sig"))))
  (testing "version-ok? / parts-present? gates"
    (is (tok/version-ok? "mk1"))
    (is (not (tok/version-ok? "mk9")))
    (is (tok/parts-present? "mk1" "p" "s"))
    (is (not (tok/parts-present? "mk1" nil "s"))))
  (testing "constant-time= rejects length mismatch"
    (is (tok/constant-time= "ab" "ab"))
    (is (not (tok/constant-time= "ab" "abc"))))
  (testing "sign still uses pure wire path end-to-end"
    (let [t (tok/sign secret {:sub "x" :scope "all" :now 10 :ttl 5})
          [v p s] (str/split t #"\." 3)]
      (is (tok/version-ok? v))
      (is (tok/parts-present? v p s))
      (is (= t (tok/wire-token p s))))))
