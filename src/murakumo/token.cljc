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
  CLI's environment (to mint) and as a Worker secret (to verify).

  W6 product-shell: pure helpers DELEGATE to precompiled KIR when oracle is
  loadable (JVM classpath or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
  Host mirrors remain fallback when oracle is not ready."
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle])
  #?(:clj (:import [javax.crypto Mac]
                   [javax.crypto.spec SecretKeySpec]
                   [java.util Base64]
                   [java.nio.charset StandardCharsets])))

(def ^:private oid :token)

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- o [export args]
  (oracle/call oid export args))

(def ^:const version "mk1")
(def ^:const default-ttl 2592000)

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

(defn- mirror-claim-sub [sub]
  (str (or sub "anonymous")))

(defn- mirror-claim-scope [scope]
  (str (or scope "all")))

(defn- mirror-claim-exp [now ttl]
  (+ (oracle/i64->host (oracle/as-i64 now))
     (oracle/i64->host (oracle/as-i64 (or ttl default-ttl)))))

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
  (or (nil? (:exp cl))
      (>= (oracle/i64->host (oracle/as-i64 now))
          (oracle/i64->host (oracle/as-i64 (:exp cl))))))

(defn- char-code [s i]
  #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt ^string s i)))

(defn- mirror-constant-time= [a b]
  (and (string? a) (string? b) (= (count a) (count b))
       (zero? (reduce (fn [acc i] (bit-or acc (bit-xor (char-code a i) (char-code b i))))
                      0 (range (count a))))))

(defn- mirror-scope-allows? [token-scope required]
  (or (= "all" (str token-scope)) (= (str token-scope) (str required))))

(defn- try-oracle
  "Run oracle body; on failure (e.g. cljs KIR string-from-i64 bounds) use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn claims
  "Build the token claim map. Pure claim fields use kotoba when oracle ready
  (claim-sub/scope/exp via Product Value ABI options); map assembly stays host."
  [{:keys [sub scope now ttl]}]
  (let [sub' (try-oracle
              #(o 'claim-sub [(oracle/option-string sub)])
              #(mirror-claim-sub sub))
        scope' (try-oracle
                #(o 'claim-scope [(oracle/option-string scope)])
                #(mirror-claim-scope scope))
        exp' (try-oracle
              #(oracle/i64->host
                (o 'claim-exp [(oracle/as-i64 now) (oracle/option-i64 ttl)]))
              #(mirror-claim-exp now ttl))]
    {:sub sub'
     :scope scope'
     :iat (oracle/i64->host (oracle/as-i64 now))
     :exp exp'}))

(defn encode-claims-json
  "Fixed-key JSON. Kotoba oracle when ready; host mirror otherwise.
   Falls back if KIR string-from-i64 faults on some cljs builds."
  [{:keys [sub scope iat exp] :as m}]
  (try-oracle
   #(o 'encode-claims-json
       [(str sub) (str scope)
        (oracle/as-i64 iat) (oracle/as-i64 exp)])
   #(mirror-encode-claims-json m)))

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
  "True if claims are expired. Kotoba oracle (option exp) when ready."
  [cl now]
  (try-oracle
   #(= 1 (oracle/i64->host
          (o 'expired?
             [(oracle/option-i64 (when (contains? cl :exp) (:exp cl)))
              (oracle/as-i64 now)])))
   #(mirror-expired? cl now)))

(defn signing-input
  "HMAC message: version + '.' + payloadSeg. Kotoba when ready."
  [payload-seg]
  (try-oracle
   #(o 'signing-input [(str payload-seg)])
   #(mirror-signing-input payload-seg)))

(defn wire-token
  "mk1.<payloadSeg>.<sig>. Kotoba when ready."
  [payload-seg sig]
  (try-oracle
   #(o 'wire-token [(str payload-seg) (str sig)])
   #(mirror-wire-token payload-seg sig)))

(defn version-ok? [v]
  (try-oracle
   #(= 1 (oracle/i64->host (o 'version-ok? [(str v)])))
   #(mirror-version-ok? v)))

(defn parts-present?
  "All three wire segments present (non-blank)."
  [v payload sig]
  (try-oracle
   #(let [seg (fn [x]
                (when (and x (not (str/blank? (str x))))
                  (str x)))]
      (= 1 (oracle/i64->host
            (o 'parts-present?
               [(oracle/option-string (seg v))
                (oracle/option-string (seg payload))
                (oracle/option-string (seg sig))]))))
   #(mirror-parts-present? v payload sig)))

(defn constant-time=
  "Full-scan string compare. Kotoba constant-time-eq when ready.
   Falls back if KIR string-substring scan faults on some cljs builds."
  [a b]
  (try-oracle
   #(and (string? a) (string? b)
         (= 1 (oracle/i64->host
               (o 'constant-time-eq [(str a) (str b)]))))
   #(mirror-constant-time= a b)))

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
  "Does a token's scope grant `required`? Kotoba when ready."
  [token-scope required]
  (try-oracle
   #(= 1 (oracle/i64->host
          (o 'scope-allows? [(str token-scope) (str required)])))
   #(mirror-scope-allows? token-scope required)))
