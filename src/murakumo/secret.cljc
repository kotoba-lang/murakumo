(ns murakumo.secret
  "Named secret resolve for murakumo ops CLIs.

  Matches the W6 secret-custody kit reply shape (provider.secret id 21 /
  secret-transport ADR 0145–0146):

      {:tag :value :value s} | {:tag :error :code kw :message s}

  Standing policy: **no ambient env dump**, **no keychain list**. Default
  host path reads only the exact env vars mapped for known secret names.
  Hosts with provider can inject `provider.secret-transport/env-fetch`,
  `fn-fetch` (kagi one-shot), or `keychain-fetch` as `:fetch`."
  (:require [clojure.string :as str]))

;; ── named secrets (stable ids for kit allowlists) ───────────────────

(def token-secret-name "murakumo-token")
(def token-secret-env "MURAKUMO_TOKEN_SECRET")

(def service-token-name "murakumo-service-token")
(def service-token-env "MURAKUMO_SERVICE_TOKEN")

(def metrics-token-name "murakumo-metrics-token")
(def metrics-token-env "MURAKUMO_METRICS_TOKEN")

;; Path refs: env holds absolute filesystem paths to PEM files (never PEM bodies).
(def quic-cert-path-name "murakumo-quic-cert-path")
(def quic-cert-path-env "MURAKUMO_QUIC_CERT")
(def quic-key-path-name "murakumo-quic-key-path")
(def quic-key-path-env "MURAKUMO_QUIC_KEY")

(def known-env-secrets
  "Default ops mapping: secret-name → exact env var (never enumerated)."
  {token-secret-name token-secret-env
   service-token-name service-token-env
   metrics-token-name metrics-token-env
   quic-cert-path-name quic-cert-path-env
   quic-key-path-name quic-key-path-env})

(defn env-fetch
  "Build a kit-shaped fetch from `{secret-name env-var-name}`.
  Reads only the mapped env var for the requested name."
  [name->env]
  (when-not (and (map? name->env) (seq name->env)
                 (every? string? (keys name->env))
                 (every? string? (vals name->env)))
    (throw (ex-info "murakumo.secret/env-fetch requires non-empty string map"
                    {:phase :murakumo-secret})))
  (doseq [[_ e] name->env]
    (when (or (str/blank? e) (str/includes? e "*"))
      (throw (ex-info "murakumo.secret/env-fetch env var name invalid"
                      {:phase :murakumo-secret :env e}))))
  (fn [{:keys [name]}]
    (if-let [env-name (get name->env name)]
      #?(:clj
         (if-let [v (System/getenv ^String env-name)]
           (if (str/blank? v)
             {:tag :error :code :secret/empty :message "env secret empty"}
             {:tag :value :value v})
           {:tag :error :code :secret/not-found :message "env not set"})
         :cljs
         (let [v (when (exists? js/process)
                   (aget js/process.env env-name))]
           (cond
             (nil? v) {:tag :error :code :secret/not-found :message "env not set"}
             (str/blank? (str v)) {:tag :error :code :secret/empty :message "env secret empty"}
             :else {:tag :value :value (str v)})))
      {:tag :error :code :secret/not-found :message "no env mapping"})))

(defn map-fetch
  "Sealed map fetch for tests / host-injected secrets."
  [m]
  (fn [{:keys [name]}]
    (if-let [v (get m name)]
      (if (and (string? v) (str/blank? v))
        {:tag :error :code :secret/empty :message "empty"}
        {:tag :value :value (str v)})
      {:tag :error :code :secret/not-found :message "not found"})))

(defn fn-fetch
  "Wrap a host one-shot getter `(fn [name] string-or-nil)` — kagi shape."
  [getter]
  (when-not (fn? getter)
    (throw (ex-info "murakumo.secret/fn-fetch requires a getter fn"
                    {:phase :murakumo-secret})))
  (fn [{:keys [name]}]
    (try
      (let [v (getter name)]
        (cond
          (nil? v) {:tag :error :code :secret/not-found :message "nil"}
          (and (string? v) (str/blank? v)) {:tag :error :code :secret/empty :message "blank"}
          :else {:tag :value :value (str v)}))
      (catch #?(:clj Throwable :cljs :default) e
        {:tag :error
         :code :secret/fetch
         :message (or #?(:clj (.getMessage e) :cljs (.-message e))
                      "getter failed")}))))

