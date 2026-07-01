;; murakumo.identity — portable identity/hash/token helpers for the control plane.
;;
;; The live CLI still shells to kotoba for DID derivation. This namespace handles
;; deterministic local formatting used by multiple shells: SHA-256 hex, CIDv1
;; dag-cbor sha2-256 base32lower, and the operator bearer token shape.

(ns murakumo.identity
  (:require [clojure.string :as str]
            #?(:cljs [goog.crypt :as gcrypt])
            #?(:cljs [goog.crypt.base64 :as gbase64]))
  #?(:clj (:import (java.security MessageDigest)
                   (java.util Base64))))

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

(defn- utf8-bytes [s]
  #?(:clj (.getBytes (str s) "UTF-8")
     :cljs (gcrypt/stringToUtf8ByteArray (str s))))

(defn sha256-bytes
  "SHA-256 digest bytes for `s`."
  [s]
  #?(:clj (.digest (MessageDigest/getInstance "SHA-256") (utf8-bytes s))
     :cljs (let [sha (goog.crypt.Sha256.)]
             (.update sha (utf8-bytes s))
             (.digest sha))))

(defn sha256-hex
  "SHA-256 digest as lowercase hex."
  [s]
  (->> (sha256-bytes s)
	       (map #(let [n (bit-and (int %) 0xff)]
	               #?(:clj (format "%02x" n)
	                  :cljs (str (when (< n 16) "0") (.toString n 16)))))
	       (apply str)))

(defn node-seed
  "Deterministic per-node Ed25519 seed from the shared operator seed and node name."
  [operator-seed node]
  (sha256-hex (str operator-seed ":" (:name node))))

(defn node-p2p-seed
  "Deterministic per-node libp2p seed from the shared operator seed and node name."
  [operator-seed node]
  (sha256-hex (str operator-seed ":" (:name node) ":p2p")))

(defn x25519-seed
  "Deterministic fleet x25519 seed derived from the shared operator seed."
  [operator-seed]
  (sha256-hex (str operator-seed ":x25519")))

(defn overlay-auth-key
  "Deterministic per-overlay MAC key derived from the shared operator seed.

   This is a transitional keyed-MAC material for murakumo-overlay frames; the
   later encrypted transport can replace the derivation without changing the
   cloud/driver argv contract."
  [operator-seed overlay-id]
  (sha256-hex (str operator-seed ":" overlay-id ":murakumo-overlay-auth")))

(defn did-derive-argv
  "kotoba CLI argv for deriving a did:key from an Ed25519 seed."
  [kotoba seed]
  [kotoba "did-derive" seed])

(defn did-from-output
  "Normalise kotoba did-derive stdout."
  [out]
  (str/trim (str out)))

(defn did-from-command-result
  "Normalise a process result from did-derive."
  [result]
  (did-from-output (:out result)))

(defn- base32-lower [bytes]
  (let [bits (mapcat (fn [byte]
                       (map #(bit-and (bit-shift-right (bit-and (int byte) 0xff) %) 1)
                            [7 6 5 4 3 2 1 0]))
                     bytes)]
    (->> (partition 5 5 (repeat 0) bits)
         (map (fn [chunk] (.charAt b32 (reduce #(+ (* %1 2) %2) 0 chunk))))
         (apply str))))

(defn graph-cid
  "KotobaCid::from_bytes(name): CIDv1 dag-cbor sha2-256, base32lower, b-prefix."
  [name]
  (let [digest (seq (sha256-bytes name))
        raw (concat [0x01 0x71 0x12 0x20] digest)]
    (str "b" (base32-lower raw))))

(defn b64url-bytes
  "Base64url without padding for byte arrays."
  [bytes]
  #?(:clj (-> (.encodeToString (Base64/getUrlEncoder) bytes)
              (str/replace "=" ""))
     :cljs (-> (gbase64/encodeByteArray bytes true)
               (str/replace "=" ""))))

(defn b64url
  "Base64url without padding for a UTF-8 string."
  [s]
  (b64url-bytes (utf8-bytes s)))

(defn op-token
  "Craft the operator Bearer JWT shape kotoba checks at the control-plane edge."
  [did]
  (str (b64url "{\"alg\":\"HS256\",\"typ\":\"JWT\"}") "."
       (b64url (str "{\"sub\":\"" did "\",\"exp\":9999999999}")) "."
       "kotoba-cli-media"))
