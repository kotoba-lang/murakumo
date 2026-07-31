;; murakumo.persist — portable Datom/atproto persistence data helpers.
;;
;; W6 product-shell + T6.4: constants + rkey/uri/url/write-ok? + curl/auth/
;; operation field helpers require the shipped `:persist` KIR on **every**
;; platform. Host pure mirrors are gone — cljs/nbb must preload shipped KIR
;; (resources/ via nbb cwd, register-kir!, or set-resource-loader!) before
;; requiring this ns (ADR-260731-w6-t64-persist-mirror-delete).
;; Envelope maps + graph-cid hashing stay host.

(ns murakumo.persist
  (:require [murakumo.dash.state :as dash-state]
            [murakumo.identity :as identity]
            [murakumo.reconcile.plan :as reconcile-plan]
            [murakumo.tunnel :as tunnel]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :persist)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(defn- o-record
  "T5.2: structural host map → call-record (requires shipped oracle)."
  [export host-map field-specs]
  (oracle/require-ready! oid)
  (oracle/call-record oid export host-map field-specs))

;; ── constants (oracle SSoT) ────────────────────────────────────────────

(def repo-authority
  (o 'repo-authority []))

(def fleet-graph-name
  (o 'fleet-graph-name []))

(def snapshot-collection
  (o 'snapshot-collection []))

(def reconcile-collection
  (o 'reconcile-collection []))

(def snapshot-local-port
  (oracle/i64->host (o 'snapshot-local-port [])))

(def reconcile-local-port
  (oracle/i64->host (o 'reconcile-local-port [])))

(def forward-settle-ms
  (oracle/i64->host (o 'forward-settle-ms [])))

(def operation-create
  (o 'operation-create []))

(def write-ok-marker
  (o 'write-ok-marker []))

(def auth-bearer-prefix
  (o 'auth-bearer-prefix []))

(def content-type-json-header
  (o 'content-type-json-header []))

(def curl-timeout-s
  (oracle/i64->host (o 'curl-timeout-s [])))

(def curl-method-post
  (o 'curl-method-post []))

(def xrpc-repo-write-path
  (o 'xrpc-repo-write-path []))

(defn auth-header
  "Authorization Bearer header value. Kotoba `auth-header` (required)."
  [token]
  (o-record 'auth-header {:token token} [[:token :string]]))

(defn fleet-graph-cid []
  (identity/graph-cid fleet-graph-name))

(def ^:private rkey-schema
  "T5.2 native guest record for snapshot/reconcile rkeys."
  [:record :persist/rkey [[:millis :i64] [:seq-n :i64]]])

(def ^:private uri-schema
  [:record :persist/uri [[:collection :string] [:rkey :string]]])

(defn repo-uri
  "Build the AT URI for an append-only murakumo record.
   Kotoba `repo-uri` (required). T5.2 native: single :persist/uri argument."
  [collection rkey]
  (o-record 'repo-uri
            {:uri (oracle/record uri-schema
                                 {:collection collection :rkey rkey})}
            [[:uri :raw]]))

(defn snapshot-rkey
  "Kotoba `snapshot-rkey` (required). T5.2 native: single :persist/rkey argument."
  [millis sequence-number]
  (o-record 'snapshot-rkey
            {:rkey (oracle/record rkey-schema
                                  {:millis millis :seq-n sequence-number})}
            [[:rkey :raw]]))

(defn reconcile-rkey
  "Kotoba `reconcile-rkey` (required). T5.2 native: single :persist/rkey argument."
  [millis sequence-number]
  (o-record 'reconcile-rkey
            {:rkey (oracle/record rkey-schema
                                  {:millis millis :seq-n sequence-number})}
            [[:rkey :raw]]))

(defn repo-write-envelope
  "Build the pure repo.write payload before host-side JSON encoding.
   `:operation` from kotoba `operation-create`."
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
   Kotoba `repo-write-url` (required). T5.2: structural map → call-record."
  [local-port]
  (o-record 'repo-write-url
            {:local-port local-port}
            [[:local-port :i64]]))

(defn repo-write-curl-argv
  "argv for POSTing a repo.write envelope through a local forward.
   Method/timeout/headers via kotoba pure constants."
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
   Kotoba `write-ok?` (required)."
  [out]
  (oracle/bool->host (o-record 'write-ok? {:out out} [[:out :string]])))
