;; murakumo.overlay.crypto — host-side frame sealing for murakumo-overlay.

(ns murakumo.overlay.crypto
  (:require [murakumo.identity :as identity])
  (:import [java.security SecureRandom]
           [java.util Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def ^:private secure-random (SecureRandom.))
(def ^:private gcm-tag-bits 128)
(def ^:private nonce-bytes 12)

(defn- sha256-bytes [s]
  (identity/sha256-bytes s))

(defn- b64url-encode [bytes]
  (-> (.encodeToString (Base64/getUrlEncoder) bytes)
      (.replace "=" "")))

(defn- b64url-decode [s]
  (.decode (Base64/getUrlDecoder) (str s)))

(defn- random-nonce []
  (let [bytes (byte-array nonce-bytes)]
    (.nextBytes secure-random bytes)
    bytes))

(defn- cipher [mode auth-key nonce]
  (doto (Cipher/getInstance "AES/GCM/NoPadding")
    (.init mode
           (SecretKeySpec. (sha256-bytes auth-key) "AES")
           (GCMParameterSpec. gcm-tag-bits nonce))))

(defn seal
  "Encrypt a UTF-8 payload with AES-GCM using auth-key-derived key material."
  [auth-key payload]
  (let [nonce (random-nonce)
        cipher (cipher Cipher/ENCRYPT_MODE auth-key nonce)]
    {:alg :aes-256-gcm
     :nonce (b64url-encode nonce)
     :ciphertext (b64url-encode (.doFinal cipher (.getBytes (str payload) "UTF-8")))}))

(defn open
  "Decrypt a sealed payload. Throws if authentication fails."
  [auth-key sealed]
  (let [nonce (b64url-decode (:nonce sealed))
        ciphertext (b64url-decode (:ciphertext sealed))
        cipher (cipher Cipher/DECRYPT_MODE auth-key nonce)]
    (String. (.doFinal cipher ciphertext) "UTF-8")))
