;; murakumo.overlay.runtime — execution adapter boundary for murakumo-overlay.
;;
;; These adapters intentionally return a structured would-run result today. The
;; contract is stable enough for the CLI runner, tests, and the later socket/relay
;; implementation to share.
;;
;; W6 product-shell: default ports, known-adapter?, adapter-kind, endpoint-kind,
;; scheme-prefix-host via kotoba overlay_runtime_core. Adapter registry maps and
;; full URL regex parse stay host.

(ns murakumo.overlay.runtime
  (:require #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :overlay-runtime)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(def default-relay-port
  #?(:clj (long (o 'default-relay-port []))
     :cljs 4701))

(def default-web-port
  #?(:clj (long (o 'default-web-port []))
     :cljs 443))

(def default-quic-port
  #?(:clj (long (o 'default-quic-port []))
     :cljs 4001))

(def default-port-by-kind
  #?(:clj {:quic (long (o 'default-port-for-kind ["quic"]))
           :webrtc (long (o 'default-port-for-kind ["webrtc"]))
           :webtransport (long (o 'default-port-for-kind ["webtransport"]))
           :relay (long (o 'default-port-for-kind ["relay"]))}
     :cljs {:quic default-quic-port
            :webrtc default-web-port
            :webtransport default-web-port
            :relay default-relay-port}))

(def adapters
  {"murakumo.runtime.relay"
   {:kind #?(:clj (keyword (o 'adapter-kind ["murakumo.runtime.relay"]))
             :cljs :relay-runtime)
    :status :placeholder
    :opens :relay-listener}

   "murakumo.runtime.quic"
   {:kind #?(:clj (keyword (o 'adapter-kind ["murakumo.runtime.quic"]))
             :cljs :quic)
    :status :placeholder
    :opens :identity-stream}

   "murakumo.runtime.webrtc"
   {:kind #?(:clj (keyword (o 'adapter-kind ["murakumo.runtime.webrtc"]))
             :cljs :webrtc)
    :status :placeholder
    :opens :browser-identity-stream}

   "murakumo.runtime.webtransport"
   {:kind #?(:clj (keyword (o 'adapter-kind ["murakumo.runtime.webtransport"]))
             :cljs :webtransport)
    :status :placeholder
    :opens :browser-identity-stream}

   "murakumo.runtime.relay-client"
   {:kind #?(:clj (keyword (o 'adapter-kind ["murakumo.runtime.relay-client"]))
             :cljs :relay)
    :status :placeholder
    :opens :relayed-identity-stream}})

(defn adapter [name]
  (get adapters name))

(defn adapter-records []
  (->> adapters
       (mapv (fn [[name spec]]
               (assoc spec :adapter name)))))

(defn known-adapter?
  "JVM: kotoba `known-adapter?`."
  [name]
  #?(:clj (= 1 (o 'known-adapter? [(str name)]))
     :cljs (contains? adapters name)))

(defn parse-int [value]
  #?(:clj (Integer/parseInt value)
     :cljs (js/parseInt value 10)))

(defn scheme-host
  "Host between `://` and next `:` or `/` or end.
   JVM: kotoba `scheme-prefix-host`."
  [url]
  #?(:clj (o 'scheme-prefix-host [(str url)])
     :cljs
     (when-let [[_ host] (re-matches #"[a-zA-Z][a-zA-Z0-9+.-]*://([^/:]+).*"
                                     (str url))]
       host)))

(defn relay-url-parts [url]
  (when-let [[_ host port path] (re-matches #"relay://([^/:]+)(?::([0-9]+))?(/.*)?"
                                            (str url))]
    {:host #?(:clj (let [h (scheme-host url)]
                     (if (and (string? h) (seq h)) h host))
              :cljs host)
     :port (when port (parse-int port))
     :path path}))

(defn endpoint-url-parts [url]
  (when-let [[_ scheme host port path] (re-matches #"([a-zA-Z][a-zA-Z0-9+.-]*)://([^/:]+)(?::([0-9]+))?(/.*)?"
                                                   (str url))]
    {:scheme scheme
     :host #?(:clj (let [h (scheme-host url)]
                     (if (and (string? h) (seq h)) h host))
              :cljs host)
     :port (when port (parse-int port))
     :path path}))

(defn relay-listen-spec
  "Derive the host listener settings for a relay session.

   `relay://name:port` advertises `name:port`, while the local process binds on
   `0.0.0.0` by default so public DNS names do not have to resolve locally.
   Tests can override with `:bind-host` in the session."
  [session]
  (let [{:keys [host port]} (relay-url-parts (:url session))]
    {:bind-host (or (:bind-host session) "0.0.0.0")
     :advertise-host host
     :port (or (:port session) port default-relay-port)
     :transports (vec (:transports session))}))

(defn dial-connect-spec
  "Derive the host/port a dial runtime should probe for a session.

   Direct sessions use `:direct`; relay-client sessions use `:relay`. This is a
   reachability probe for the runtime boundary, not full transport framing yet."
  ([session] (dial-connect-spec session nil))
  ([session endpoint-key]
   (let [endpoint-key (or endpoint-key (if (:relay session) :direct :direct))
         endpoint (get session endpoint-key)
         {:keys [host port path]} (endpoint-url-parts (:endpoint endpoint))
         kind (:kind endpoint)]
     {:endpoint endpoint-key
      :kind kind
      :transport (:transport endpoint)
      :host host
      :port (or port (get default-port-by-kind kind
                         #?(:clj (long (o 'default-port-for-kind
                                          [(name (or kind :unknown))]))
                            :cljs 0)))
      :path path
      :overlay (:overlay session)
      :node (:node session)
      :name (:name session)
      :principal (:principal session)})))

(defn execute-step
  "Execute one dispatched overlay step.

   Until the socket runtimes land, this returns the exact runtime boundary a real
   adapter must satisfy: adapter identity, requested argv, normalized session, and
   an explicit placeholder mode."
  [step]
  (let [adapter-name (:adapter step)
        adapter-spec (adapter adapter-name)]
    (if-not #?(:clj (known-adapter? adapter-name)
               :cljs adapter-spec)
      {:ok? false
       :mode :adapter-missing
       :adapter adapter-name
       :reason :unknown-adapter
       :argv (:argv step)}
      {:ok? true
       :mode :would-run
       :adapter adapter-name
       :runtime (:runtime step)
       :opens (:opens adapter-spec)
       :status (:status adapter-spec)
       :listen (when (= "murakumo.runtime.relay" adapter-name)
                 (relay-listen-spec (:session step)))
       :connect (when (#{"murakumo.runtime.quic"
                         "murakumo.runtime.webrtc"
                         "murakumo.runtime.webtransport"
                         "murakumo.runtime.relay-client"}
                       adapter-name)
                  (dial-connect-spec (:session step)
                                     (if (= "murakumo.runtime.relay-client" adapter-name)
                                       :relay
                                       :direct)))
       :argv (:argv step)
       :session (:session step)})))
