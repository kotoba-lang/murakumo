;; murakumo.overlay.runtime — execution adapter boundary for murakumo-overlay.
;;
;; These adapters intentionally return a structured would-run result today. The
;; contract is stable enough for the CLI runner, tests, and the later socket/relay
;; implementation to share.
;;
;; W6 product-shell authority (ADR-260728-w6-overlay-runtime-tokens-pure-oracle):
;; default ports, known-adapter?, adapter-kind, scheme-prefix-host +
;; scheme/kind/adapter tokens DELEGATE to precompiled kotoba/overlay_runtime_core
;; when oracle is loadable (JVM classpath or cljs/nbb —
;; ADR-260728-w6-cljs-oracle-load).
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

(defn- oracle-str-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(defn- oracle-str-call [export args mirror]
  (try-oracle
   #(o export args)
   (fn [] mirror)))

;; ── host-mirror pure helpers + dual-source tokens ────────────────────

(def ^:private mirror-default-relay-port 4701)
(def ^:private mirror-default-web-port 443)
(def ^:private mirror-default-quic-port 4001)
(def ^:private mirror-scheme-quic "quic://")
(def ^:private mirror-scheme-webrtc "webrtc://")
(def ^:private mirror-scheme-relay "relay://")
(def ^:private mirror-scheme-webtransport "webtransport://")
(def ^:private mirror-kind-quic "quic")
(def ^:private mirror-kind-webrtc "webrtc")
(def ^:private mirror-kind-webtransport "webtransport")
(def ^:private mirror-kind-relay "relay")
(def ^:private mirror-kind-other "other")
(def ^:private mirror-adapter-relay "murakumo.runtime.relay")
(def ^:private mirror-adapter-quic "murakumo.runtime.quic")
(def ^:private mirror-adapter-webrtc "murakumo.runtime.webrtc")
(def ^:private mirror-adapter-webtransport "murakumo.runtime.webtransport")
(def ^:private mirror-adapter-relay-client "murakumo.runtime.relay-client")
(def ^:private mirror-adapter-kind-relay-runtime "relay-runtime")
(def ^:private mirror-adapter-kind-quic "quic")
(def ^:private mirror-adapter-kind-webrtc "webrtc")
(def ^:private mirror-adapter-kind-webtransport "webtransport")
(def ^:private mirror-adapter-kind-relay "relay")

(def scheme-quic (oracle-str-const 'scheme-quic mirror-scheme-quic))
(def scheme-webrtc (oracle-str-const 'scheme-webrtc mirror-scheme-webrtc))
(def scheme-relay (oracle-str-const 'scheme-relay mirror-scheme-relay))
(def scheme-webtransport
  (oracle-str-const 'scheme-webtransport mirror-scheme-webtransport))
(def kind-quic (oracle-str-const 'kind-quic mirror-kind-quic))
(def kind-webrtc (oracle-str-const 'kind-webrtc mirror-kind-webrtc))
(def kind-webtransport
  (oracle-str-const 'kind-webtransport mirror-kind-webtransport))
(def kind-relay (oracle-str-const 'kind-relay mirror-kind-relay))
(def kind-other (oracle-str-const 'kind-other mirror-kind-other))
(def adapter-relay (oracle-str-const 'adapter-relay mirror-adapter-relay))
(def adapter-quic (oracle-str-const 'adapter-quic mirror-adapter-quic))
(def adapter-webrtc (oracle-str-const 'adapter-webrtc mirror-adapter-webrtc))
(def adapter-webtransport
  (oracle-str-const 'adapter-webtransport mirror-adapter-webtransport))
(def adapter-relay-client
  (oracle-str-const 'adapter-relay-client mirror-adapter-relay-client))
(def adapter-kind-relay-runtime
  (oracle-str-const 'adapter-kind-relay-runtime mirror-adapter-kind-relay-runtime))
(def adapter-kind-quic
  (oracle-str-const 'adapter-kind-quic mirror-adapter-kind-quic))
(def adapter-kind-webrtc
  (oracle-str-const 'adapter-kind-webrtc mirror-adapter-kind-webrtc))
(def adapter-kind-webtransport
  (oracle-str-const 'adapter-kind-webtransport mirror-adapter-kind-webtransport))
(def adapter-kind-relay
  (oracle-str-const 'adapter-kind-relay mirror-adapter-kind-relay))

(defn- mirror-default-port-for-kind [kind]
  (case (name kind)
    "quic" mirror-default-quic-port
    "relay" mirror-default-relay-port
    ("webrtc" "webtransport") mirror-default-web-port
    0))

(defn- mirror-known-adapters []
  #{adapter-relay adapter-quic adapter-webrtc adapter-webtransport
    adapter-relay-client})

(defn- mirror-adapter-kind [name]
  (cond
    (= name adapter-relay) adapter-kind-relay-runtime
    (= name adapter-quic) adapter-kind-quic
    (= name adapter-webrtc) adapter-kind-webrtc
    (= name adapter-webtransport) adapter-kind-webtransport
    (= name adapter-relay-client) adapter-kind-relay
    :else ""))

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
  {adapter-relay
   {:kind (adapter-kind-kw adapter-relay)
    :status :placeholder
    :opens :relay-listener}

   adapter-quic
   {:kind (adapter-kind-kw adapter-quic)
    :status :placeholder
    :opens :identity-stream}

   adapter-webrtc
   {:kind (adapter-kind-kw adapter-webrtc)
    :status :placeholder
    :opens :browser-identity-stream}

   adapter-webtransport
   {:kind (adapter-kind-kw adapter-webtransport)
    :status :placeholder
    :opens :browser-identity-stream}

   adapter-relay-client
   {:kind (adapter-kind-kw adapter-relay-client)
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
   #(contains? (mirror-known-adapters) (str name))))

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
       :listen (when (= adapter-relay adapter-name)
                 (relay-listen-spec (:session step)))
       :connect (when (#{adapter-quic adapter-webrtc adapter-webtransport
                         adapter-relay-client}
                       adapter-name)
                  (dial-connect-spec (:session step)
                                     (if (= adapter-relay-client adapter-name)
                                       :relay
                                       :direct)))
       :argv (:argv step)
       :session (:session step)})))
