(ns murakumo.infer.poll-worker
  (:require [clojure.string :as str]
            [murakumo.infer.backoff :as backoff]
            ["node:crypto" :as crypto]
            ["node:os" :as os]))

(def job-kind "host-large-model")

(def ^:private trust-tiers
  "The two production trust domains (docs/adr-secure-community-cloud.md in
  network-awai/cloud-murakumo). There is no `:any`, and placement requires an
  exact match with no Secure-to-Community fallback."
  #{"awai-secure" "community"})

(defn node-trust-tier
  "The tier this node enrolls under.

  **Defaults to community, and that default is the ADR's, not a convenience.**
  `local-murakumo.control-plane` reads `(or (:node/trust-tier body) :community)`
  precisely so that an unauthenticated provider cannot become Secure by
  omitting a field. Opting into Secure has to be an explicit act by whoever
  operates the hardware.

  Until 2026-08-29 this worker sent NO tier at all, so every node it enrolled
  became Community. Workloads normalize the other way -- a missing declaration
  is `:awai-secure`, keeping upgrades on the safer pool. Both defaults are
  individually correct and together they deadlock: the ten `fleet.edn` Mac
  minis serving `murakumo-edge` were `live? true / ready? true / slots-free 1`
  and an admission state of pending, so `murakumo-edge` had zero admitted backends and
  the public route answered 503 while the fleet looked healthy."
  [opts env]
  (let [t (or (:trust-tier opts) (get env "MURAKUMO_TRUST_TIER") "community")
        t (str/trim (str t))]
    (when-not (contains? trust-tiers t)
      (println (str "--trust-tier must be one of " (str/join ", " (sort trust-tiers))
                    " (got " (pr-str t) ")"))
      (js/process.exit 1))
    t))

(defn parse-args [args]
  (loop [xs args, acc {}]
    (if-let [a (first xs)]
      (if (str/starts-with? a "--")
        (recur (drop 2 xs) (assoc acc (keyword (subs a 2)) (second xs)))
        (recur (rest xs) acc))
      acc)))

(defn local-auth-token
  "Resolve the local inference-server credential without confusing it with
   the Murakumo control-plane token. An explicit option is useful for manual
   diagnostics; resident services should use a dedicated environment value or
   the vLLM-standard VLLM_API_KEY."
  [opts env]
  (or (:local-token opts)
      (get env "MURAKUMO_INFER_LOCAL_TOKEN")
      (get env "VLLM_API_KEY")))

(defn local-auth-headers [token]
  (cond-> {"content-type" "application/json"}
    (not (str/blank? (str token)))
    (assoc "authorization" (str "Bearer " token))))

(defn control-auth
  "Resolve control-plane authentication without exposing the credential in
  logs.  A device CACAO is preferred when explicitly supplied; the service
  token remains supported for existing operator-managed residents."
  [opts env]
  (let [candidates [[:cacao (:cacao opts)]
                    [:bearer (:token opts)]
                    [:cacao (get env "MURAKUMO_NODE_CACAO")]
                    [:bearer (get env "MURAKUMO_SERVICE_TOKEN")]]]
    (some (fn [[kind credential]]
            (when-not (str/blank? (str credential))
              {:kind kind :credential credential}))
          candidates)))

(defn control-auth-headers [auth]
  (cond-> {"content-type" "application/json"}
    (= :bearer (:kind auth))
    (assoc "authorization" (str "Bearer " (:credential auth)))
    (= :cacao (:kind auth))
    (assoc "authorization" (str "CACAO " (:credential auth)))))

