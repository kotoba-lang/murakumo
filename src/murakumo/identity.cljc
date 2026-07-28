;; murakumo.identity — portable identity/hash/token helpers for the control plane.
;;
;; The live CLI still shells to kotoba for DID derivation. This namespace handles
;; deterministic local formatting used by multiple shells: SHA-256 hex, CIDv1
;; dag-cbor sha2-256 base32lower, and the operator bearer token shape.
;;
;; W6 product-shell authority: pure seed preimages, JWT templates, trim, and
;; display prefixes DELEGATE to precompiled kotoba/identity_core.kotoba KIR on
;; JVM. SHA-256 / base32 / base64url stay host crypto.

(ns murakumo.identity
  (:require [clojure.string :as str]
            #?(:clj [murakumo.kotoba.oracle :as oracle])
            #?(:cljs [goog.crypt :as gcrypt])
            #?(:cljs [goog.crypt.base64 :as gbase64]))
  #?(:clj (:import (java.security MessageDigest)
                   (java.util Base64))))

(def ^:private oid :identity)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

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
  "Deterministic per-node Ed25519 seed from the shared operator seed and node name.
   JVM: preimage via kotoba `seed-node`."
  [operator-seed node]
  (sha256-hex #?(:clj (o 'seed-node [(str operator-seed) (str (:name node))])
                 :cljs (str operator-seed ":" (:name node)))))

(defn node-p2p-seed
  "Deterministic per-node libp2p seed from the shared operator seed and node name.
   JVM: preimage via kotoba `seed-p2p`."
  [operator-seed node]
  (sha256-hex #?(:clj (o 'seed-p2p [(str operator-seed) (str (:name node))])
                 :cljs (str operator-seed ":" (:name node) ":p2p"))))

(defn x25519-seed
  "Deterministic fleet x25519 seed derived from the shared operator seed.
   JVM: preimage via kotoba `seed-x25519`."
  [operator-seed]
  (sha256-hex #?(:clj (o 'seed-x25519 [(str operator-seed)])
                 :cljs (str operator-seed ":x25519"))))

(defn overlay-auth-key
  "Deterministic per-overlay MAC key derived from the shared operator seed.

   This is a transitional keyed-MAC material for murakumo-overlay frames; the
   later encrypted transport can replace the derivation without changing the
   cloud/driver argv contract.
   JVM: preimage via kotoba `seed-overlay`."
  [operator-seed overlay-id]
  (sha256-hex #?(:clj (o 'seed-overlay [(str operator-seed) (str overlay-id)])
                 :cljs (str operator-seed ":" overlay-id ":murakumo-overlay-auth"))))

(defn did-derive-argv
  "kotoba CLI argv for deriving a did:key from an Ed25519 seed."
  [kotoba seed]
  [kotoba "did-derive" seed])

(defn did-from-output
  "Normalise kotoba did-derive stdout. JVM: kotoba `did-from-output` (trim)."
  [out]
  #?(:clj (o 'did-from-output [(str out)])
     :cljs (str/trim (str out))))

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
  "KotobaCid::from_bytes(name): CIDv1 dag-cbor sha2-256, base32lower, b-prefix.
   JVM: display prefix via kotoba `cid-b-prefix`; multihash bytes stay host."
  [name]
  (let [digest (seq (sha256-bytes name))
        raw (concat [0x01 0x71 0x12 0x20] digest)
        prefix #?(:clj (o 'cid-b-prefix [])
                  :cljs "b")]
    (str prefix (base32-lower raw))))

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
  "Craft the operator Bearer JWT shape kotoba checks at the control-plane edge.
   JVM: header/payload/sig-seg templates via oracle; b64url join stays host."
  [did]
  #?(:clj
     (str (b64url (o 'jwt-header-json [])) "."
          (b64url (o 'jwt-payload-json [(str did)])) "."
          (o 'op-token-sig-seg []))
     :cljs
     (str (b64url "{\"alg\":\"HS256\",\"typ\":\"JWT\"}") "."
          (b64url (str "{\"sub\":\"" did "\",\"exp\":9999999999}")) "."
          "kotoba-cli-media")))
