;; murakumo.persist — portable Datom/atproto persistence data helpers.
;;
;; W6 product-shell authority (ADR-260728-w6-persist-oracle-authority +
;; ADR-260728-w6-persist-envelope-pure-oracle):
;; constants + rkey/uri/url/write-ok? + curl/auth/operation field helpers DELEGATE
;; to precompiled persist_core.kir.edn when oracle is loadable (JVM or cljs/nbb).
;; Envelope maps + graph-cid hashing stay host. cljs mirrors remain fallback.

(ns murakumo.persist
  (:require [murakumo.dash.state :as dash-state]
            [murakumo.identity :as identity]
            [murakumo.reconcile.plan :as reconcile-plan]
            [murakumo.tunnel :as tunnel]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :persist)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "Run oracle body; on failure (e.g. cljs KIR i64-str substring) use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn- oracle-const
  "Load-time constant from oracle export, or mirror."
  [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(defn- oracle-i64-const
  [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid export []))
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

;; ── host-mirror pure helpers ───────────────────────────────────────────

(def ^:private mirror-repo-authority "did:web:etzhayyim.com:murakumo")
(def ^:private mirror-fleet-graph-name "murakumo-fleet")
(def ^:private mirror-snapshot-collection "com.murakumo.fleet.snapshot")
(def ^:private mirror-reconcile-collection "com.murakumo.fleet.reconcile")
(def ^:private mirror-snapshot-local-port 18099)
(def ^:private mirror-reconcile-local-port 18098)
(def ^:private mirror-forward-settle-ms 400)

(defn- mirror-repo-uri [collection rkey]
  (str "at://" mirror-repo-authority "/" collection "/" rkey))

(defn- mirror-snapshot-rkey [millis sequence-number]
  (str "snap-" millis "-" sequence-number))

(defn- mirror-reconcile-rkey [millis sequence-number]
  (str "rec-" millis "-" sequence-number))

(defn- mirror-repo-write-url [local-port]
  (str "http://localhost:" local-port
       "/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write"))

(defn- mirror-write-ok? [out]
  (some? (re-find #"\"status\":\"ok\"" (str out))))

(def ^:private mirror-operation-create "create")
(def ^:private mirror-write-ok-marker "\"status\":\"ok\"")
(def ^:private mirror-auth-bearer-prefix "Authorization: Bearer ")
(def ^:private mirror-content-type-json-header "content-type: application/json")
(def ^:private mirror-curl-timeout-s 6)
(def ^:private mirror-curl-method-post "POST")
(def ^:private mirror-xrpc-repo-write-path
  "/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write")

(defn- mirror-auth-header [token]
  (str mirror-auth-bearer-prefix token))

;; ── dual-source constants ──────────────────────────────────────────────

(def repo-authority
  (oracle-const 'repo-authority mirror-repo-authority))

(def fleet-graph-name
  (oracle-const 'fleet-graph-name mirror-fleet-graph-name))

(def snapshot-collection
  (oracle-const 'snapshot-collection mirror-snapshot-collection))

(def reconcile-collection
  (oracle-const 'reconcile-collection mirror-reconcile-collection))

(def snapshot-local-port
  (oracle-i64-const 'snapshot-local-port mirror-snapshot-local-port))

(def reconcile-local-port
  (oracle-i64-const 'reconcile-local-port mirror-reconcile-local-port))

(def forward-settle-ms
  (oracle-i64-const 'forward-settle-ms mirror-forward-settle-ms))

(def operation-create
  (oracle-const 'operation-create mirror-operation-create))

(def write-ok-marker
  (oracle-const 'write-ok-marker mirror-write-ok-marker))

(def auth-bearer-prefix
  (oracle-const 'auth-bearer-prefix mirror-auth-bearer-prefix))

(def content-type-json-header
  (oracle-const 'content-type-json-header mirror-content-type-json-header))

(def curl-timeout-s
  (oracle-i64-const 'curl-timeout-s mirror-curl-timeout-s))

(def curl-method-post
  (oracle-const 'curl-method-post mirror-curl-method-post))

(def xrpc-repo-write-path
  (oracle-const 'xrpc-repo-write-path mirror-xrpc-repo-write-path))

(defn auth-header
  "Authorization Bearer header value. Kotoba `auth-header` when ready."
  [token]
  (try-oracle
   #(o 'auth-header [(str token)])
   #(mirror-auth-header token)))

(defn fleet-graph-cid []
  (identity/graph-cid fleet-graph-name))

(defn repo-uri
  "Build the AT URI for an append-only murakumo record.
   Kotoba `repo-uri` when oracle ready."
  [collection rkey]
  (try-oracle
   #(o 'repo-uri [(str collection) (str rkey)])
   #(mirror-repo-uri collection rkey)))

(defn snapshot-rkey
  "Kotoba `snapshot-rkey` when oracle ready (falls back if KIR i64-str fails on cljs)."
  [millis sequence-number]
  (try-oracle
   #(o 'snapshot-rkey [(oracle/as-i64 millis) (oracle/as-i64 sequence-number)])
   #(mirror-snapshot-rkey millis sequence-number)))

(defn reconcile-rkey
  "Kotoba `reconcile-rkey` when oracle ready."
  [millis sequence-number]
  (try-oracle
   #(o 'reconcile-rkey [(oracle/as-i64 millis) (oracle/as-i64 sequence-number)])
   #(mirror-reconcile-rkey millis sequence-number)))

(defn repo-write-envelope
  "Build the pure repo.write payload before host-side JSON encoding.
   `:operation` from kotoba `operation-create` when ready."
  [graph collection rkey record]
  {:graph graph
   :uri (repo-uri collection rkey)
   :operation operation-create
   :cid (identity/graph-cid rkey)
   :record record})

(defn snapshot-write-envelope
  "Build the repo.write envelope for a dashboard snapshot record."
  [rkey snapshot snapshot-json]
  (repo-write-envelope (fleet-graph-cid)
                       snapshot-collection
                       rkey
                       (dash-state/snapshot-record snapshot snapshot-json)))

(defn reconcile-write-envelope
  "Build the repo.write envelope for a reconcile plan record."
  [rkey plan plan-json]
  (repo-write-envelope (fleet-graph-cid)
                       reconcile-collection
                       rkey
                       (reconcile-plan/reconcile-record plan plan-json)))

(defn snapshot-write-plan
  "Pure write plan for persisting one dashboard snapshot."
  [millis sequence-number snapshot snapshot-json]
  (let [rkey (snapshot-rkey millis sequence-number)]
    {:local-port snapshot-local-port
     :rkey rkey
     :envelope (snapshot-write-envelope rkey snapshot snapshot-json)}))

(defn reconcile-write-plan
  "Pure write plan for persisting one reconcile result."
  [millis sequence-number plan plan-json]
  (let [rkey (reconcile-rkey millis sequence-number)]
    {:local-port reconcile-local-port
     :rkey rkey
     :envelope (reconcile-write-envelope rkey plan plan-json)}))

(defn repo-write-url
  "Local forwarded endpoint for kotoba atproto repo.write.
   Kotoba `repo-write-url` when oracle ready (try/catch for cljs i64-str)."
  [local-port]
  (try-oracle
   #(o 'repo-write-url [(oracle/as-i64 local-port)])
   #(mirror-repo-write-url local-port)))

(defn repo-write-curl-argv
  "argv for POSTing a repo.write envelope through a local forward.
   Method/timeout/headers via kotoba pure constants when ready."
  [local-port token body]
  ["curl" "-s" "-m" (str curl-timeout-s) "-X" curl-method-post
   (repo-write-url local-port)
   "-H" (auth-header token)
   "-H" content-type-json-header
   "-d" body])

(defn write-forward-command
  "Shell command to ensure the local forward for a write plan."
  [write-plan remote-port host]
  (tunnel/ensure-forward-command (:local-port write-plan) remote-port host))

(defn write-curl-argv
  "argv for POSTing an encoded write-plan envelope."
  [write-plan token body]
  (repo-write-curl-argv (:local-port write-plan) token body))

(defn write-ok?
  "True when repo.write stdout contains an ok status.
   Kotoba `write-ok?` when oracle ready."
  [out]
  (try-oracle
   #(oracle/bool->host (o 'write-ok? [(str out)]))
   #(mirror-write-ok? out)))
