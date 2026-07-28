;; murakumo.overlay.runtime — execution adapter boundary for murakumo-overlay.
;;
;; These adapters intentionally return a structured would-run result today. The
;; contract is stable enough for the CLI runner, tests, and the later socket/relay
;; implementation to share.
;;
;; W6 product-shell authority:
;; default ports, known-adapter?, adapter-kind, scheme-prefix-host DELEGATE to
;; precompiled kotoba/overlay_runtime_core when oracle is loadable
;; (JVM classpath or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
;; Adapter registry maps and full URL regex parse stay host. cljs mirrors as fallback.

(ns murakumo.overlay.runtime
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-runtime)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "Run oracle body; on failure use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn- oracle-i64-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid export []))
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(defn- oracle-str-call [export args mirror]
  (try-oracle
   #(o export args)
   (fn [] mirror)))

;; ── host-mirror pure helpers ───────────────────────────────────────────

(def ^:private mirror-default-relay-port 4701)
(def ^:private mirror-default-web-port 443)
(def ^:private mirror-default-quic-port 4001)

(defn- mirror-default-port-for-kind [kind]
  (case (name kind)
    "quic" mirror-default-quic-port
    "relay" mirror-default-relay-port
    ("webrtc" "webtransport") mirror-default-web-port
    0))

(def ^:private mirror-known-adapters
  #{"murakumo.runtime.relay"
    "murakumo.runtime.quic"
    "murakumo.runtime.webrtc"
    "murakumo.runtime.webtransport"
    "murakumo.runtime.relay-client"})

(defn- mirror-adapter-kind [name]
  (case name
    "murakumo.runtime.relay" "relay-runtime"
    "murakumo.runtime.quic" "quic"
    "murakumo.runtime.webrtc" "webrtc"
    "murakumo.runtime.webtransport" "webtransport"
    "murakumo.runtime.relay-client" "relay"
    ""))

(defn- mirror-scheme-prefix-host [url]
  (when-let [[_ host] (re-matches #"[a-zA-Z][a-zA-Z0-9+.-]*://([^/:]+).*"
                                  (str url))]
    host))

;; ── dual-source constants ──────────────────────────────────────────────

(def default-relay-port
  (oracle-i64-const 'default-relay-port mirror-default-relay-port))

(def default-web-port
  (oracle-i64-const 'default-web-port mirror-default-web-port))

(def default-quic-port
  (oracle-i64-const 'default-quic-port mirror-default-quic-port))

(defn- port-for-kind [kind]
  (try-oracle
   #(oracle/i64->host (o 'default-port-for-kind [(name kind)]))
   #(mirror-default-port-for-kind kind)))

(def default-port-by-kind
  {:quic (port-for-kind :quic)
   :webrtc (port-for-kind :webrtc)
   :webtransport (port-for-kind :webtransport)
   :relay (port-for-kind :relay)})

(defn- adapter-kind-kw [name]
  (keyword
   (try-oracle
    #(o 'adapter-kind [(str name)])
    #(mirror-adapter-kind name))))

(def adapters
  {"murakumo.runtime.relay"
   {:kind (adapter-kind-kw "murakumo.runtime.relay")
    :status :placeholder
    :opens :relay-listener}

   "murakumo.runtime.quic"
   {:kind (adapter-kind-kw "murakumo.runtime.quic")
    :status :placeholder
    :opens :identity-stream}

   "murakumo.runtime.webrtc"
   {:kind (adapter-kind-kw "murakumo.runtime.webrtc")
    :status :placeholder
    :opens :browser-identity-stream}

   "murakumo.runtime.webtransport"
   {:kind (adapter-kind-kw "murakumo.runtime.webtransport")
    :status :placeholder
    :opens :browser-identity-stream}

   "murakumo.runtime.relay-client"
   {:kind (adapter-kind-kw "murakumo.runtime.relay-client")
    :status :placeholder
    :opens :relayed-identity-stream}})

(defn adapter [name]
  (get adapters name))

(defn adapter-records []
  (->> adapters
       (mapv (fn [[name spec]]
               (assoc spec :adapter name)))))

(defn known-adapter?
  "Kotoba `known-adapter?` when oracle ready."
  [name]
  (try-oracle
   #(= 1 (oracle/i64->host (o 'known-adapter? [(str name)])))
   #(contains? mirror-known-adapters (str name))))

(defn parse-int [value]
  #?(:clj (Integer/parseInt value)
     :cljs (js/parseInt value 10)))

(defn scheme-host
  "Host between `://` and next `:` or `/` or end.
   Kotoba `scheme-prefix-host` when oracle ready."
  [url]
  (let [s (str url)
        host (try-oracle
              #(o 'scheme-prefix-host [s])
              #(or (mirror-scheme-prefix-host s) ""))]
    (when (and (string? host) (seq host))
      host)))

(defn relay-url-parts [url]
  (when-let [[_ host port path] (re-matches #"relay://([^/:]+)(?::([0-9]+))?(/.*)?"
                                            (str url))]
    {:host (or (scheme-host url) host)
     :port (when port (parse-int port))
     :path path}))

(defn endpoint-url-parts [url]
  (when-let [[_ scheme host port path] (re-matches #"([a-zA-Z][a-zA-Z0-9+.-]*)://([^/:]+)(?::([0-9]+))?(/.*)?"
                                                   (str url))]
    {:scheme scheme
     :host (or (scheme-host url) host)
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
                         (port-for-kind (or kind :unknown))))
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
    (if-not (known-adapter? adapter-name)
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
