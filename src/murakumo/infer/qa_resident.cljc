(ns murakumo.infer.qa-resident
  "Dedicated, single-slot OpenAI-compatible ingress for isolated QA."
  (:require [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:path" :as path]
            ["node:stream/promises" :as streams]))

(def ^:private chat-path "/v1/chat/completions")
(def ^:private max-request-bytes 2097152)
(def ^:private max-ttl-ms 3600000)

(defn- config-error! [message]
  (throw (js/Error. (str "QA config: " message))))

(defn- port? [port]
  (and (js/Number.isSafeInteger port) (<= 1 port 65535)))

(defn- lstat [pathname]
  (try (.lstatSync fs pathname) (catch :default _ nil)))

(defn- owned-socket? [pathname uid]
  (let [entry (lstat pathname)]
    (and entry (.isSocket entry) (= uid (.-uid entry)))))

(defn- unix-backend! [socket]
  (let [posix (.-posix path)
        uid (when (.-getuid js/process) (.getuid js/process))]
    (when-not (and (string? socket) (.isAbsolute posix socket) (= socket (.normalize posix socket))
                   (str/ends-with? socket ".sock") (not (str/includes? socket "\u0000"))
                   (<= (.byteLength js/Buffer socket "utf8") 103))
      (config-error! "backend-socket must be a normalized absolute .sock pathname of at most 103 UTF-8 bytes"))
    (when-not (and (number? uid) (pos? uid) (= uid (.geteuid js/process)))
      (config-error! "backend-socket requires a non-root POSIX service UID"))
    (let [parent (.dirname posix socket)
          entry (lstat parent)]
      (when-not (and entry (.isDirectory entry) (= uid (.-uid entry))
                     (= 448 (bit-and (.-mode entry) 4095)))
        (config-error! "backend-socket parent must be a directory owned by the service UID with mode 0700"))
      (loop [ancestor (.dirname posix parent)]
        (let [entry (lstat ancestor)]
          ;; POSIX mode check. Deployment preflight also checks inherited ACLs.
          (when-not (and entry (.isDirectory entry) (contains? #{0 uid} (.-uid entry))
                         (zero? (bit-and (.-mode entry) 18)))
            (config-error! "backend-socket ancestors must be trusted directories with no group or other write access")))
        (let [next (.dirname posix ancestor)]
          (when-not (= next ancestor) (recur next)))))
    (when-not (owned-socket? socket uid)
      (config-error! "backend-socket must already be a Unix socket owned by the service UID"))
    {:options #js {:socketPath socket} :socket socket :uid uid}))

(defn- backend! [{:keys [backend backend-socket port] :as config}]
  (if (contains? config :backend-socket)
    (do (when (contains? config :backend)
          (config-error! "configure exactly one of backend-socket and backend"))
        (unix-backend! backend-socket))
    (let [backend-port (when (string? backend)
                         (some-> (re-matches #"http://127\.0\.0\.1:([1-9][0-9]{0,4})" backend)
                                 second js/Number))]
      (when-not (port? backend-port)
        (config-error! "backend must be http://127.0.0.1:PORT with an explicit port, or set backend-socket"))
      (when (= port backend-port) (config-error! "backend and listener ports must differ"))
      {:options #js {:hostname "127.0.0.1" :port backend-port}})))

(defn- load-config! [file]
  (let [raw (try (.readFileSync fs file "utf8")
                 (catch :default _ (config-error! "cannot read dedicated config file")))
        config (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                    (catch :default _ (config-error! "expected valid JSON")))
        {:keys [bind port model auth]} config]
    (when-not (map? config) (config-error! "expected a JSON object"))
    (when (some #(not (contains? #{:bind :port :backend :backend-socket :model :auth} %)) (keys config))
      (config-error! "unexpected field in dedicated config"))
    (when-not (= "127.0.0.1" bind) (config-error! "bind must be explicitly 127.0.0.1"))
    (when-not (port? port) (config-error! "port must be an integer from 1 to 65535"))
    (when-not (and (string? model) (not (str/blank? model)) (= model (str/trim model)))
      (config-error! "model must be an explicit nonempty ID"))
    (when-not (and (map? auth) (= #{:token-sha256 :expires-at-ms} (set (keys auth))))
      (config-error! "auth must contain token-sha256 and expires-at-ms"))
    (let [{:keys [token-sha256 expires-at-ms]} auth
          started (.now js/performance)
          now (js/Date.now)]
      (when-not (and (string? token-sha256) (re-matches #"[a-f0-9]{64}" token-sha256))
        (config-error! "auth.token-sha256 must be a 64-character lowercase hexadecimal SHA-256 digest"))
      (when-not (and (js/Number.isSafeInteger expires-at-ms) (< now expires-at-ms)
                     (<= expires-at-ms (+ now max-ttl-ms)))
        (config-error! "auth.expires-at-ms must be a future Unix timestamp in milliseconds within one hour"))
      {:bind bind :port port :model model :backend (backend! config)
       :auth {:digest (js/Buffer.from token-sha256 "hex")
              :expires-at-ms expires-at-ms
              :monotonic-deadline (+ started (- expires-at-ms now))}})))

(defn- live-credential? [{:keys [expires-at-ms monotonic-deadline]}]
  (and (< (js/Date.now) expires-at-ms) (< (.now js/performance) monotonic-deadline)))

(defn- authorized? [req {:keys [digest] :as auth}]
  (let [values (aget (.-headersDistinct req) "authorization")]
    (and (live-credential? auth)
         values (= 1 (.-length values))
         (when-let [[_ scheme token] (re-matches #"([A-Za-z]+) ([a-f0-9]{64})" (aget values 0))]
           (and (= "bearer" (str/lower-case scheme))
                (.timingSafeEqual crypto digest
                                  (.digest (.update (.createHash crypto "sha256") token "ascii"))))))))

(defn- error! [res status code]
  (when-not (or (.-destroyed res) (.-writableEnded res))
    (when (= status 401) (.setHeader res "www-authenticate" "Bearer"))
    (.writeHead res status #js {"content-type" "application/json" "cache-control" "no-store"
                               "connection" "close"})
    (.end res (js/JSON.stringify #js {:error code}))))

(defn- request-body! [req]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []
           bytes (atom 0)]
       (.on req "error" reject)
       (.once req "close" #(when-not (.-complete req) (reject (js/Error. "incomplete body"))))
       (.on req "data" (fn [chunk]
                         (if (> (swap! bytes + (.-length chunk)) max-request-bytes)
                           (do (.pause req) (resolve nil))
                           (.push chunks chunk))))
       (.on req "end" #(resolve (.concat js/Buffer chunks)))))))

(defn- object-with-fields? [value fields]
  (and (some? value) (= (.-prototype js/Object) (js/Object.getPrototypeOf value))
       (every? fields (array-seq (js/Object.keys value)))))

(defn- nonblank-text? [value]
  (and (string? value) (not (str/blank? value))))

(defn- nonempty-array-of? [predicate values]
  (and (js/Array.isArray values) (pos? (.-length values))
       (every? predicate (array-seq values))))

(defn- text-part? [part]
  (and (object-with-fields? part #{"type" "text"})
       (= "text" (aget part "type")) (string? (aget part "text"))))

(defn- text-content? [content]
  (or (string? content) (nonempty-array-of? text-part? content)))

(defn- assistant-text? [content]
  (or (nonblank-text? content)
      (and (nonempty-array-of? text-part? content)
           (some #(nonblank-text? (aget % "text")) (array-seq content)))))

(defn- function-call? [call]
  (when (and (object-with-fields? call #{"id" "type" "function"})
             (nonblank-text? (aget call "id")) (= "function" (aget call "type")))
    (let [function (aget call "function")]
      (and (object-with-fields? function #{"name" "arguments"})
           (nonblank-text? (aget function "name")) (string? (aget function "arguments"))))))

(defn- text-message? [message]
  (when (some? message)
    (let [role (aget message "role")
          content (aget message "content")]
      (case role
        ("system" "developer" "user")
        (and (object-with-fields? message #{"role" "content" "name"})
             (text-content? content)
             (or (not (js/Object.hasOwn message "name")) (nonblank-text? (aget message "name"))))
        "assistant"
        (and (object-with-fields? message #{"role" "content" "name" "tool_calls"})
             (or (not (js/Object.hasOwn message "name")) (nonblank-text? (aget message "name")))
             (if (js/Object.hasOwn message "tool_calls")
               (and (nonempty-array-of? function-call? (aget message "tool_calls"))
                    (or (nil? content) (text-content? content)))
               (assistant-text? content)))
        "tool"
        (and (object-with-fields? message #{"role" "content" "tool_call_id"})
             (text-content? content) (nonblank-text? (aget message "tool_call_id")))
        false))))

(defn- post! [{:keys [options socket uid]} body signal]
  (js/Promise.
   (fn [resolve reject]
     (when (and socket (not (owned-socket? socket uid)))
       (throw (js/Error. "backend socket unavailable")))
     (let [request-options (js/Object.assign
                            #js {:method "POST" :path chat-path :signal signal :agent false
                                 :headers #js {"content-type" "application/json"}} options)
           req (.request http request-options resolve)]
       (.once req "error" reject)
       (.end req (js/JSON.stringify body))))))

(defn- forward! [res backend body controller]
  (let [timeout (.timeout js/AbortSignal 480000)
        signal (.any js/AbortSignal #js [(.-signal controller) timeout])]
    (-> (post! backend body signal)
        (.then (fn [upstream]
                 (let [status (.-statusCode upstream)
                       headers (.-headers upstream)
                       content-type (some-> (aget headers "content-type") (str/split #";" 2)
                                            first str/trim str/lower-case)
                       encoding (aget headers "content-encoding")]
                   (if (and (<= 200 status 299) (not (contains? #{204 205} status))
                            (contains? #{"application/json" "text/event-stream"} content-type)
                            (or (nil? encoding) (= "identity" encoding)))
                     (do (.writeHead res status
                                     #js {"content-type" content-type "cache-control" "no-store"})
                         (.flushHeaders res)
                         (streams/pipeline upstream res #js {:signal signal}))
                     (do (.destroy upstream)
                         (error! res 502 "qa_backend_response"))))))
        (.catch (fn [_]
                  (cond
                    (.-headersSent res) (.destroy res)
                    (.-aborted timeout) (error! res 504 "qa_backend_timeout")
                    :else (error! res 502 "qa_backend_unavailable")))))))

(defn- run-request! [req res {:keys [auth backend model]} busy? expect-continue?]
  (let [controller (js/AbortController.)]
    (.once res "close" #(when-not (.-writableFinished res) (.abort controller)))
    (when expect-continue? (.writeContinue res))
    (-> (request-body! req)
        (.then (fn [bytes]
                 (if-not bytes
                   (error! res 413 "qa_body_too_large")
                   (let [body (try (js/JSON.parse (.toString bytes "utf8")) (catch :default _ nil))
                         messages (when body (aget body "messages"))]
                     (cond
                       (.-destroyed res) nil
                       (not (live-credential? auth)) (error! res 401 "unauthorized")
                       (not (nonempty-array-of? text-message? messages))
                       (error! res 400 "qa_text_messages_required")
                       (not= model (aget body "model")) (error! res 422 "qa_model_mismatch")
                       :else (forward! res backend body controller))))))
        (.catch (fn [_] (error! res 400 "qa_invalid_request")))
        (.finally #(reset! busy? false)))))

(defn run-command [args]
  (let [[flag file & extra] args]
    (when-not (and (= "--config" flag) (seq file) (empty? extra))
      (throw (js/Error. "resident serve-qa --config FILE")))
    (let [{:keys [bind port auth] :as config} (load-config! file)
          busy? (atom false)
          handle! (fn [req res expect-continue?]
                    (cond
                      (not (and (= "POST" (.-method req)) (= chat-path (.-url req))))
                      (error! res 404 "not_found")
                      (not (authorized? req auth)) (error! res 401 "unauthorized")
                      (not (compare-and-set! busy? false true)) (error! res 429 "qa_busy")
                      :else (run-request! req res config busy? expect-continue?)))
          server (.createServer http #js {:headersTimeout 10000 :requestTimeout 30000
                                         :connectionsCheckingInterval 1000 :maxHeaderSize 16384}
                                (fn [req res] (handle! req res false)))]
      ;; Retain every header within maxHeaderSize so a duplicate cannot be truncated.
      (set! (.-maxHeadersCount server) 0)
      (.on server "checkContinue" (fn [req res] (handle! req res true)))
      (.once server "error" (fn [error]
                              (.error js/console (case (.-code error)
                                                   "EADDRINUSE" "QA listener port is in use"
                                                   "EACCES" "QA listener port permission denied"
                                                   "QA listener could not start"))
                              (set! (.-exitCode js/process) 1)))
      (.listen server port bind
               #(.log js/console (js/JSON.stringify #js {:mode "serve-qa"
                                                         :listeners #js [#js {:bind bind :port port}]}))))))
