;; murakumo.fleet.inventory — portable fleet inventory helpers.
;;
;; This is the .cljc source of truth for selector/defaulting logic and portable
;; parsing of host inventory command output. Shell execution stays in murakumo.fleet.
;;
;; W6 product-shell authority (ADR-260728-w6-fleet-inventory-oracle-authority):
;; pure port/url/selector/offline helpers DELEGATE to precompiled
;; kotoba/fleet_inventory_core.kotoba KIR when oracle is loadable (JVM or
;; cljs/nbb — ADR-260728-w6-cljs-oracle-load). Vector folds stay host.
;; cljs mirrors remain as fallback when oracle is not ready.

(ns murakumo.fleet.inventory
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :fleet-inventory)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- mirror-node-port [fleet node]
  (or (:port node) (:fleet/port fleet) 8077))

(defn- mirror-health-url [port]
  (str "http://localhost:" port "/health"))

(defn node-port
  "Resolve a node's control HTTP port, defaulting to the fleet port, then 8077.
   Kotoba `resolve-port` when oracle ready (Product Value ABI optional ports)."
  [fleet node]
  (if (oracle-ready?)
    (oracle/i64->host
     (o 'resolve-port
        [(oracle/option-i64 (:port node))
         (oracle/option-i64 (:fleet/port fleet))]))
    (mirror-node-port fleet node)))

(defn node-health-url
  "Node-local health URL for the control HTTP port.
   Kotoba `health-url` when oracle ready (falls back if KIR string-from-i64
   is unavailable on a runtime — e.g. some cljs kir builds)."
  [fleet node]
  (let [port (node-port fleet node)]
    (if (oracle-ready?)
      (try
        (o 'health-url [(oracle/as-i64 port)])
        (catch #?(:clj Exception :cljs :default) _
          (mirror-health-url port)))
      (mirror-health-url port))))

(defn select
  "Resolve a node selector string to node maps.

   nil or \"all\" selects every node; otherwise accepts a comma-separated list of
   node names. Unknown names are ignored, matching the original CLI behaviour.
   Kotoba selector helpers when oracle ready."
  [fleet sel]
  (let [nodes (:nodes fleet)]
    (if (oracle-ready?)
      (if (= 1 (oracle/i64->host (o 'selector-is-all? [(str (or sel ""))])))
        nodes
        (filter #(= 1 (oracle/i64->host
                       (o 'selector-wants-name?
                          [(str sel) (str (:name %))])))
                nodes))
      (if (or (nil? sel) (= sel "all"))
        nodes
        (let [want (set (str/split sel #","))]
          (filter #(want (:name %)) nodes))))))

(defn node-named
  "Return the first node with `name`, or nil."
  [fleet name]
  (first (filter #(= name (:name %)) (:nodes fleet))))

(defn parse-tailscale-status
  "Parse `tailscale status` stdout into tailscale-name -> reachability metadata.
   Offline detection via kotoba `line-has-offline?` when oracle ready."
  [out]
  (into {}
        (for [line (str/split-lines (str out))
              :let [cols (str/split (str/trim line) #"\s+")]
              :when (>= (count cols) 4)]
          [(nth cols 1) {:ip (nth cols 0)
                         :online? (if (oracle-ready?)
                                    (not= 1 (oracle/i64->host
                                             (o 'line-has-offline? [(str line)])))
                                    (not (str/includes? line "offline")))}])))

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
