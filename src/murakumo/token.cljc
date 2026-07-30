(ns murakumo.token
  "murakumo inference access tokens — stateless, HMAC-SHA256 signed capability
  tokens the operator mints from the CLI and the gateway verifies without any
  shared state (no KV, no DB round-trip).

  Wire format (v1):

      mk1.<payloadSeg>.<sig>
      payloadSeg = b64url(json {\"sub\":…, \"scope\":…, \"iat\":N, \"exp\":N})
      sig        = b64url( HMAC-SHA256(secret, \"mk1.\" + payloadSeg) )

  Pure wire/claims helpers are kotoba/token_core.kotoba SSoT (encode-claims-json,
  signing-input, wire-token, constant-time=). HMAC-SHA256 + base64url codecs
  stay host (javax on JVM/bb, WebCrypto on cljs).

  The signing secret (MURAKUMO_TOKEN_SECRET) is the operator's; it lives in the
  CLI's environment (to mint) and as a Worker secret (to verify).

  W6 product-shell + T6.4: pure helpers require the shipped `:token` KIR on
  **every** platform. Host pure mirrors are gone — cljs/nbb must preload
  shipped KIR (resources/ via nbb cwd, register-kir!, or set-resource-loader!)
  before requiring this ns or calling pure helpers
  (ADR-260731-w6-t64-token-mirror-delete)."
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle])
  #?(:clj (:import [javax.crypto Mac]
                   [javax.crypto.spec SecretKeySpec]
                   [java.util Base64]
                   [java.nio.charset StandardCharsets])))

(def ^:private oid :token)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

;; ── residual version / defaults / seps / json tokens ─────────────────

