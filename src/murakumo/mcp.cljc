(ns murakumo.mcp
  "murakumo's MCP surface: issue and inspect API keys from an MCP client.

  Same implementation as the `token` CLI (murakumo.apikey), so the two surfaces
  cannot drift. Two tools, deliberately no more:

    murakumo.issue_api_key   — mint an mk1 capability token
    murakumo.verify_api_key  — decode + verify one

  ## What this does and does not expose

  It mints from `$MURAKUMO_TOKEN_SECRET` in the server's own environment. It
  never reads, returns or logs that secret — only tokens derived from it — and it
  refuses outright when it is unset rather than emitting something that would fail
  at the gateway with an indistinguishable 401.

  The issued token IS a credential. Two properties keep that bounded, both
  enforced in murakumo.apikey rather than here:

    - scope must be one the gateway actually understands (chat | image | all);
      an unknown scope is refused at issue time instead of becoming a confusing
      401 later
    - ttl is capped at 90 days. mk1 is stateless by design — the gateway verifies
      with no KV or DB round-trip, which also means there is no revocation list.
      Expiry is the entire revocation story, so an unbounded key would be a
      permanent one. Re-issue instead.

  Running this server therefore grants its client the ability to mint keys up to
  those bounds, for as long as the secret is in the environment. That is the same
  authority the CLI has always had; MCP just makes it callable by an agent."
  (:require [mcp.model :as m]
            [mcp.ports :as ports]
            [murakumo.apikey :as apikey]))

(defn- secret []
  #?(:cljs (or (some-> (aget js/process.env "MURAKUMO_TOKEN_SECRET") str) "")
     :clj  (or (System/getenv "MURAKUMO_TOKEN_SECRET") "")))

(defn- now-s []
  #?(:cljs (quot (.getTime (js/Date.)) 1000)
     :clj  (quot (System/currentTimeMillis) 1000)))

(defn issue-tool
  "args: sub, scope, ttl_seconds. Returns the token plus its claims, or a
  refusal — never throws, so a bad request is an answer the model can act on."
  [args]
  (let [ttl (some-> (get args "ttl_seconds") long)
        r (apikey/issue {:secret (secret)
                         :sub (get args "sub" "mcp-client")
                         :scope (get args "scope" "all")
                         :ttl (or ttl apikey/default-ttl)
                         :now (now-s)})]
    (if (:ok r)
      {:token (:token r)
       :sub (get-in r [:claims :sub])
       :scope (get-in r [:claims :scope])
       :expires_at (get-in r [:claims :exp])
       :usage "send as `x-api-key: <token>` or `Authorization: Bearer <token>`"
       :note "stateless capability token — it cannot be revoked, only expire"}
      {:error (:error r)})))

(defn verify-tool
  [args]
  (apikey/inspect {:secret (secret) :token (get args "token") :now (now-s)}))

(def ^:private tool-fns
  {"murakumo.issue_api_key" issue-tool
   "murakumo.verify_api_key" verify-tool})

(defrecord MurakumoTools []
  ports/ITool
  (invoke [_ tool-name args]
    (if-let [f (get tool-fns tool-name)]
      (f (or args {}))
      {:error (str "tool not implemented: " tool-name)})))

(defn tool-port [] (->MurakumoTools))

(defn manifest []
  (-> (m/server "murakumo" "0.1.0")
      (m/add-tool "murakumo.issue_api_key"
                  {:description
                   (str "Issue a murakumo API key (mk1 capability token) for api.murakumo.cloud. "
                        "Requires MURAKUMO_TOKEN_SECRET in this server's environment; refuses if unset. "
                        "The key cannot be revoked — it can only expire — so prefer a short ttl and "
                        "the narrowest scope that works. Returns the token once.")
                   :input-schema
                   {:type "object"
                    :properties
                    {"sub" {:type "string"
                            :description "label for who/what this key is for (appears in the token claims, e.g. \"laptop\", \"ci\")"}
                     "scope" {:type "string" :enum ["chat" "image" "all"]
                              :description "what the key may reach; narrower is better"}
                     "ttl_seconds" {:type "integer"
                                    :description "lifetime in seconds (default 2592000 = 30d, maximum 7776000 = 90d)"}}}})
      (m/add-tool "murakumo.verify_api_key"
                  {:description
                   (str "Decode and verify a murakumo API key against MURAKUMO_TOKEN_SECRET. "
                        "Answers {valid, sub, scope, exp} — an invalid key is an answer, not an error. "
                        "Use it to check whether a key is expired or was signed with a different secret.")
                   :input-schema
                   {:type "object"
                    :properties {"token" {:type "string" :description "the mk1.… token to check"}}
                    :required ["token"]}})))
