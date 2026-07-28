(ns murakumo.token
  "murakumo inference access tokens — stateless, HMAC-SHA256 signed capability
  tokens the operator mints from the CLI and the gateway verifies without any
  shared state (no KV, no DB round-trip).

  Wire format (v1):

      mk1.<payloadSeg>.<sig>
      payloadSeg = b64url(json {\"sub\":…, \"scope\":…, \"iat\":N, \"exp\":N})
      sig        = b64url( HMAC-SHA256(secret, \"mk1.\" + payloadSeg) )

  Pure wire/claims helpers mirror kotoba/token_core.kotoba (encode-claims-json,
  signing-input, wire-token, constant-time=). HMAC-SHA256 + base64url codecs
  stay host (javax on JVM/bb, WebCrypto on cljs).

  The signing secret (MURAKUMO_TOKEN_SECRET) is the operator's; it lives in the
  CLI's environment (to mint) and as a Worker secret (to verify)."
  (:require [clojure.string :as str]
            #?(:cljs [goog.crypt.base64 :as gb64]))
  #?(:clj (:import [javax.crypto Mac]
                   [javax.crypto.spec SecretKeySpec]
                   [java.util Base64]
                   [java.nio.charset StandardCharsets])))

(def ^:const version "mk1")
(def ^:const default-ttl 2592000)

;; ── base64url (no padding) over raw bytes — host codec ──────────────

(defn b64url-bytes [bytes]
  #?(:clj  (-> (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))
     :cljs (-> (gb64/encodeByteArray bytes) (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" ""))))

(defn- b64url-decode->str [s]
  #?(:clj  (String. (.decode (Base64/getUrlDecoder) ^String s) StandardCharsets/UTF_8)
     :cljs (let [b64 (-> s (str/replace "-" "+") (str/replace "_" "/"))]
             (gb64/decodeString b64))))

(defn b64url-str [s]
  #?(:clj  (b64url-bytes (.getBytes ^String s StandardCharsets/UTF_8))
     :cljs (-> (gb64/encodeString s) (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" ""))))

;; ── pure claims / wire (parity: kotoba/token_core.kotoba) ────────────

(defn claims
  "Build the token claim map. `now`/`ttl` in epoch seconds (caller supplies the
  clock — this ns is pure). Scope is a plain string like \"chat\" | \"image\" |
  \"all\"; the gateway decides what each scope may reach."
  [{:keys [sub scope now ttl]}]
  {:sub (str (or sub "anonymous"))
   :scope (str (or scope "all"))
   :iat (long now)
   :exp (long (+ now (or ttl default-ttl)))})

(defn encode-claims-json
  "Fixed-key JSON matching kotoba `encode-claims-json` on JVM and cljs."
  [{:keys [sub scope iat exp]}]
  (str "{\"sub\":\"" sub "\",\"scope\":\"" scope
       "\",\"iat\":" iat ",\"exp\":" exp "}"))

(defn encode-claims
  "b64url of fixed-key claims JSON (host b64 codec)."
  [m]
  (b64url-str (encode-claims-json m)))

(defn decode-claims [payload-seg]
  (try
    (let [s (b64url-decode->str payload-seg)
          m #?(:clj (let [g (fn [re] (some-> (re-find re s) second))]
                      {:sub (g #"\"sub\":\"([^\"]*)\"")
                       :scope (g #"\"scope\":\"([^\"]*)\"")
                       :iat (some-> (g #"\"iat\":(\d+)") (Long/parseLong))
                       :exp (some-> (g #"\"exp\":(\d+)") (Long/parseLong))})
               :cljs (js->clj (js/JSON.parse s) :keywordize-keys true))]
      m)
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn expired? [cl now] (or (nil? (:exp cl)) (>= (long now) (long (:exp cl)))))

(defn signing-input
  "HMAC message: version + '.' + payloadSeg (kotoba `signing-input`)."
  [payload-seg]
  (str version "." payload-seg))

(defn wire-token
  "mk1.<payloadSeg>.<sig> (kotoba `wire-token`)."
  [payload-seg sig]
  (str version "." payload-seg "." sig))

(defn version-ok? [v]
  (= version (str v)))

(defn parts-present?
  "All three wire segments present (host split projects 0/1)."
  [v payload sig]
  (boolean (and v payload sig (not (str/blank? (str v)))
                (not (str/blank? (str payload)))
                (not (str/blank? (str sig))))))

(defn- char-code [s i]
  #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt ^string s i)))

(defn constant-time=
  "Length-checked constant-time string compare (kotoba `constant-time-eq`)."
  [a b]
  (and (string? a) (string? b) (= (count a) (count b))
       (zero? (reduce (fn [acc i] (bit-or acc (bit-xor (char-code a i) (char-code b i))))
                      0 (range (count a))))))

;; ── HMAC host adapter (javax / WebCrypto) ───────────────────────────

#?(:clj
   (defn- hmac-b64url [secret msg]
     (let [mac (Mac/getInstance "HmacSHA256")]
       (.init mac (SecretKeySpec. (.getBytes ^String secret StandardCharsets/UTF_8) "HmacSHA256"))
       (b64url-bytes (.doFinal mac (.getBytes ^String msg StandardCharsets/UTF_8))))))

#?(:cljs
   (defn- hmac-b64url
     "Promise<sig-b64url>. WebCrypto HMAC-SHA256 over the utf8 message bytes."
     [secret msg]
     (let [enc (js/TextEncoder.)]
       (-> (js/crypto.subtle.importKey "raw" (.encode enc secret)
                                       #js {:name "HMAC" :hash "SHA-256"} false #js ["sign"])
           (.then (fn [k] (js/crypto.subtle.sign "HMAC" k (.encode enc msg))))
           (.then (fn [buf] (b64url-bytes (js/Uint8Array. buf))))))))

#?(:clj
   (defn sign
     "Mint a token: pure wire + host HMAC."
     [secret opts]
     (let [payload (encode-claims (claims opts))
           si (signing-input payload)
           sig (hmac-b64url secret si)]
       (wire-token payload sig))))

#?(:clj
   (defn verify
     "Verify: pure version/parts/CT-eq + host HMAC; returns claims or nil."
     [secret token now]
     (let [[v payload sig] (str/split (str token) #"\." 3)]
       (when (and (version-ok? v) (parts-present? v payload sig))
         (let [expected (hmac-b64url secret (signing-input payload))]
           (when (constant-time= sig expected)
             (let [cl (decode-claims payload)]
               (when (and cl (not (expired? cl now))) cl))))))))

#?(:cljs
   (defn sign
     "Promise<token>. Pure wire + host WebCrypto HMAC."
     [secret opts]
     (let [payload (encode-claims (claims opts))
           si (signing-input payload)]
       (-> (hmac-b64url secret si)
           (.then (fn [sig] (wire-token payload sig)))))))

#?(:cljs
   (defn verify
     "Promise<claims|nil>. Pure version/parts/CT-eq + host WebCrypto HMAC."
     [secret token now]
     (let [[v payload sig] (str/split (str token) #"\." 3)]
       (if (and (version-ok? v) (parts-present? v payload sig))
         (-> (hmac-b64url secret (signing-input payload))
             (.then (fn [expected]
                      (when (constant-time= sig expected)
                        (let [cl (decode-claims payload)]
                          (when (and cl (not (expired? cl now))) cl))))))
         (js/Promise.resolve nil)))))

(defn scope-allows?
  "Does a token's scope grant `required`? \"all\" grants everything; otherwise
  exact match. Pure — usable on both runtimes (kotoba `scope-allows?`)."
  [token-scope required]
  (or (= "all" (str token-scope)) (= (str token-scope) (str required))))
