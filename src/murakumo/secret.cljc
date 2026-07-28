(ns murakumo.secret
  "Named secret resolve for murakumo ops CLIs.

  Matches the W6 secret-custody kit reply shape (provider.secret id 21 /
  secret-transport ADR 0145–0146):

      {:tag :value :value s} | {:tag :error :code kw :message s}

  Standing policy: **no ambient env dump**, **no keychain list**. Default
  host path reads only the exact env var mapped for a secret name. Hosts
  with provider can inject `provider.secret-transport/env-fetch`,
  `fn-fetch` (kagi one-shot), or `keychain-fetch` as `:fetch`."
  (:require [clojure.string :as str]))

(def token-secret-name "murakumo-token")
(def token-secret-env "MURAKUMO_TOKEN_SECRET")

(defn env-fetch
  "Build a kit-shaped fetch from `{secret-name env-var-name}`.
  Reads only the mapped env var for the requested name."
  [name->env]
  (when-not (and (map? name->env) (seq name->env)
                 (every? string? (keys name->env))
                 (every? string? (vals name->env)))
    (throw (ex-info "murakumo.secret/env-fetch requires non-empty string map"
                    {:phase :murakumo-secret})))
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

(defn default-token-fetch
  "Default ops path: exact MURAKUMO_TOKEN_SECRET only."
  []
  (env-fetch {token-secret-name token-secret-env}))

(defn resolve-secret
  "Return the secret string for `name`, or nil when missing/empty/error.

  opts:
    :fetch  kit-shaped `(fn [{:keys [name]}] reply)` — default token env-fetch
            when name is token-secret-name, else nil path"
  ([name] (resolve-secret name {}))
  ([name {:keys [fetch]}]
   (let [fetch (or fetch
                   (when (= name token-secret-name)
                     (default-token-fetch)))]
     (when fetch
       (let [reply (fetch {:name name})]
         (when (and (map? reply) (= :value (:tag reply)))
           (let [v (str (:value reply))]
             (when-not (str/blank? v) v))))))))

(defn resolve-token-secret
  "Resolve the murakumo inference HMAC secret (named murakumo-token)."
  ([] (resolve-token-secret {}))
  ([opts] (resolve-secret token-secret-name opts)))
