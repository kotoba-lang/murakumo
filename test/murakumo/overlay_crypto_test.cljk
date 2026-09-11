;; murakumo.overlay-crypto-test — host frame sealing checks.

(ns murakumo.overlay-crypto-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.crypto :as crypto]))

(deftest aes-gcm-seals-and-opens-payload
  (let [sealed (crypto/seal "shared-secret" "payload")]
    (is (= :aes-256-gcm (:alg sealed)))
    (is (string? (:nonce sealed)))
    (is (string? (:ciphertext sealed)))
    (is (nil? (:payload sealed)))
    (is (= "payload" (crypto/open "shared-secret" sealed)))))

(deftest aes-gcm-rejects-wrong-key
  (let [sealed (crypto/seal "shared-secret" "payload")]
    (is (thrown? Exception (crypto/open "wrong-secret" sealed)))))

(deftest live-aes-adapter-packaging-gates
  (is (= "aes-256-gcm" crypto/alg-name))
  (is (= "AES/GCM/NoPadding" crypto/cipher-transform))
  (is (= 12 crypto/nonce-bytes))
  (is (= 128 crypto/gcm-tag-bits))
  (is (= "abc" (crypto/strip-b64-pad "abc==")))
  (let [sealed (crypto/seal "k" "p")]
    (is (crypto/sealed-alg-ok? (:alg sealed)))
    (is (crypto/sealed-fields-present? sealed))
    (is (crypto/sealed-map-ok? sealed))
    (is (not (crypto/sealed-map-ok? (dissoc sealed :nonce))))
    (is (thrown? Exception (crypto/open "k" (dissoc sealed :ciphertext))))))