(def version
  "Wire version token. Kotoba SSoT (requires oracle)."
  (o 'version []))

(def default-ttl
  "Default exp offset seconds. Kotoba SSoT (requires oracle)."
  (oracle/i64->host (o 'default-ttl [])))

(def default-sub
  "Default claim sub when absent. Kotoba SSoT (requires oracle)."
  (o 'default-sub []))

(def default-scope
  "Default claim scope when absent. Kotoba SSoT (requires oracle)."
  (o 'default-scope []))

(def scope-all
  "Wildcard scope token. Kotoba SSoT (requires oracle)."
  (o 'scope-all []))

(def jwt-seg-sep
  "Separator between wire segments. Kotoba SSoT (requires oracle)."
  (o 'jwt-seg-sep []))

(def wire-sep
  "Alias of jwt-seg-sep for wire-token. Kotoba SSoT (requires oracle)."
  (o 'wire-sep []))

(def json-sub-prefix
  (o 'json-sub-prefix []))
(def json-scope-mid
  (o 'json-scope-mid []))
(def json-iat-mid
  (o 'json-iat-mid []))
(def json-exp-mid
  (o 'json-exp-mid []))
(def json-close
  (o 'json-close []))

;; ── base64url (no padding) over raw bytes — host codec ──────────────
;; cljs: prefer Node Buffer (nbb); fall back to btoa/atob in browsers.

#?(:cljs
   (defn- cljs-b64-encode
     "UTF-8 string or Uint8Array → standard base64."
     [data]
     (if (exists? js/Buffer)
       (if (string? data)
         (.toString (js/Buffer.from data "utf8") "base64")
         (.toString (js/Buffer.from data) "base64"))
       (let [bin (if (string? data)
                   data
                   (apply str (map #(js/String.fromCharCode %) (array-seq data))))]
         (js/btoa bin)))))

#?(:cljs
   (defn- cljs-b64-decode-str
     "standard base64 → UTF-8 string."
     [b64]
     (if (exists? js/Buffer)
       (.toString (js/Buffer.from b64 "base64") "utf8")
       (js/atob b64))))

#?(:cljs
   (defn- cljs-b64url [b64]
     (-> b64 (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" ""))))

(defn b64url-bytes [bytes]
  #?(:clj  (-> (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))
     :cljs (cljs-b64url (cljs-b64-encode bytes))))

(defn- b64url-decode->str [s]
  #?(:clj  (String. (.decode (Base64/getUrlDecoder) ^String s) StandardCharsets/UTF_8)
     :cljs (let [b64 (-> s (str/replace "-" "+") (str/replace "_" "/"))]
             (cljs-b64-decode-str b64))))

(defn b64url-str [s]
  #?(:clj  (b64url-bytes (.getBytes ^String s StandardCharsets/UTF_8))
     :cljs (cljs-b64url (cljs-b64-encode s))))

;; ── pure claims / wire — kotoba/token_core.kotoba is SSoT ────────────

(defn claims
  "Build the token claim map. Pure claim fields use kotoba (claim-sub/scope/exp
  via Product Value ABI options); map assembly stays host."
  [{:keys [sub scope now ttl]}]
  (let [sub' (o 'claim-sub [(oracle/option-string sub)])
        scope' (o 'claim-scope [(oracle/option-string scope)])
        exp' (oracle/i64->host
              (o 'claim-exp [(oracle/as-i64 now) (oracle/option-i64 ttl)]))]
    {:sub sub'
     :scope scope'
     :iat (oracle/i64->host (oracle/as-i64 now))
     :exp exp'}))

(defn encode-claims-json
  "Fixed-key JSON. Kotoba oracle required (T6.4)."
  [{:keys [sub scope iat exp]}]
  (o 'encode-claims-json
     [(str sub) (str scope)
      (oracle/as-i64 iat) (oracle/as-i64 exp)]))

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

(defn expired?
  "True if claims are expired. Kotoba oracle (option exp). Profile 5: :bool."
  [cl now]
  (oracle/bool->host
   (o 'expired?
      [(oracle/option-i64 (when (contains? cl :exp) (:exp cl)))
       (oracle/as-i64 now)])))

(defn signing-input
  "HMAC message: version + '.' + payloadSeg. Kotoba required."
  [payload-seg]
  (o 'signing-input [(str payload-seg)]))

(defn wire-token
  "mk1.<payloadSeg>.<sig>. Kotoba required."
  [payload-seg sig]
  (o 'wire-token [(str payload-seg) (str sig)]))

(defn version-ok? [v]
  (oracle/bool->host (o 'version-ok? [(str v)])))

(defn parts-present?
  "All three wire segments present (non-blank). Profile 5: guest :bool."
  [v payload sig]
  (let [seg (fn [x]
              (when (and x (not (str/blank? (str x))))
                (str x)))]
    (oracle/bool->host
     (o 'parts-present?
        [(oracle/option-string (seg v))
         (oracle/option-string (seg payload))
         (oracle/option-string (seg sig))]))))

(defn constant-time=
  "Full-scan string compare via kotoba constant-time-eq. Profile 5: :bool."
  [a b]
  (and (string? a) (string? b)
       (oracle/bool->host
        (o 'constant-time-eq [(str a) (str b)]))))

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
     (let [sep-re (re-pattern (str "\\" jwt-seg-sep))
           [v payload sig] (str/split (str token) sep-re 3)]
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
     (let [sep-re (re-pattern (str "\\" jwt-seg-sep))
           [v payload sig] (str/split (str token) sep-re 3)]
       (if (and (version-ok? v) (parts-present? v payload sig))
         (-> (hmac-b64url secret (signing-input payload))
             (.then (fn [expected]
                      (when (constant-time= sig expected)
                        (let [cl (decode-claims payload)]
                          (when (and cl (not (expired? cl now))) cl))))))
         (js/Promise.resolve nil)))))

(defn scope-allows?
  "Does a token's scope grant `required`? Kotoba required. Profile 5: :bool."
  [token-scope required]
  (oracle/bool->host
   (o 'scope-allows? [(str token-scope) (str required)])))
