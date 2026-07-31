(ns murakumo.apikey-parity-test
  "murakumo.apikey mints keys that cloud-murakumo — the gateway that VERIFIES
  them — accepts. Compatibility here is tested, not asserted in a docstring.

  The important assertion is `mints-what-the-gateway-verifies`: it runs the real
  `cloud-murakumo.token/verify` over our output. A token that merely 'looks like'
  mk1 is worthless; the only property that matters is that the verifier says yes.

  cloud-murakumo is a sibling checkout, so the parity assertions skip (loudly)
  rather than fail when it is absent — a missing sibling is an environment gap,
  not a defect in this namespace. Everything that does not need the sibling is
  asserted unconditionally."
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.apikey :as apikey]))

(def ^:private secret "test-secret-not-a-real-key")
(def ^:private now 1700000000)

(def ^:private gateway
  "cloud-murakumo.token if the sibling checkout is on the classpath, else nil."
  (try (require 'cloud-murakumo.token)
       (find-ns 'cloud-murakumo.token)
       (catch Throwable _ nil)))

(deftest wire-format
  (testing "mk1.<payload>.<sig>, three dot-separated segments"
    (let [{:keys [ok token]} (apikey/issue {:secret secret :sub "cli" :scope "all" :now now})
          parts (clojure.string/split token #"\." 3)]
      (is ok)
      (is (= 3 (count parts)))
      (is (= "mk1" (first parts)))
      (is (every? seq parts))
      (testing "base64url — no +, / or = anywhere in the token"
        (is (not (re-find #"[+/=]" token)))))))

(deftest deterministic
  (testing "same inputs → same token (fixed claim key order)"
    (is (= (:token (apikey/issue {:secret secret :sub "a" :scope "chat" :now now}))
           (:token (apikey/issue {:secret secret :sub "a" :scope "chat" :now now})))))
  (testing "a different secret yields a different signature"
    (is (not= (:token (apikey/issue {:secret secret :sub "a" :scope "chat" :now now}))
              (:token (apikey/issue {:secret "other" :sub "a" :scope "chat" :now now}))))))

(deftest refuses-bad-input
  (testing "no secret is a refusal, not a token signed with nil"
    (let [r (apikey/issue {:secret "" :sub "x" :now now})]
      (is (false? (:ok r)))
      (is (re-find #"MURAKUMO_TOKEN_SECRET" (:error r)))))
  (testing "unknown scope is rejected at issue time, not discovered as a 401 later"
    (is (false? (:ok (apikey/issue {:secret secret :scope "root" :now now})))))
  (testing "ttl is bounded — a stateless token cannot be revoked, so it must expire"
    (is (false? (:ok (apikey/issue {:secret secret :ttl (* 365 24 60 60) :now now}))))
    (is (false? (:ok (apikey/issue {:secret secret :ttl 0 :now now}))))
    (is (true? (:ok (apikey/issue {:secret secret :ttl 60 :now now}))))))

(deftest inspect-round-trip
  (let [{:keys [token]} (apikey/issue {:secret secret :sub "sub-1" :scope "image"
                                       :ttl 3600 :now now})]
    (testing "a freshly issued key inspects as valid with its claims intact"
      (let [r (apikey/inspect {:secret secret :token token :now now})]
        (is (:valid r))
        (is (= "sub-1" (:sub r)))
        (is (= "image" (:scope r)))))
    (testing "expiry is enforced against the supplied clock"
      (is (false? (:valid (apikey/inspect {:secret secret :token token :now (+ now 3601)})))))
    (testing "a tampered signature does not verify"
      (is (false? (:valid (apikey/inspect {:secret secret :token (str token "x") :now now})))))
    (testing "the wrong secret does not verify"
      (is (false? (:valid (apikey/inspect {:secret "wrong" :token token :now now})))))
    (testing "garbage is answered, not thrown"
      (is (false? (:valid (apikey/inspect {:secret secret :token "not-a-token" :now now})))))))

;; ── the assertion that actually matters ──────────────────────────────────────

(deftest mints-what-the-gateway-verifies
  (if-not gateway
    (println "SKIP parity: cloud-murakumo not on the classpath"
             "(add ../../network-awai/cloud-murakumo/src to run it)")
    (let [verify (ns-resolve 'cloud-murakumo.token 'verify)
          sign   (ns-resolve 'cloud-murakumo.token 'sign)]
      (testing "cloud-murakumo/verify accepts a key issued here"
        (let [{:keys [token]} (apikey/issue {:secret secret :sub "parity" :scope "chat"
                                             :ttl 3600 :now now})
              cl (verify secret token now)]
          (is (some? cl) "the verifying gateway must accept our token")
          (is (= "parity" (:sub cl)))
          (is (= "chat" (:scope cl)))))
      (testing "byte-identical to what the gateway itself would mint"
        (is (= (sign secret {:sub "parity" :scope "chat" :now now :ttl 3600})
               (:token (apikey/issue {:secret secret :sub "parity" :scope "chat"
                                      :now now :ttl 3600})))))
      (testing "and we accept what the gateway mints (verification is symmetric)"
        (let [theirs (sign secret {:sub "theirs" :scope "all" :now now :ttl 3600})]
          (is (:valid (apikey/inspect {:secret secret :token theirs :now now}))))))))
