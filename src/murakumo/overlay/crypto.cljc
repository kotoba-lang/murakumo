;; murakumo.overlay.crypto — host-side frame sealing for murakumo-overlay.
;;
;; W6 product-shell + T6.4 remainder (oracle-required on JVM):
;; packaging constants + gates DELEGATE to kotoba/overlay_crypto_core.
;; On :clj the shipped KIR is required. cljs keeps private mirrors as
;; fail-closed fallback without preload. AES-GCM Cipher, SecureRandom nonce,
;; SHA-256 key material stay JVM host.

(ns murakumo.overlay.crypto
  (:require [clojure.string :as str]
            [murakumo.identity :as identity]
            [murakumo.kotoba.oracle :as oracle])
  #?(:clj (:import [java.security SecureRandom]
                   [java.util Base64]
                   [javax.crypto Cipher]
                   [javax.crypto.spec GCMParameterSpec SecretKeySpec])))

(def ^:private oid :overlay-crypto)

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- o
  "Call a pure export. JVM requires the oracle artifact; cljs may fall back."
  [export args]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "overlay-crypto oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export args))
     :cljs
     (if (oracle-ready?)
       (try
         (oracle/call oid export args)
         (catch :default _
           ::oracle-failed))
       ::oracle-failed)))

#?(:cljs
   (do
     (def ^:private mirror-alg-name "aes-256-gcm")
     (def ^:private mirror-cipher-transform "AES/GCM/NoPadding")
     (def ^:private mirror-nonce-bytes 12)
     (def ^:private mirror-gcm-tag-bits 128)
     (def ^:private mirror-field-alg :alg)
     (def ^:private mirror-field-nonce :nonce)
     (def ^:private mirror-field-ciphertext :ciphertext)

     (defn- cljs-str [export mirror]
       (let [v (o export [])]
         (if (= v ::oracle-failed) mirror v)))

     (defn- cljs-i64 [export mirror]
       (let [v (o export [])]
         (if (= v ::oracle-failed) mirror (oracle/i64->host v))))

     (defn- mirror-strip-b64-pad [s]
       (str/replace (str s) "=" ""))

     (defn- mirror-sealed-alg-ok? [alg]
       (or (= alg :aes-256-gcm)
           (= (str alg) mirror-alg-name)
           (= (name (keyword alg)) mirror-alg-name)))

     (defn- mirror-sealed-fields-present? [sealed]
       (boolean (and (some? (get sealed mirror-field-alg))
                     (some? (get sealed mirror-field-nonce))
                     (some? (get sealed mirror-field-ciphertext)))))))

;; ── pure packaging (kotoba SSoT; JVM requires oracle) ────────────────

(def alg-name
  #?(:clj (o 'alg-name [])
     :cljs (cljs-str 'alg-name mirror-alg-name)))

(def cipher-transform
  #?(:clj (o 'cipher-transform [])
     :cljs (cljs-str 'cipher-transform mirror-cipher-transform)))

(def nonce-bytes
  #?(:clj (oracle/i64->host (o 'nonce-bytes []))
     :cljs (cljs-i64 'nonce-bytes mirror-nonce-bytes)))

(def gcm-tag-bits
  #?(:clj (oracle/i64->host (o 'gcm-tag-bits []))
     :cljs (cljs-i64 'gcm-tag-bits mirror-gcm-tag-bits)))

(def field-alg
  (keyword #?(:clj (o 'field-alg [])
              :cljs (cljs-str 'field-alg (name mirror-field-alg)))))

(def field-nonce
  (keyword #?(:clj (o 'field-nonce [])
              :cljs (cljs-str 'field-nonce (name mirror-field-nonce)))))

(def field-ciphertext
  (keyword #?(:clj (o 'field-ciphertext [])
              :cljs (cljs-str 'field-ciphertext (name mirror-field-ciphertext)))))

(defn strip-b64-pad
  "Strip '=' padding (kotoba `strip-b64-pad`; JVM requires oracle)."
  [s]
  #?(:clj (o 'strip-b64-pad [(str s)])
     :cljs (let [v (o 'strip-b64-pad [(str s)])]
             (if (= v ::oracle-failed) (mirror-strip-b64-pad s) v))))

(defn sealed-alg-ok?
  "True when sealed map carries the expected AES-GCM alg."
  [alg]
  #?(:clj (oracle/bool->host
           (o 'sealed-alg-ok?
              [(if (keyword? alg) (name alg) (str alg))]))
     :cljs (let [v (o 'sealed-alg-ok?
                      [(if (keyword? alg) (name alg) (str alg))])]
             (if (= v ::oracle-failed)
               (mirror-sealed-alg-ok? alg)
               (oracle/bool->host v)))))

(defn- option-field
  "Product Value ABI optional sealed field: keyword → name string."
  [v]
  (oracle/option-string
   (when (some? v)
     (if (keyword? v) (name v) (str v)))))

(defn sealed-fields-present?
  "True when :alg :nonce :ciphertext are all present."
  [sealed]
  #?(:clj (oracle/bool->host
           (o 'sealed-fields-present?
              [(option-field (get sealed field-alg))
               (option-field (get sealed field-nonce))
               (option-field (get sealed field-ciphertext))]))
     :cljs (let [v (o 'sealed-fields-present?
                      [(option-field (get sealed field-alg))
                       (option-field (get sealed field-nonce))
                       (option-field (get sealed field-ciphertext))])]
             (if (= v ::oracle-failed)
               (mirror-sealed-fields-present? sealed)
               (oracle/bool->host v)))))

(defn sealed-map-ok?
  "Live open gate: fields present + alg ok."
  [sealed]
  (and (map? sealed)
       (sealed-fields-present? sealed)
       (sealed-alg-ok? (get sealed field-alg))))

;; ── host AES-GCM (JVM only) ──────────────────────────────────────────

#?(:clj
   (do
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
         (String. (.doFinal c ciphertext) "UTF-8")))))
