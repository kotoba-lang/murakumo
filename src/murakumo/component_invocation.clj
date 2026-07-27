(ns murakumo.component-invocation
  "Bounded loopback client for a resident kototama Component.

  The runtime signs every invocation receipt. This client pins that Ed25519
  key, verifies the exact embedded payload, and checks that the receipt is
  bound to the request before returning guest output to an actor."
  (:require [cheshire.core :as json]
            [ed25519.core :as ed])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.time Duration]))

(def default-max-response-bytes (* 256 1024))

(defn- reject [reason message data]
  (throw (ex-info message (assoc data :murakumo.component-invocation/reason reason))))

(defn- hex->bytes [value expected-bytes field]
  (when-not (and (string? value)
                 (= (* expected-bytes 2) (count value))
                 (re-matches #"[0-9a-f]+" value))
    (reject :invalid-receipt (str field " must be lowercase hex") {:field field}))
  (byte-array
   (map (fn [[a b]]
          (unchecked-byte (Integer/parseInt (str a b) 16)))
        (partition 2 value))))

(defn- bytes->hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- sha256 [^String value]
  (bytes->hex (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes value "UTF-8"))))

(defn- canonical-value [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[k v]] [(name k) (canonical-value v)]))
                       value)
    (vector? value) (mapv canonical-value value)
    (sequential? value) (mapv canonical-value value)
    :else value))

(defn- invocation-json [export params]
  ;; Rust reserializes InvocationRequest in struct field order, while nested
  ;; serde_json::Value objects use lexicographically ordered keys.
  (json/generate-string
   (array-map "export" export
              "params" (mapv canonical-value params))))

(defn- endpoint! [url]
  (let [uri (URI/create url)]
    (when-not (and (= "http" (.getScheme uri))
                   (contains? #{"127.0.0.1" "::1"} (.getHost uri))
                   (= "/v1/invoke" (.getPath uri))
                   (nil? (.getQuery uri))
                   (nil? (.getFragment uri))
                   (nil? (.getUserInfo uri)))
      (reject :invalid-endpoint
              "Resident Component invocation requires literal loopback /v1/invoke"
              {:url url}))
    uri))

(defn- read-bounded [input max-bytes]
  (with-open [input input]
    (let [bytes (.readNBytes input (inc max-bytes))]
      (when (> (alength bytes) max-bytes)
        (reject :oversize-response "Component invocation response is too large"
                {:max-bytes max-bytes}))
      (String. bytes "UTF-8"))))

(defn- verify-receipt! [receipt expected-public-key request-sha]
  (let [algorithm (get receipt "algorithm")
        body (get receipt "body")
        payload (get receipt "payload")
        public-key (get body "receipt-public-key")
        signature (get receipt "signature")]
    (when-not (and (= "ed25519" algorithm)
                   (map? body)
                   (string? payload)
                   (= body (json/parse-string payload))
                   (= "kototama.component-invocation-receipt/v1"
                      (get body "format"))
                   (= "ok" (get body "status"))
                   (= request-sha (get body "request-sha256"))
                   (= expected-public-key public-key))
      (reject :invalid-receipt "Component invocation receipt is not request-bound"
              {:body body}))
    (when-not (ed/verify
               (hex->bytes public-key 32 "receipt-public-key")
               (.getBytes payload "UTF-8")
               (hex->bytes signature 64 "signature"))
      (reject :invalid-signature "Component invocation receipt signature is invalid" {}))
    body))

(defn invoke!
  "Invoke EXPORT with JSON-compatible PARAMS on a loopback resident service.

  Options require :expected-public-key. The returned map is the verified
  receipt body; callers consume its `\"output\"` only after all checks pass."
  [url export params {:keys [expected-public-key timeout-ms max-response-bytes]
                      :or {timeout-ms 15000
                           max-response-bytes default-max-response-bytes}}]
  (when-not (and (string? export)
                 (re-matches #"[a-z0-9-]{1,128}" export)
                 (vector? params)
                 (pos-int? timeout-ms)
                 (pos-int? max-response-bytes)
                 (string? expected-public-key))
    (reject :invalid-request "Bounded invocation input and pinned public key are required" {}))
  (let [uri (endpoint! url)
        body (invocation-json export params)
        timeout (Duration/ofMillis timeout-ms)
        client (-> (HttpClient/newBuilder) (.connectTimeout timeout) .build)
        request (-> (HttpRequest/newBuilder uri)
                    (.timeout timeout)
                    (.header "content-type" "application/json")
                    (.header "accept" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
        response-body (read-bounded (.body response) max-response-bytes)]
    (when-not (<= 200 (.statusCode response) 299)
      (reject :invocation-rejected "Resident Component rejected invocation"
              {:status (.statusCode response)}))
    (let [receipt (try
                    (json/parse-string response-body)
                    (catch Exception _
                      (reject :invalid-response
                              "Resident Component response is not JSON" {})))]
      (verify-receipt! receipt expected-public-key (sha256 body)))))
