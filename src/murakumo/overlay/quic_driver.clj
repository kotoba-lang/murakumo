;; murakumo.overlay.quic-driver — JVM Clojure QUIC transport driver.

(ns murakumo.overlay.quic-driver
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [murakumo.overlay.cert :as cert])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]
           [javax.net.ssl X509TrustManager]
           [tech.kwik.core QuicClientConnection QuicStream]
           [tech.kwik.core.log NullLogger]
           [tech.kwik.core.server ApplicationProtocolConnection
            ApplicationProtocolConnectionFactory ServerConnectionConfig
            ServerConnector]))

(def alpn "murakumo-overlay-quic/1")
(def default-timeout-ms 3000)

(defn parse-argv [args]
  (let [[action & flags] args]
    (loop [opts {:action (keyword action)}
           flags flags]
      (if (empty? flags)
        opts
        (let [[flag value & more] flags]
          (cond
            (= "--request-edn" flag) (recur (assoc opts :request (edn/read-string value)) more)
            (= "--timeout-ms" flag) (recur (assoc opts :timeout-ms (Long/parseLong (str value))) more)
            :else (recur (update opts :extra (fnil conj []) flag) (cons value more))))))))

(defn valid-request? [request]
  (boolean
   (and (map? request)
        (= "murakumo.overlay.adapter-request" (:type request))
        (= 1 (:version request))
        (:transport request)
        (:connect request))))

(defn result [request action mode ok? extra]
  (merge {:ok? (boolean ok?)
          :type "murakumo.overlay.quic-driver-result"
          :mode mode
          :action action
          :transport (or (:transport request) :quic)
          :connect (:connect request)}
         extra))

(defn invalid-request [action]
  {:ok? false
   :type "murakumo.overlay.quic-driver-result"
   :mode :invalid-request
   :action action
   :reason :missing-or-invalid-request})

(defn timeout-duration [timeout-ms]
  (Duration/ofMillis (long timeout-ms)))

(defn trust-all-manager []
  (reify X509TrustManager
    (checkClientTrusted [_ _chain _auth-type])
    (checkServerTrusted [_ _chain _auth-type])
    (getAcceptedIssuers [_] (make-array java.security.cert.X509Certificate 0))))

(defn client-builder [request timeout-ms]
  (let [{:keys [host port]} (:connect request)]
    (doto (QuicClientConnection/newBuilder)
      (.host host)
      (.port (int port))
      (.applicationProtocol alpn)
      (.connectTimeout (timeout-duration timeout-ms))
      (.maxIdleTimeout (timeout-duration timeout-ms))
      (.logger (NullLogger.))
      (.customTrustManager (trust-all-manager))
      (.preferIPv4)
      (.maxOpenPeerInitiatedBidirectionalStreams 8)
      (.maxOpenPeerInitiatedUnidirectionalStreams 8)
      (.defaultStreamReceiveBufferSize (Long/valueOf 65536)))))

(defn connect! [request timeout-ms]
  (let [conn (.build (client-builder request timeout-ms))]
    (.connect conn)
    conn))

(defn stream-reader [^QuicStream stream]
  (BufferedReader. (InputStreamReader. (.getInputStream stream) "UTF-8")))

(defn stream-writer [^QuicStream stream]
  (OutputStreamWriter. (.getOutputStream stream) "UTF-8"))

(defn write-line! [writer value]
  (.write writer (str value "\n"))
  (.flush writer))

(defn hello [request]
  {:type "murakumo.overlay.adapter-hello"
   :transport :quic
   :overlay (get-in request [:session :overlay])
   :node (get-in request [:session :node])
   :name (get-in request [:session :name])
   :target (get-in request [:connect :path])})

(defn ack [hello]
  {:type "murakumo.overlay.adapter-ack"
   :transport :quic
   :accepted? (= "murakumo.overlay.adapter-hello" (:type hello))
   :hello hello})

(defn check! [request timeout-ms]
  (try
    (let [conn (connect! request timeout-ms)]
      (.close conn)
      (result request :check :connected true {}))
    (catch Exception e
      (result request :check :connect-failed false
              {:reason :connect-error :message (.getMessage e)}))))