(defn valid-node-name? [node-name]
  (boolean (and (string? node-name)
                (re-matches #"[A-Za-z0-9._-]{1,128}" node-name))))

(defn- sha256-hex [s]
  (-> (.createHash crypto "sha256") (.update (str s)) (.digest "hex")))

(defn node-did
  "Use the device DID paired with a CACAO when one is configured.  The pending
  name-derived identity is retained only for existing operator-token residents."
  [opts env node-name]
  (or (:did opts)
      (get env "MURAKUMO_NODE_DID")
      (str "did:key:pending-" (sha256-hex node-name))))

(defn- full-keyword-name [k]
  (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)))

(defn- json-body [body]
  (js/JSON.stringify (clj->js body :keyword-fn full-keyword-name)))

(defn- response-body [^js response]
  (-> (.text response)
      (.then (fn [text]
               {:status (.-status response)
                :body (try
                        (js->clj (js/JSON.parse text) :keywordize-keys true)
                        (catch :default _ text))}))))

(defn- api-with-fetch! [fetch-fn base auth method path & [body]]
  (-> (fetch-fn (str base path)
                (clj->js
                 (cond-> {:method (name method)
                          :headers (control-auth-headers auth)}
                   body (assoc :body (json-body body)))))
      (.then response-body)))

(defn- api! [base auth method path & [body]]
  (api-with-fetch! js/fetch base auth method path body))

(defn model-ready?
  "True only when the local OpenAI-compatible model inventory names the model
  this worker will actually execute.  HTTP reachability alone is not capacity."
  [model body]
  (let [rows (or (:data body) (:models body) [])
        ids (keep #(or (:id %) (:name %) (:model %)) rows)]
    (boolean (some #{model} ids))))

(defn probe-readiness-with-fetch!
  [fetch-fn local-url local-token model]
  (-> (fetch-fn (str local-url "/models")
                #js {:method "GET"
                     :headers (clj->js (local-auth-headers local-token))})
      (.then response-body)
      (.then (fn [{:keys [status body]}]
               {:ready? (and (<= 200 status 299) (model-ready? model body))
                :status status}))
      (.catch (fn [error]
                {:ready? false :status 0 :error (str error)}))))

(defn heartbeat-body
  "Build one bounded liveness observation.  `ready?` is a fresh local model
  probe; a busy single-slot worker remains live but advertises zero free slots."
  [{:keys [did model engine slots busy? memory-bytes]} ready? observed-at]
  (let [total (max 1 (or slots 1))
        used (if (and busy? @busy?) 1 0)]
    {:did did
     :node/ready? (boolean (and ready? (< used total)))
     :node/model model
     :node/engine (or engine "openai-compatible")
     :node/observed-at observed-at
     :node/capacity {:slots-total total
                     :slots-free (max 0 (- total used))
                     :memory-bytes (max 0 (long (memory-bytes)))}}))

(defn heartbeat-with-fetch!
  [fetch-fn {:keys [base auth did node-name local-url local-token model]
             :as config}]
  (-> (probe-readiness-with-fetch! fetch-fn local-url local-token model)
      (.then (fn [{:keys [ready?] :as probe}]
               (-> (api-with-fetch! fetch-fn base auth :post
                                    (str "/infer/nodes/" node-name "/heartbeat")
                                    (heartbeat-body config ready? (js/Date.now)))
                   (.then #(assoc % :probe probe)))))))

(defn- heartbeat! [config]
  (heartbeat-with-fetch! js/fetch config))

(defn run-completion-with-fetch!
  "Run one local OpenAI-compatible completion. Resolves to a structured
   outcome for both HTTP and response-shape failures."
  [fetch-fn local-url local-token model prompt max-tokens & [openai-request]]
  (let [t0 (js/Date.now)]
    (-> (fetch-fn (str local-url "/chat/completions")
                  #js {:method "POST"
                       :headers (clj->js (local-auth-headers local-token))
                       :body (json-body
                              (if (map? openai-request)
                                (-> openai-request
                                    (assoc :model model :stream false)
                                    (update :max_tokens #(min 2048 (or % max-tokens 512))))
                                {:model model
                                 :messages [{:role "user" :content (str prompt)}]
                                 :max_tokens (or max-tokens 512)}))})
        (.then response-body)
        (.then (fn [{:keys [status body]}]
                 (let [text (get-in body [:choices 0 :message :content])]
                   (cond
                     (not (<= 200 status 299))
                     {:ok false :error (str "local inference HTTP " status ": " (pr-str body)) :ms 0}

                     (or text (seq (get-in body [:choices 0 :message :tool_calls])))
                     {:ok true :text (or text "") :response body
                      :ms (- (js/Date.now) t0)}

                     :else
                     {:ok false :error (str "no completion in response: " (pr-str body)) :ms 0})))))))

(defn run-completion! [local-url local-token model prompt max-tokens & [openai-request]]
  (run-completion-with-fetch! js/fetch local-url local-token model prompt max-tokens openai-request))

(defn promise-finally
  "nbb-compatible Promise cleanup. Some fleet nbb runtimes do not expose the
   JavaScript `finally` method through CLJS interop, so spell out both arms."
  [promise cleanup]
  (.then promise
         (fn [value]
           (cleanup)
           value)
         (fn [error]
           (cleanup)
           (js/Promise.reject error))))

(defn- report-result! [base auth did job-id outcome ms]
  (api! base auth :post (str "/infer/queue/" job-id "/result")
        {:did did
         :output (if (:ok outcome)
                   (cond-> {:text (:text outcome)}
                     (:response outcome) (assoc :response (:response outcome)))
                   {:error (:error outcome)})
         :ms ms}))

(defn- claim-and-run! [{:keys [base auth did local-url local-token model busy?]
                        :as config} job]
  (let [job-id (:job-id job)]
    (-> (api! base auth :post (str "/infer/queue/" job-id "/claim") {:did did})
        (.then
         (fn [{:keys [status]}]
           (if (not= 201 status)
             (do
               (println (str "[join] job " job-id " already claimed, trying next"))
               false)
             (do
               (reset! busy? true)
               (let [work (-> (heartbeat! config)
                              (.catch (fn [error]
                                        (println "[join] busy heartbeat error:" (str error))))
                              (.then (fn [_]
                                       (run-completion! local-url local-token model
                                                        (get-in job [:input :prompt])
                                                        (get-in job [:input :max-tokens])
                                                        (get-in job [:input :request]))))
                              (.then
                               (fn [outcome]
                                 (.then
                                  (report-result! base auth did job-id outcome (:ms outcome 0))
                                  (fn [{:keys [status body]}]
                                    (println (str "[join] job " job-id " -> "
                                                  (if (:ok outcome) "ok" "error")
                                                  " (result status " status ")"))
                                    (when (= 201 status)
                                      (println "[join]   settled:"
                                               (pr-str (:shares body)))))))))]
                 (-> (promise-finally
                      work
                      (fn []
                        (reset! busy? false)
                        (-> (heartbeat! config)
                            (.catch (fn [error]
                                      (println "[join] idle heartbeat error:"
                                               (str error)))))))
                     (.then (fn [_] true)))))))))))

(defn claim-first-available!
  "Try jobs in queue order until one claim succeeds.  A fleet poll returns the
   same first job to many idle workers; stopping after its expected 409 would
   serialize fan-out at one worker per poll interval."
  [claim-fn jobs]
  (letfn [(attempt [remaining]
            (if-let [job (first remaining)]
              (-> (claim-fn job)
                  (.then (fn [claimed?]
                           (if claimed?
                             true
                             (attempt (next remaining))))))
              (js/Promise.resolve false)))]
    (attempt jobs)))

(defn claim-order
  "Give each worker a stable, different queue permutation.  Atomic claims are
   still authoritative; this only avoids making every idle worker begin with
   the same CAS and then walk the same collision chain."
  [did jobs]
  (sort-by #(sha256-hex (str did "\u0000" (:job-id %))) jobs))

(defn queue-path
  "Request only jobs that this worker is currently eligible to claim.  The
   control plane remains authoritative, but including the DID prevents a soft
   cache-affinity job from being advertised to every edge worker first."
  [model did]
  (str "/infer/queue?model=" (js/encodeURIComponent model)
       "&did=" (js/encodeURIComponent did)))

(defn- poll-once! [{:keys [base auth model did] :as config}]
  (-> (api! base auth :get (queue-path model did))
      (.then (fn [{:keys [body]}]
               (claim-first-available!
                #(claim-and-run! config (select-keys % [:job-id :kind :input]))
                (claim-order (:did config) body))))))

(defn- failure-facts
  "What a fetch failure says about itself: undici hides the OS error under
   `.cause.code`; an HTTP throw carries `:status`."
  [error]
  {:code (or (some-> error .-cause .-code) (some-> error .-code))
   :message (str (or (some-> error .-message) error))
   :status (some-> error ex-data :status)})

(defn- schedule-after-failure!
  "Classify, log, and wait. `failures` is the loop's consecutive-failure count.
   A local-exhaustion failure (this node cannot open sockets) waits the whole
   ceiling: retrying on cadence is exactly what kept benjamin's port range full
   for 60 days (ADR-260902-fleet-outbound-hygiene)."
  [label failures error base-ms next!]
  (let [facts (failure-facts error)
        kind (backoff/classify facts)
        n (swap! failures inc)
        policy (assoc backoff/default-policy :base-ms base-ms)
        wait (backoff/delay-for policy kind n (js/Math.random))]
    (println (str "[join] " label " error (" (name kind)
                  (when-let [c (:code facts)] (str " " c))
                  ", failure " n ", next in " (js/Math.round (/ wait 1000)) "s): "
                  (:message facts)))
    (js/setTimeout next! wait)))

(defn- loop! [{:keys [poll-ms poll-failures] :as config}]
  (-> (poll-once! config)
      (.then (fn [_]
               (reset! poll-failures 0)
               (js/setTimeout #(loop! config) poll-ms)))
      (.catch (fn [error]
                (schedule-after-failure! "poll" poll-failures error poll-ms
                                         #(loop! config))))))

(defn- heartbeat-loop! [{:keys [heartbeat-ms heartbeat-failures] :as config}]
  (-> (heartbeat! config)
      (.then (fn [{:keys [status probe]}]
               (reset! heartbeat-failures 0)
               (when-not (= 201 status)
                 (println "[join] heartbeat rejected:" status))
               (when-not (:ready? probe)
                 (println "[join] local model not ready; advertised ready=false"))
               (js/setTimeout #(heartbeat-loop! config) heartbeat-ms)))
      (.catch (fn [error]
                (schedule-after-failure! "heartbeat" heartbeat-failures error heartbeat-ms
                                         #(heartbeat-loop! config))))))

(defn -main [& args]
  (let [opts (parse-args args)
        env {"MURAKUMO_SERVICE_TOKEN" (aget js/process.env "MURAKUMO_SERVICE_TOKEN")
             "MURAKUMO_NODE_CACAO" (aget js/process.env "MURAKUMO_NODE_CACAO")
             "MURAKUMO_NODE_DID" (aget js/process.env "MURAKUMO_NODE_DID")
             "MURAKUMO_INFER_LOCAL_TOKEN" (aget js/process.env "MURAKUMO_INFER_LOCAL_TOKEN")
             "VLLM_API_KEY" (aget js/process.env "VLLM_API_KEY")
             "MURAKUMO_TRUST_TIER" (aget js/process.env "MURAKUMO_TRUST_TIER")}
        base (str/replace (or (:base opts) "https://api.murakumo.cloud") #"/+$" "")
        model (:model opts)
        node-name (or (:name opts) (some-> (.hostname os) (str/split #"\.") first) "murakumo-node")
        local-url (str/replace (or (:local-url opts) "http://localhost:11434/v1") #"/+$" "")
        poll-ms (or (some-> (:poll-ms opts) js/parseInt) 5000)
        heartbeat-ms (or (some-> (:heartbeat-ms opts) js/parseInt) 30000)
        slots (or (some-> (:slots opts) js/parseInt) 1)
        auth (control-auth opts env)
        local-token (local-auth-token opts env)
        did (node-did opts env node-name)
        config {:base base :auth auth :did did :node-name node-name :model model
                :engine "openai-compatible" :slots slots :busy? (atom false)
                :memory-bytes #(.freemem os)
                :local-url local-url :local-token local-token
                :poll-ms poll-ms :heartbeat-ms heartbeat-ms
                :poll-failures (atom 0) :heartbeat-failures (atom 0)}]
    (when (str/blank? (str model))
      (println "usage: nbb scripts/infer-join.cljs --model <id> [--base URL] [--name NODE] [--did DID] [--local-url URL] [--slots 1] [--poll-ms 5000] [--heartbeat-ms 30000] [--trust-tier awai-secure|community]")
      (js/process.exit 1))
    (when-not (valid-node-name? node-name)
      (println "--name must contain only A-Z, a-z, 0-9, dot, underscore, or dash (max 128).")
      (js/process.exit 1))
    (when-not auth
      (println "MURAKUMO_NODE_CACAO or MURAKUMO_SERVICE_TOKEN must be set (or --cacao/--token).")
      (js/process.exit 1))
    (when (and (= :cacao (:kind auth))
               (str/starts-with? did "did:key:pending-"))
      (println "MURAKUMO_NODE_DID (or --did) must name the CACAO issuer DID.")
      (js/process.exit 1))
    (println (str "[join] scope: own-fleet trust only -- no output verification for " job-kind " jobs."))
    (-> (api! base auth :post "/infer/nodes"
              {:node/did did :node/name node-name :node/tier "native"
               :node/trust-tier (node-trust-tier opts env)
               :node/caps {:engine "openai-compatible" :model model}
               :node/can [job-kind "full-shard"]})
        (.then (fn [{:keys [status body]}]
                 (println (str "[join] enrolled " node-name " (" did ") as native/"
                                    (node-trust-tier opts env)
                                    " -> " base " -- status " status))
                 (when-not (#{200 201} status) (println "[join]   " (pr-str body)))
                 (when (#{200 201} status)
                   (heartbeat-loop! config)
                   (loop! config)))))))
