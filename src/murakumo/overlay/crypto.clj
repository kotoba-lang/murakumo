;; murakumo.overlay.crypto — host-side frame sealing for murakumo-overlay.
;;
;; W6 product-shell: packaging constants + gates DELEGATE to
;; kotoba/overlay_crypto_core.kotoba (resources/murakumo/oracle/overlay_crypto_core.kir.edn).
;; AES-GCM Cipher, SecureRandom nonce, SHA-256 key material stay host.

(ns murakumo.overlay.crypto
  (:require [clojure.string :as str]
            [murakumo.identity :as identity]
            [murakumo.kotoba.oracle :as oracle])
  (:import [java.security SecureRandom]
           [java.util Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def ^:private oid :overlay-crypto)

(defn- o [export args]
  (oracle/call oid export args))

;; ── pure packaging (kotoba SSoT) ─────────────────────────────────────

(def alg-name (o 'alg-name []))
(def cipher-transform (o 'cipher-transform []))
(def nonce-bytes (long (o 'nonce-bytes [])))
(def gcm-tag-bits (long (o 'gcm-tag-bits [])))
(def field-alg (keyword (o 'field-alg [])))
(def field-nonce (keyword (o 'field-nonce [])))
(def field-ciphertext (keyword (o 'field-ciphertext [])))

(defn strip-b64-pad
  "Strip '=' padding (kotoba `strip-b64-pad`)."
  [s]
  (o 'strip-b64-pad [(str s)]))

(defn sealed-alg-ok?
  "True when sealed map carries the expected AES-GCM alg."
  [alg]
  (= 1 (o 'sealed-alg-ok?
          [(if (keyword? alg) (name alg) (str alg))])))

(defn sealed-fields-present?
  "True when :alg :nonce :ciphertext are all present."
  [sealed]
  (= 1 (o 'sealed-fields-present?
          [(long (if (some? (get sealed field-alg)) 1 0))
           (long (if (some? (get sealed field-nonce)) 1 0))
           (long (if (some? (get sealed field-ciphertext)) 1 0))])))

(defn sealed-map-ok?
  "Live open gate: fields present + alg ok."
  [sealed]
  (and (map? sealed)
       (sealed-fields-present? sealed)
       (sealed-alg-ok? (get sealed field-alg))))

;; ── host AES-GCM ────────────────────────────────────────────────────

(def ^:private secure-random (SecureRandom.))

(defn- sha256-bytes [s]
  (identity/sha256-bytes s))

(defn- b64url-encode [bytes]
  (strip-b64-pad (.encodeToString (Base64/getUrlEncoder) bytes)))

(defn- b64url-decode [s]
  (.decode (Base64/getUrlDecoder) (str s)))

(defn- random-nonce []
  (let [bytes (byte-array nonce-bytes)]
    (.nextBytes secure-random bytes)
    bytes))

(defn- cipher [mode auth-key nonce]
  (doto (Cipher/getInstance cipher-transform)
    (.init mode
           (SecretKeySpec. (sha256-bytes auth-key) "AES")
           (GCMParameterSpec. gcm-tag-bits nonce))))

(defn seal
  "Encrypt a UTF-8 payload with AES-GCM using auth-key-derived key material."
  [auth-key payload]
  (let [nonce (random-nonce)
        c (cipher Cipher/ENCRYPT_MODE auth-key nonce)]
    {field-alg :aes-256-gcm
     field-nonce (b64url-encode nonce)
     field-ciphertext (b64url-encode (.doFinal c (.getBytes (str payload) "UTF-8")))}))

(defn open
  "Decrypt a sealed payload. Throws if packaging gate or authentication fails."
  [auth-key sealed]
  (when-not (sealed-map-ok? sealed)
    (throw (ex-info "invalid sealed frame packaging"
                    {:alg (get sealed field-alg)
                     :has-nonce (contains? sealed field-nonce)
                     :has-ciphertext (contains? sealed field-ciphertext)})))
  (let [nonce (b64url-decode (get sealed field-nonce))
        ciphertext (b64url-decode (get sealed field-ciphertext))
        c (cipher Cipher/DECRYPT_MODE auth-key nonce)]
    (String. (.doFinal c ciphertext) "UTF-8")))
