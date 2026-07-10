(ns murakumo.token
  "murakumo inference access tokens — stateless, HMAC-SHA256 signed capability
  tokens the operator mints from the CLI and the gateway verifies without any
  shared state (no KV, no DB round-trip).

  Wire format (v1):

      mk1.<payloadSeg>.<sig>
      payloadSeg = b64url(json {\"sub\":…, \"scope\":…, \"iat\":N, \"exp\":N})
      sig        = b64url( HMAC-SHA256(secret, \"mk1.\" + payloadSeg) )

  b64url is RFC-4648 url-safe, no padding. `verify` recomputes the signature
  over the LITERAL received `mk1.<payloadSeg>` bytes (never re-serialized), so
  the two runtimes only need to agree on HMAC + base64url of raw bytes — not on
  JSON key order or whitespace. That is what lets the CLI (JVM/babashka, javax
  HMAC) and the Cloudflare Worker (cljs, WebCrypto) mint/verify the same token.

  This file is the single source of truth for the format and is kept BYTE-
  IDENTICAL in cloud-murakumo (the verifying gateway). Edit both together.

  The signing secret (MURAKUMO_TOKEN_SECRET) is the operator's; it lives in the
  CLI's environment (to mint) and as a Worker secret (to verify). It is never
  embedded in a token and never leaves those two places."
  (:require [clojure.string :as str]
            #?(:cljs [goog.crypt.base64 :as gb64]))
  #?(:clj (:import [javax.crypto Mac]
                   [javax.crypto.spec SecretKeySpec]
                   [java.util Base64]
                   [java.nio.charset StandardCharsets])))

(def ^:const version "mk1")

;; ── base64url (no padding) over raw bytes ───────────────────────────

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

;; ── pure claims helpers (identical on both runtimes) ────────────────

(defn claims
  "Build the token claim map. `now`/`ttl` in epoch seconds (caller supplies the
  clock — this ns is pure). Scope is a plain string like \"chat\" | \"image\" |
  \"all\"; the gateway decides what each scope may reach."
  [{:keys [sub scope now ttl]}]
  {:sub (str (or sub "anonymous"))
   :scope (str (or scope "all"))
   :iat (long now)
   :exp (long (+ now (or ttl 2592000)))})   ; default 30d

(defn encode-claims [m]
  (b64url-str #?(:clj (str "{\"sub\":\"" (:sub m) "\",\"scope\":\"" (:scope m)
                           "\",\"iat\":" (:iat m) ",\"exp\":" (:exp m) "}")
                 :cljs (js/JSON.stringify (clj->js m)))))

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

(defn- char-code [s i]
  #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt ^string s i)))

(defn- constant-time=
  "Length-checked constant-time string compare (avoids sig timing leaks)."
  [a b]
  (and (string? a) (string? b) (= (count a) (count b))
       (zero? (reduce (fn [acc i] (bit-or acc (bit-xor (char-code a i) (char-code b i))))
                      0 (range (count a))))))

;; ── HMAC — sync on the JVM (CLI), async in the Worker (WebCrypto) ────

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

;; ── sign / verify (sync on JVM, Promise on cljs) ────────────────────

#?(:clj
   (defn sign
     "Mint a token from claim opts. JVM/bb — returns the token string."
     [secret opts]
     (let [payload (encode-claims (claims opts))
           signing-input (str version "." payload)]
       (str signing-input "." (hmac-b64url secret signing-input)))))

#?(:clj
   (defn verify
     "JVM/bb verify — returns the claim map if the signature is valid and the
     token is unexpired, else nil."
     [secret token now]
     (let [[v payload sig] (str/split (str token) #"\." 3)]
       (when (and (= v version) payload sig)
         (let [expected (hmac-b64url secret (str version "." payload))]
           (when (constant-time= sig expected)
             (let [cl (decode-claims payload)]
               (when (and cl (not (expired? cl now))) cl))))))))

#?(:cljs
   (defn sign
     "Promise<token>. Worker-side minting (rarely used — the CLI usually mints)."
     [secret opts]
     (let [payload (encode-claims (claims opts))
           signing-input (str version "." payload)]
       (-> (hmac-b64url secret signing-input)
           (.then (fn [sig] (str signing-input "." sig)))))))

#?(:cljs
   (defn verify
     "Promise<claims|nil>. Worker-side verification for the gateway."
     [secret token now]
     (let [[v payload sig] (str/split (str token) #"\." 3)]
       (if (and (= v version) payload sig)
         (-> (hmac-b64url secret (str version "." payload))
             (.then (fn [expected]
                      (when (constant-time= sig expected)
                        (let [cl (decode-claims payload)]
                          (when (and cl (not (expired? cl now))) cl))))))
         (js/Promise.resolve nil)))))

(defn scope-allows?
  "Does a token's scope grant `required`? \"all\" grants everything; otherwise
  exact match. Pure — usable on both runtimes."
  [token-scope required]
  (or (= "all" (str token-scope)) (= (str token-scope) (str required))))
