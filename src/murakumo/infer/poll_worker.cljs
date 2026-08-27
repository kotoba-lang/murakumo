(ns murakumo.infer.poll-worker
  (:require [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:os" :as os]))

(def job-kind "host-large-model")

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

(defn- sha256-hex [s]
  (-> (.createHash crypto "sha256") (.update (str s)) (.digest "hex")))

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

(defn- api! [base token method path & [body]]
  (-> (js/fetch (str base path)
                (clj->js
                 (cond-> {:method (name method)
                          :headers (cond-> {"content-type" "application/json"}
                                     token (assoc "authorization" (str "Bearer " token)))}
                   body (assoc :body (json-body body)))))
      (.then response-body)))

(defn run-completion-with-fetch!
  "Run one local OpenAI-compatible completion. Resolves to a structured
   outcome for both HTTP and response-shape failures."
  [fetch-fn local-url local-token model prompt max-tokens]
  (let [t0 (js/Date.now)]
    (-> (fetch-fn (str local-url "/chat/completions")
                  #js {:method "POST"
                       :headers (clj->js (local-auth-headers local-token))
                       :body (js/JSON.stringify
                              #js {:model model
                                   :messages #js [#js {:role "user" :content (str prompt)}]
                                   :max_tokens (or max-tokens 512)})})
        (.then response-body)
        (.then (fn [{:keys [status body]}]
                 (let [text (get-in body [:choices 0 :message :content])]
                   (cond
                     (not (<= 200 status 299))
                     {:ok false :error (str "local inference HTTP " status ": " (pr-str body)) :ms 0}

                     text
                     {:ok true :text text :ms (- (js/Date.now) t0)}

                     :else
                     {:ok false :error (str "no completion in response: " (pr-str body)) :ms 0})))))))

(defn run-completion! [local-url local-token model prompt max-tokens]
  (run-completion-with-fetch! js/fetch local-url local-token model prompt max-tokens))

(defn- report-result! [base token did job-id outcome ms]
  (api! base token :post (str "/infer/queue/" job-id "/result")
        {:did did
         :output (if (:ok outcome) {:text (:text outcome)} {:error (:error outcome)})
         :ms ms}))

(defn- claim-and-run! [{:keys [base token did local-url local-token model]} job]
  (let [job-id (:job-id job)]
    (-> (api! base token :post (str "/infer/queue/" job-id "/claim") {:did did})
        (.then
         (fn [{:keys [status]}]
           (if (not= 201 status)
             (println (str "[join] job " job-id " already claimed, skipping"))
             (-> (run-completion! local-url local-token model
                                  (get-in job [:input :prompt])
                                  (get-in job [:input :max-tokens]))
                 (.then
                  (fn [outcome]
                    (-> (report-result! base token did job-id outcome (:ms outcome 0))
                        (.then
                         (fn [{:keys [status body]}]
                           (println (str "[join] job " job-id " -> "
                                         (if (:ok outcome) "ok" "error")
                                         " (result status " status ")"))
                           (when (= 201 status)
                             (println "[join]   settled:" (pr-str (:shares body))))))))))))))))

(defn- poll-once! [{:keys [base token] :as config}]
  (-> (api! base token :get "/infer/queue")
      (.then (fn [{:keys [body]}]
               (if-let [job (first body)]
                 (claim-and-run! config (select-keys job [:job-id :kind :input]))
                 (js/Promise.resolve nil))))
      (.catch (fn [error] (println "[join] poll error:" (str error))))))

(defn- loop! [{:keys [poll-ms] :as config}]
  (-> (poll-once! config)
      (.then (fn [_] (js/setTimeout #(loop! config) poll-ms)))))

(defn -main [& args]
  (let [opts (parse-args args)
        env {"MURAKUMO_SERVICE_TOKEN" (aget js/process.env "MURAKUMO_SERVICE_TOKEN")
             "MURAKUMO_INFER_LOCAL_TOKEN" (aget js/process.env "MURAKUMO_INFER_LOCAL_TOKEN")
             "VLLM_API_KEY" (aget js/process.env "VLLM_API_KEY")}
        base (str/replace (or (:base opts) "https://api.murakumo.cloud") #"/+$" "")
        model (:model opts)
        node-name (or (:name opts) (some-> (.hostname os) (str/split #"\.") first) "murakumo-node")
        local-url (str/replace (or (:local-url opts) "http://localhost:11434/v1") #"/+$" "")
        poll-ms (or (some-> (:poll-ms opts) js/parseInt) 5000)
        token (or (:token opts) (get env "MURAKUMO_SERVICE_TOKEN"))
        local-token (local-auth-token opts env)
        did (str "did:key:pending-" (sha256-hex node-name))
        config {:base base :token token :did did :model model
                :local-url local-url :local-token local-token :poll-ms poll-ms}]
    (when (str/blank? (str model))
      (println "usage: nbb scripts/infer-join.cljs --model <id> [--base URL] [--name NODE] [--local-url URL] [--poll-ms 5000]")
      (js/process.exit 1))
    (when (str/blank? (str token))
      (println "MURAKUMO_SERVICE_TOKEN must be set (or --token).")
      (js/process.exit 1))
    (println (str "[join] scope: own-fleet trust only -- no output verification for " job-kind " jobs."))
    (-> (api! base token :post "/infer/nodes"
              {:node/did did :node/name node-name :node/tier "native"
               :node/caps {:engine "openai-compatible" :model model}
               :node/can [job-kind "full-shard"]})
        (.then (fn [{:keys [status body]}]
                 (println (str "[join] enrolled " node-name " (" did ") as native -> " base " -- status " status))
                 (when-not (#{200 201} status) (println "[join]   " (pr-str body)))
                 (loop! config))))))
