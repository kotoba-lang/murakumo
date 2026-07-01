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
