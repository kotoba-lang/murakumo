;; murakumo.persist — portable Datom/atproto persistence data helpers.

(ns murakumo.persist
  (:require [murakumo.dash.state :as dash-state]
            [murakumo.identity :as identity]
            [murakumo.reconcile.plan :as reconcile-plan]
            [murakumo.tunnel :as tunnel]))

(def repo-authority "did:web:etzhayyim.com:murakumo")

(def fleet-graph-name "murakumo-fleet")

(def snapshot-collection "com.murakumo.fleet.snapshot")

(def reconcile-collection "com.murakumo.fleet.reconcile")

(def snapshot-local-port 18099)

(def reconcile-local-port 18098)

(def forward-settle-ms 400)

(defn fleet-graph-cid []
  (identity/graph-cid fleet-graph-name))

(defn repo-uri
  "Build the AT URI for an append-only murakumo record."
  [collection rkey]
  (str "at://" repo-authority "/" collection "/" rkey))

(defn snapshot-rkey [millis sequence-number]
  (str "snap-" millis "-" sequence-number))

(defn reconcile-rkey [millis sequence-number]
  (str "rec-" millis "-" sequence-number))

(defn repo-write-envelope
  "Build the pure repo.write payload before host-side JSON encoding."
  [graph collection rkey record]
  {:graph graph
   :uri (repo-uri collection rkey)
   :operation "create"
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
  "Local forwarded endpoint for kotoba atproto repo.write."
  [local-port]
  (str "http://localhost:" local-port "/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write"))

(defn repo-write-curl-argv
  "argv for POSTing a repo.write envelope through a local forward."
  [local-port token body]
  ["curl" "-s" "-m" "6" "-X" "POST"
   (repo-write-url local-port)
   "-H" (str "Authorization: Bearer " token)
   "-H" "content-type: application/json"
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
  "True when repo.write stdout contains an ok status."
  [out]
  (some? (re-find #"\"status\":\"ok\"" (str out))))