(defn kagi-fetch
  "Wire named secrets to a kagi (or kagi-shaped) one-shot getter.

  `name->ref` maps secret-name → opaque ref (string key id / path).
  `getter` is `(fn [ref] string-or-nil)` performing exactly one lookup —
  e.g. `(fn [ref] (kagi.secret-store/get-secret store ref {}))`.

  Unknown names and nil getter results map to kit-shaped `:secret/not-found`.
  No enumeration of the vault."
  [name->ref getter]
  (when-not (and (map? name->ref) (seq name->ref)
                 (every? string? (keys name->ref))
                 (every? string? (vals name->ref)))
    (throw (ex-info "murakumo.secret/kagi-fetch requires string name→ref map"
                    {:phase :murakumo-secret})))
  (when-not (fn? getter)
    (throw (ex-info "murakumo.secret/kagi-fetch requires getter fn"
                    {:phase :murakumo-secret})))
  (fn-fetch
   (fn [name]
     (if-let [ref (get name->ref name)]
       (getter ref)
       nil))))


(defn default-ops-fetch
  "Default ops path: exact env vars for all known secret names."
  []
  (env-fetch known-env-secrets))

(defn default-token-fetch
  "Default ops path: exact MURAKUMO_TOKEN_SECRET only."
  []
  (env-fetch {token-secret-name token-secret-env}))

(defn resolve-secret
  "Return the secret string for `name`, or nil when missing/empty/error.

  opts:
    :fetch  kit-shaped `(fn [{:keys [name]}] reply)` — default `default-ops-fetch`"
  ([name] (resolve-secret name {}))
  ([name {:keys [fetch]}]
   (let [fetch (or fetch (default-ops-fetch))]
     (let [reply (fetch {:name name})]
       (when (and (map? reply) (= :value (:tag reply)))
         (let [v (str (:value reply))]
           (when-not (str/blank? v) v)))))))

(defn resolve-token-secret
  "Resolve the murakumo inference HMAC secret (named murakumo-token)."
  ([] (resolve-token-secret {}))
  ([opts] (resolve-secret token-secret-name opts)))

(defn resolve-service-token
  "Resolve the cloud write-gate service token (named murakumo-service-token)."
  ([] (resolve-service-token {}))
  ([opts] (resolve-secret service-token-name opts)))

(defn resolve-metrics-token
  "Resolve the metrics/model-map push token (named murakumo-metrics-token)."
  ([] (resolve-metrics-token {}))
  ([opts] (resolve-secret metrics-token-name opts)))

(defn valid-env-var-name?
  "Reject blank, wildcard, and path-like env var names."
  [env-name]
  (and (string? env-name)
       (not (str/blank? env-name))
       (not (str/includes? env-name "*"))
       (not (str/includes? env-name "/"))
       (not (str/includes? env-name "\\"))
       (not (str/includes? env-name " "))
       (<= (count env-name) 256)))

(defn resolve-exact-env
  "Read one **exact** env var by name declared in config (e.g. overlay
  `:overlay/auth-key-env`). Never dumps the environment.

  opts:
    :fetch  optional kit-shaped fetch; default builds a one-entry env-fetch"
  ([env-name] (resolve-exact-env env-name {}))
  ([env-name {:keys [fetch]}]
   (when (valid-env-var-name? env-name)
     (let [alias "dyn-env"
           fetch (or fetch (env-fetch {alias env-name}))]
       (resolve-secret alias {:fetch fetch})))))

(defn valid-path-ref?
  "True when `p` is an absolute filesystem path, not a PEM body or dump.
  Env path refs must point at files under host custody — never inline PEM."
  [p]
  (and (string? p)
       (not (str/blank? p))
       (not (str/includes? p "\0"))
       (not (str/includes? p "-----BEGIN"))
       (not (str/includes? p "*"))
       #?(:clj (.isAbsolute (java.io.File. ^String p))
          :cljs (or (str/starts-with? p "/")
                    (boolean (re-matches #"[A-Za-z]:[\\/].*" p))))
       (<= (count p) 1024)))

(defn resolve-path-ref
  "Resolve a named **path ref** secret (absolute path string to on-disk
  material). Rejects PEM bodies and relative/wildcard paths.

  opts: same as `resolve-secret` (`:fetch` inject)."
  ([name] (resolve-path-ref name {}))
  ([name opts]
   (when-let [p (resolve-secret name opts)]
     (when (valid-path-ref? p) p))))

(defn resolve-quic-cert-path
  "Absolute path to QUIC cert PEM file, or nil."
  ([] (resolve-quic-cert-path {}))
  ([opts] (resolve-path-ref quic-cert-path-name opts)))

(defn resolve-quic-key-path
  "Absolute path to QUIC private key PEM file, or nil."
  ([] (resolve-quic-key-path {}))
  ([opts] (resolve-path-ref quic-key-path-name opts)))
