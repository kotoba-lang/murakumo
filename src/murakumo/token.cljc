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
            #?(:clj [murakumo.kotoba.oracle :as oracle])
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

;; ── pure claims / wire — kotoba/token_core.kotoba is SSoT ────────────
;; JVM: public pure helpers DELEGATE to precompiled KIR oracle.
;; cljs: host-mirror (same semantics; resource load is JVM/bb-only this slice).

(defn- mirror-claim-sub [sub]
  (str (or sub "anonymous")))

(defn- mirror-claim-scope [scope]
  (str (or scope "all")))

(defn- mirror-claim-exp [now ttl]
  (long (+ (long now) (long (or ttl default-ttl)))))

(defn- mirror-encode-claims-json [{:keys [sub scope iat exp]}]
  (str "{\"sub\":\"" sub "\",\"scope\":\"" scope
       "\",\"iat\":" iat ",\"exp\":" exp "}"))

(defn- mirror-signing-input [payload-seg]
  (str version "." payload-seg))

(defn- mirror-wire-token [payload-seg sig]
  (str version "." payload-seg "." sig))

(defn- mirror-version-ok? [v]
  (= version (str v)))

(defn- mirror-parts-present? [v payload sig]
  (boolean (and v payload sig (not (str/blank? (str v)))
                (not (str/blank? (str payload)))
                (not (str/blank? (str sig))))))

(defn- mirror-expired? [cl now]
  (or (nil? (:exp cl)) (>= (long now) (long (:exp cl)))))

(defn- char-code [s i]
  #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt ^string s i)))

(defn- mirror-constant-time= [a b]
  (and (string? a) (string? b) (= (count a) (count b))
       (zero? (reduce (fn [acc i] (bit-or acc (bit-xor (char-code a i) (char-code b i))))
                      0 (range (count a))))))

(defn- mirror-scope-allows? [token-scope required]
  (or (= "all" (str token-scope)) (= (str token-scope) (str required))))

(defn claims
  "Build the token claim map. Pure claim fields use kotoba authority on JVM
  (claim-sub/scope/exp); map assembly stays host."
  [{:keys [sub scope now ttl]}]
  (let [sub' #?(:clj (oracle/call :token 'claim-sub [(if (some? sub) 1 0) (str (or sub ""))])
                :cljs (mirror-claim-sub sub))
        scope' #?(:clj (oracle/call :token 'claim-scope [(if (some? scope) 1 0) (str (or scope ""))])
                  :cljs (mirror-claim-scope scope))
        exp' #?(:clj (oracle/call :token 'claim-exp [(long now) (long (if (some? ttl) ttl -1))])
                :cljs (mirror-claim-exp now ttl))]
    {:sub sub' :scope scope' :iat (long now) :exp (long exp')}))

(defn encode-claims-json
  "Fixed-key JSON. JVM: kotoba oracle. cljs: host mirror."
  [{:keys [sub scope iat exp] :as m}]
  #?(:clj (oracle/call :token 'encode-claims-json
                       [(str sub) (str scope) (long iat) (long exp)])
     :cljs (mirror-encode-claims-json m)))

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
  "True if claims are expired. JVM: kotoba oracle."
  [cl now]
  #?(:clj (= 1 (oracle/call :token 'expired?
                            [(if (contains? cl :exp) 1 0)
                             (long (or (:exp cl) 0))
                             (long now)]))
     :cljs (mirror-expired? cl now)))

(defn signing-input
  "HMAC message: version + '.' + payloadSeg. JVM: kotoba oracle."
  [payload-seg]
  #?(:clj (oracle/call :token 'signing-input [(str payload-seg)])
     :cljs (mirror-signing-input payload-seg)))

(defn wire-token
  "mk1.<payloadSeg>.<sig>. JVM: kotoba oracle."
  [payload-seg sig]
  #?(:clj (oracle/call :token 'wire-token [(str payload-seg) (str sig)])
     :cljs (mirror-wire-token payload-seg sig)))

(defn version-ok? [v]
  #?(:clj (= 1 (oracle/call :token 'version-ok? [(str v)]))
     :cljs (mirror-version-ok? v)))

(defn parts-present?
  "All three wire segments present."
  [v payload sig]
  #?(:clj (= 1 (oracle/call :token 'parts-present?
                            [(if (and v (not (str/blank? (str v)))) 1 0)
                             (if (and payload (not (str/blank? (str payload)))) 1 0)
                             (if (and sig (not (str/blank? (str sig)))) 1 0)]))
     :cljs (mirror-parts-present? v payload sig)))

(defn constant-time=
  "Full-scan string compare. JVM: kotoba oracle constant-time-eq."
  [a b]
  #?(:clj (and (string? a) (string? b)
               (= 1 (oracle/call :token 'constant-time-eq [(str a) (str b)])))
     :cljs (mirror-constant-time= a b)))

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
  "Does a token's scope grant `required`? JVM: kotoba oracle."
  [token-scope required]
  #?(:clj (= 1 (oracle/call :token 'scope-allows? [(str token-scope) (str required)]))
     :cljs (mirror-scope-allows? token-scope required)))
