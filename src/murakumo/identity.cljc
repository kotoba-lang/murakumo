;; murakumo.identity — portable identity/hash/token helpers for the control plane.
;;
;; The live CLI still shells to kotoba for DID derivation. This namespace handles
;; deterministic local formatting used by multiple shells: SHA-256 hex, CIDv1
;; dag-cbor sha2-256 base32lower, and the operator bearer token shape.
;;
;; W6 product-shell authority (ADR-260728-w6-identity-credits-oracle-authority):
;; pure seed preimages / JWT templates / trim / did-from-output DELEGATE to
;; precompiled kotoba/identity_core.kotoba KIR when oracle is loadable
;; (JVM classpath or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
;; Host remains: SHA-256, base32 CID, b64url encode; mirrors stay fallback.
;; cljs: prefer Node crypto/Buffer (nbb); browser falls back to SubtleCrypto/btoa.

(ns murakumo.identity
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle])
  #?(:clj (:import (java.security MessageDigest)
                   (java.util Base64))))

(def ^:private oid :identity)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "Run oracle body; on failure use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

;; ── host crypto codecs ────────────────────────────────────────────────

#?(:clj
   (defn- utf8-bytes [s]
     (.getBytes (str s) "UTF-8")))

#?(:cljs
   (defn- utf8-bytes [s]
     (if (exists? js/Buffer)
       (js/Uint8Array. (js/Buffer.from (str s) "utf8"))
       (let [enc (js/TextEncoder.)]
         (.encode enc (str s))))))

(defn sha256-bytes
  "SHA-256 digest bytes for `s`."
  [s]
  #?(:clj (.digest (MessageDigest/getInstance "SHA-256") (utf8-bytes s))
     :cljs
     (if (exists? js/require)
       (let [crypto (js/require "crypto")
             h (.createHash crypto "sha256")]
         (.update h (str s))
         (js/Uint8Array. (.digest h)))
       ;; browser: not available synchronously — host should inject digest
       (throw (ex-info "murakumo.identity/sha256-bytes requires Node crypto on cljs"
                       {:phase :identity})))))

(defn sha256-hex
  "SHA-256 digest as lowercase hex."
  [s]
  #?(:clj
     (->> (sha256-bytes s)
          (map #(format "%02x" (bit-and (int %) 0xff)))
          (apply str))
     :cljs
     (if (exists? js/require)
       (let [crypto (js/require "crypto")]
         (.digest (.update (.createHash crypto "sha256") (str s)) "hex"))
       (->> (array-seq (sha256-bytes s))
            (map (fn [n]
                   (let [v (bit-and n 0xff)]
                     (str (when (< v 16) "0") (.toString v 16)))))
            (apply str)))))

;; ── pure seed preimages (oracle SSoT) ─────────────────────────────────

(defn node-seed
  "Deterministic per-node Ed25519 seed from the shared operator seed and node name.
   Kotoba seed-node preimage then host SHA-256 when oracle ready."
  [operator-seed node]
  (sha256-hex
   (try-oracle
    #(o 'seed-node [(str operator-seed) (str (:name node))])
    #(str operator-seed ":" (:name node)))))

(defn node-p2p-seed
  "Deterministic per-node libp2p seed from the shared operator seed and node name."
  [operator-seed node]
  (sha256-hex
   (try-oracle
    #(o 'seed-p2p [(str operator-seed) (str (:name node))])
    #(str operator-seed ":" (:name node) ":p2p"))))

(defn x25519-seed
  "Deterministic fleet x25519 seed derived from the shared operator seed."
  [operator-seed]
  (sha256-hex
   (try-oracle
    #(o 'seed-x25519 [(str operator-seed)])
    #(str operator-seed ":x25519"))))

(defn overlay-auth-key
  "Deterministic per-overlay MAC key derived from the shared operator seed.

   This is a transitional keyed-MAC material for murakumo-overlay frames; the
   later encrypted transport can replace the derivation without changing the
   cloud/driver argv contract."
  [operator-seed overlay-id]
  (sha256-hex
   (try-oracle
    #(o 'seed-overlay [(str operator-seed) (str overlay-id)])
    #(str operator-seed ":" overlay-id ":murakumo-overlay-auth"))))

(defn did-derive-argv
  "kotoba CLI argv for deriving a did:key from an Ed25519 seed.
   Kotoba did-derive-cmd (space-joined) split with limit 3 when ready."
  [kotoba seed]
  (try-oracle
   #(vec (str/split (o 'did-derive-cmd [(str kotoba) (str seed)]) #" " 3))
   (fn [] [kotoba "did-derive" seed])))

(defn did-from-output
  "Normalise kotoba did-derive stdout."
  [out]
  (try-oracle
   #(o 'did-from-output [(str out)])
   #(str/trim (str out))))

(defn did-from-command-result
  "Normalise a process result from did-derive."
  [result]
  (did-from-output (:out result)))

(defn- as-byte-seq [bytes]
  #?(:clj (seq bytes)
     :cljs (if (sequential? bytes) bytes (js/Array.prototype.slice.call bytes))))

(defn- base32-lower [bytes]
  (let [bits (mapcat (fn [byte]
                       (map #(bit-and (bit-shift-right (bit-and (int byte) 0xff) %) 1)
                            [7 6 5 4 3 2 1 0]))
                     (as-byte-seq bytes))]
    (->> (partition 5 5 (repeat 0) bits)
         (map (fn [chunk] (.charAt b32 (reduce #(+ (* %1 2) %2) 0 chunk))))
         (apply str))))

(defn graph-cid
  "KotobaCid::from_bytes(name): CIDv1 dag-cbor sha2-256, base32lower, b-prefix.
   Kotoba b-prefix when ready; multihash bytes stay host."
  [name]
  (let [digest (seq (sha256-bytes name))
        raw (concat [0x01 0x71 0x12 0x20] digest)
        prefix (try-oracle
                #(o 'cid-b-prefix [])
                (fn [] "b"))]
    (str prefix (base32-lower raw))))

(defn b64url-bytes
  "Base64url without padding for byte arrays."
  [bytes]
  #?(:clj (-> (.encodeToString (Base64/getUrlEncoder) bytes)
              (str/replace "=" ""))
     :cljs
     (let [b64 (if (exists? js/Buffer)
                 (.toString (js/Buffer.from bytes) "base64")
                 (let [bin (apply str (map #(js/String.fromCharCode %) (array-seq bytes)))]
                   (js/btoa bin)))]
       (-> b64 (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" "")))))

(defn b64url
  "Base64url without padding for a UTF-8 string."
  [s]
  (b64url-bytes (utf8-bytes s)))

(defn op-token
  "Craft the operator Bearer JWT shape kotoba checks at the control-plane edge.
   Kotoba header/payload/sig segment templates when ready; b64url join stays host."
  [did]
  (try-oracle
   #(str (b64url (o 'jwt-header-json [])) "."
         (b64url (o 'jwt-payload-json [(str did)])) "."
         (o 'op-token-sig-seg []))
   #(str (b64url "{\"alg\":\"HS256\",\"typ\":\"JWT\"}") "."
         (b64url (str "{\"sub\":\"" did "\",\"exp\":9999999999}")) "."
         "kotoba-cli-media")))

(defn graph-name-fleet
  "Common graph-cid input used by fleet/persist. Kotoba constant when ready."
  []
  (try-oracle
   #(o 'graph-name-fleet [])
   (fn [] "murakumo-fleet")))
