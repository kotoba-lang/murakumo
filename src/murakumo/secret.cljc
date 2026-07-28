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

(def known-env-secrets
  "Default ops mapping: secret-name → exact env var (never enumerated)."
  {token-secret-name token-secret-env
   service-token-name service-token-env
   metrics-token-name metrics-token-env})

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
