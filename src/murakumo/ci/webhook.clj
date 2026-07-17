(ns murakumo.ci.webhook
  "Authenticated HTTP ingress for GitHub and Radicle CI events."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [murakumo.ci.broker :as broker]
            [murakumo.ci.source :as source]
            [org.httpkit.server :as http])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn- utf8-bytes [s] (.getBytes (str s) StandardCharsets/UTF_8))
(defn- hex [bs] (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bs)))

(defn github-signature [secret raw-body]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8-bytes secret) "HmacSHA256"))
    (str "sha256=" (hex (.doFinal mac (utf8-bytes raw-body))))))

(defn github-signature-valid? [secret raw-body supplied]
  (and (seq secret) (seq supplied)
       (MessageDigest/isEqual (utf8-bytes (github-signature secret raw-body))
                              (utf8-bytes supplied))))

(defn- response [status body]
  {:status status :headers {"content-type" "application/json"}
   :body (json/generate-string body)})

(defn handler
  "Create a Ring handler. `submit!` receives one normalized RunRequest.
   Radicle verification is mandatory and receives raw body, signature, headers."
  [{:keys [github-secret pipeline-digest submit! verify-radicle status!]}]
  (fn [request]
    (let [method (:request-method request) uri (:uri request)]
      (cond
        (and (= :get method) (= "/ci/v1/health" uri))
        (response 200 {:ok true})

        (and (= :get method) (re-matches #"/ci/v1/runs/[A-Za-z0-9._-]+" uri))
        (if status!
          (response 200 (status! (last (str/split uri #"/"))))
          (response 503 {:error "status_unavailable"}))

        (and (= :post method) (= "/ci/v1/events/github" uri))
        (let [raw (slurp (:body request))
              signature (get-in request [:headers "x-hub-signature-256"])]
          (if-not (github-signature-valid? github-secret raw signature)
            (response 401 {:error "invalid_signature"})
            (try
              (let [event (json/parse-string raw true)
                    run (source/github event pipeline-digest)]
                (submit! run)
                (response 202 {:run_id (:ci.run/id run) :status "queued"}))
              (catch Exception e
                (response 400 {:error "invalid_event" :message (ex-message e)})))))

        (and (= :post method) (= "/ci/v1/events/radicle" uri))
        (let [raw (slurp (:body request))
              signature (get-in request [:headers "x-radicle-signature"])]
          (if-not (and verify-radicle
                       (verify-radicle {:raw raw :signature signature
                                        :headers (:headers request)}))
            (response 401 {:error "invalid_signature"})
            (try
              (let [event (json/parse-string raw true)
                    run (source/radicle event pipeline-digest)]
                (submit! run)
                (response 202 {:run_id (:ci.run/id run) :status "queued"}))
              (catch Exception e
                (response 400 {:error "invalid_event" :message (ex-message e)})))))

        :else (response 404 {:error "not_found"})))))

(defn serve! [opts port]
  (http/run-server (handler opts) {:port port}))

(defn -main [& [port-arg]]
  (when-not port-arg
    (throw (ex-info "ci-gateway now requires a coordinator config path"
                    {:reason :missing-config-path})))
  ((requiring-resolve 'murakumo.ci.coordinator/-main) port-arg))
