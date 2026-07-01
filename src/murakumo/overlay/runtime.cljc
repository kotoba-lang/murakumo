;; murakumo.overlay.runtime — execution adapter boundary for murakumo-overlay.
;;
;; These adapters intentionally return a structured would-run result today. The
;; contract is stable enough for the CLI runner, tests, and the later socket/relay
;; implementation to share.

(ns murakumo.overlay.runtime)

(def default-relay-port 4701)
(def default-web-port 443)
(def default-quic-port 4001)

(def default-port-by-kind
  {:quic default-quic-port
   :webrtc default-web-port
   :webtransport default-web-port
   :relay default-relay-port})

(def adapters
  {"murakumo.runtime.relay"
   {:kind :relay-runtime
    :status :placeholder
    :opens :relay-listener}

   "murakumo.runtime.quic"
   {:kind :quic
    :status :placeholder
    :opens :identity-stream}

   "murakumo.runtime.webrtc"
   {:kind :webrtc
    :status :placeholder
    :opens :browser-identity-stream}

   "murakumo.runtime.webtransport"
   {:kind :webtransport
    :status :placeholder
    :opens :browser-identity-stream}

   "murakumo.runtime.relay-client"
   {:kind :relay
    :status :placeholder
    :opens :relayed-identity-stream}})

(defn adapter [name]
  (get adapters name))

(defn adapter-records []
  (->> adapters
       (mapv (fn [[name spec]]
               (assoc spec :adapter name)))))

(defn known-adapter? [name]
  (contains? adapters name))

(defn parse-int [value]
  #?(:clj (Integer/parseInt value)
     :cljs (js/parseInt value 10)))

(defn relay-url-parts [url]
  (when-let [[_ host port path] (re-matches #"relay://([^/:]+)(?::([0-9]+))?(/.*)?"
                                            (str url))]
    {:host host
     :port (when port (parse-int port))
     :path path}))

(defn endpoint-url-parts [url]
  (when-let [[_ scheme host port path] (re-matches #"([a-zA-Z][a-zA-Z0-9+.-]*)://([^/:]+)(?::([0-9]+))?(/.*)?"
                                                   (str url))]
    {:scheme scheme
     :host host
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
      :port (or port (default-port-by-kind kind))
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
    (if-not adapter-spec
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
