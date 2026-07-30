;; murakumo.fleet.inventory — portable fleet inventory helpers.
;;
;; This is the .cljc source of truth for selector/defaulting logic and portable
;; parsing of host inventory command output. Shell execution stays in murakumo.fleet.
;;
;; W6 product-shell + T6.4: pure port/url/selector/offline helpers + tokens
;; require the shipped `:fleet-inventory` KIR on **every** platform. Host pure
;; mirrors are gone — cljs/nbb must preload shipped KIR (resources/ via nbb cwd,
;; register-kir!, or set-resource-loader!) before requiring this ns
;; (ADR-260731-w6-t64-fleet-inv-mirror-delete).
;; Vector folds stay host.

(ns murakumo.fleet.inventory
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :fleet-inventory)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

;; ── residual selector / offline / health URL tokens ──────────────────

(def default-control-port
  "Default control HTTP port when node/fleet port absent. Kotoba SSoT."
  (oracle/i64->host (o 'default-control-port [])))

(def selector-all
  "Selector token that selects every node. Kotoba SSoT."
  (o 'selector-all []))

(def offline-token
  "tailscale status offline marker. Kotoba SSoT."
  (o 'offline-token []))

(def health-url-prefix
  "Health URL host prefix. Kotoba SSoT."
  (o 'health-url-prefix []))

(def health-url-path
  "Health URL path suffix. Kotoba SSoT."
  (o 'health-url-path []))

(def selector-join-sep
  "CSV separator between selector node names. Kotoba SSoT."
  (o 'selector-join-sep []))

(defn node-port
  "Resolve a node's control HTTP port, defaulting to the fleet port, then 8077.
   Kotoba `resolve-port` (Product Value ABI optional ports).
   T5.2: structural host map → call-record."
  [fleet node]
  (oracle/require-ready! oid)
  (oracle/i64->host
   (oracle/call-record
    oid 'resolve-port
    {:node-port (:port node)
     :fleet-port (:fleet/port fleet)}
    [[:node-port :option-i64]
     [:fleet-port :option-i64]])))

(defn node-health-url
  "Node-local health URL for the control HTTP port. Kotoba `health-url`."
  [fleet node]
  (let [port (node-port fleet node)]
    (o 'health-url [(oracle/as-i64 port)])))

(defn select
  "Resolve a node selector string to node maps.

   nil or \"all\" selects every node; otherwise accepts a comma-separated list of
   node names. Unknown names are ignored, matching the original CLI behaviour.
   Kotoba selector helpers required."
  [fleet sel]
  (let [nodes (:nodes fleet)]
    (if (oracle/bool->host (o 'selector-is-all? [(str (or sel ""))]))
      nodes
      (filter (fn [node]
                (oracle/bool->host
                 (o 'selector-wants-name?
                    [(str sel) (str (:name node))])))
              nodes))))

(defn node-named
  "Return the first node with `name`, or nil."
  [fleet name]
  (first (filter #(= name (:name %)) (:nodes fleet))))

(defn parse-tailscale-status
  "Parse `tailscale status` stdout into tailscale-name -> reachability metadata.
   Offline detection via kotoba `line-has-offline?`."
  [out]
  (into {}
        (for [line (str/split-lines (str out))
              :let [cols (str/split (str/trim line) #"\s+")]
              :when (>= (count cols) 4)]
          [(nth cols 1) {:ip (nth cols 0)
                         :online? (not (oracle/bool->host
                                        (o 'line-has-offline? [(str line)])))}])))

(defn tailscale-status-result
  "Normalise a `tailscale status` process result into inventory metadata."
  [{:keys [exit out]}]
  (if (zero? exit)
    (parse-tailscale-status out)
    {}))

(defn enrich
  "Merge tailscale reachability metadata into fleet nodes."
  [fleet tailscale-by-name]
  (update fleet :nodes
          (fn [nodes]
            (mapv (fn [node] (merge node (get tailscale-by-name (:name node) {})))
                  nodes))))