(defn dial! [request timeout-ms]
  (try
    (let [conn (connect! request timeout-ms)
          stream (.createStream conn true)
          writer (stream-writer stream)
          reader (stream-reader stream)
          hello (hello request)]
      (write-line! writer (pr-str hello))
      (let [ack-line (.readLine reader)
            ack (when ack-line (edn/read-string ack-line))]
        (.close conn)
        (result request :dial :adapter-stream
                (= "murakumo.overlay.adapter-ack" (:type ack))
                {:hello hello :ack ack})))
    (catch Exception e
      (result request :dial :stream-failed false
              {:reason :stream-error :message (.getMessage e)}))))

(defn cert-files [request]
  (let [cert (System/getenv "MURAKUMO_QUIC_CERT")
        key (System/getenv "MURAKUMO_QUIC_KEY")]
    (if (and (seq cert) (seq key))
      [(io/file cert) (io/file key)]
      (let [material (cert/ensure-quic-material! request)]
        [(:cert material) (:key material)]))))

(defn server-connector [request]
  (let [[cert key] (cert-files request)
        config (-> (ServerConnectionConfig/builder)
                   (.maxIdleTimeoutInSeconds 10)
                   (.maxOpenPeerInitiatedBidirectionalStreams 16)
                   (.maxOpenPeerInitiatedUnidirectionalStreams 16)
                   (.build))]
    (-> (ServerConnector/builder)
        (.withPort (int (get-in request [:connect :port])))
        (.withCertificate (io/input-stream cert) (io/input-stream key))
        (.withConfiguration config)
        (.withLogger (NullLogger.))
        (.build))))

(defn handle-stream! [request stream]
  (let [reader (stream-reader stream)
        writer (stream-writer stream)
        hello-line (.readLine reader)
        hello (when hello-line (edn/read-string hello-line))
        ack (ack hello)]
    (write-line! writer (pr-str ack))
    {:hello hello :ack ack}))

(defn register-app! [connector request delivered latch]
  (.registerApplicationProtocol
   connector
   alpn
   (reify ApplicationProtocolConnectionFactory
     (createConnection [_ _protocol _conn]
       (reify ApplicationProtocolConnection
         (acceptPeerInitiatedStream [_ stream]
           (try
             (deliver delivered (handle-stream! request stream))
             (catch Exception e
               (deliver delivered {:error (.getMessage e)}))
             (finally
               (.countDown latch)))))))))

(defn serve-once! [request timeout-ms]
  (try
    (let [connector (server-connector request)
          delivered (promise)
          latch (CountDownLatch. 1)]
      (try
        (register-app! connector request delivered latch)
        (.start connector)
        (println (pr-str {:ok? true
                          :type "murakumo.overlay.quic-listener"
                          :mode :listening
                          :transport :quic
                          :listen (select-keys (:connect request) [:host :port])}))
        (flush)
        (if (.await latch timeout-ms TimeUnit/MILLISECONDS)
          (let [{:keys [hello ack error]} @delivered]
            (if error
              (result request :serve-once :stream-failed false {:reason :stream-error :message error})
              (result request :serve-once :served (:accepted? ack) {:hello hello :ack ack})))
          (result request :serve-once :accept-timeout false {:reason :timeout}))
        (finally
          (.close connector))))
    (catch Exception e
      (result request :serve-once :listen-failed false
              {:reason :listen-error :message (.getMessage e)}))))

(defn serve! [request]
  (let [connector (server-connector request)
        delivered (promise)
        latch (CountDownLatch. 1)]
    (register-app! connector request delivered latch)
    (.start connector)
    (println (pr-str {:ok? true
                      :type "murakumo.overlay.quic-listener"
                      :mode :listening
                      :transport :quic
                      :listen (select-keys (:connect request) [:host :port])}))
    (flush)
    @(promise)))

(defn execute [{:keys [action request timeout-ms]}]
  (let [timeout-ms (or timeout-ms default-timeout-ms)]
    (if-not (valid-request? request)
      (invalid-request action)
      (case action
        :check (check! request timeout-ms)
        :dial (dial! request timeout-ms)
        :serve-once (serve-once! request timeout-ms)
        :serve (serve! request)
        (result request action :unknown-action false {:reason :unknown-action})))))

(defn -main [& args]
  (let [result (execute (parse-argv args))]
    (when result
      (println (pr-str result)))
    (when (false? (:ok? result))
      (System/exit 2))))
