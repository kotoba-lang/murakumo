;; murakumo.fleet.inventory — portable fleet inventory helpers.
;;
;; This is the .cljc source of truth for selector/defaulting logic and portable
;; parsing of host inventory command output. Shell execution stays in murakumo.fleet.
;;
;; W6 product-shell authority (ADR-260728-w6-fleet-inv-tokens-pure-oracle +
;; ADR-260728-w6-fleet-inventory-oracle-authority):
;; pure port/url/selector/offline helpers + selector/offline/health URL tokens
;; DELEGATE to precompiled kotoba/fleet_inventory_core.kotoba KIR when oracle
;; is loadable (JVM or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
;; Vector folds stay host. cljs mirrors remain as fallback when not ready.

(ns murakumo.fleet.inventory
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :fleet-inventory)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "JVM: require shipped KIR (T6.4). cljs: oracle when ready, else mirror."
  [thunk mirror-thunk]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid})))
       (thunk))
     :cljs
     (if (oracle-ready?)
       (try
         (thunk)
         (catch :default _
           (mirror-thunk)))
       (mirror-thunk))))

(defn- oracle-str-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export []))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/call oid export [])
         mirror)
       (catch :default _
         mirror))))

(defn- oracle-i64-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/i64->host (oracle/call oid export [])))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/i64->host (oracle/call oid export []))
         mirror)
       (catch :default _
         mirror))))

;; ── residual selector / offline / health URL tokens ──────────────────

(def ^:private mirror-default-control-port 8077)
(def ^:private mirror-selector-all "all")
(def ^:private mirror-offline-token "offline")
(def ^:private mirror-health-url-prefix "http://localhost:")
(def ^:private mirror-health-url-path "/health")
(def ^:private mirror-selector-join-sep ",")

(def default-control-port
  "Default control HTTP port when node/fleet port absent. Kotoba when ready."
  (oracle-i64-const 'default-control-port mirror-default-control-port))

(def selector-all
  "Selector token that selects every node. Kotoba when ready."
  (oracle-str-const 'selector-all mirror-selector-all))

(def offline-token
  "tailscale status offline marker. Kotoba when ready."
  (oracle-str-const 'offline-token mirror-offline-token))

(def health-url-prefix
  "Health URL host prefix. Kotoba when ready."
  (oracle-str-const 'health-url-prefix mirror-health-url-prefix))

(def health-url-path
  "Health URL path suffix. Kotoba when ready."
  (oracle-str-const 'health-url-path mirror-health-url-path))

(def selector-join-sep
  "CSV separator between selector node names. Kotoba when ready."
  (oracle-str-const 'selector-join-sep mirror-selector-join-sep))

(defn- mirror-node-port [fleet node]
  (or (:port node) (:fleet/port fleet) default-control-port))

(defn- mirror-health-url [port]
  (str health-url-prefix port health-url-path))

(defn node-port
  "Resolve a node's control HTTP port, defaulting to the fleet port, then 8077.
   Kotoba `resolve-port` when oracle ready (Product Value ABI optional ports)."
  [fleet node]
  (try-oracle
   #(oracle/i64->host
     (o 'resolve-port
        [(oracle/option-i64 (:port node))
         (oracle/option-i64 (:fleet/port fleet))]))
   #(mirror-node-port fleet node)))

(defn node-health-url
  "Node-local health URL for the control HTTP port.
   Kotoba `health-url` (cljs may still soft-fall if KIR string-from-i64 fails)."
  [fleet node]
  (let [port (node-port fleet node)]
    (try-oracle
     #(o 'health-url [(oracle/as-i64 port)])
     #(mirror-health-url port))))

(defn select
  "Resolve a node selector string to node maps.

   nil or \"all\" selects every node; otherwise accepts a comma-separated list of
   node names. Unknown names are ignored, matching the original CLI behaviour.
   Kotoba selector helpers when oracle ready."
  [fleet sel]
  (let [nodes (:nodes fleet)]
    (try-oracle
     (fn []
       (if (oracle/bool->host (o 'selector-is-all? [(str (or sel ""))]))
         nodes
         (filter (fn [node]
                   (oracle/bool->host
                    (o 'selector-wants-name?
                       [(str sel) (str (:name node))])))
                 nodes)))
     (fn []
       (if (or (nil? sel) (= sel selector-all))
         nodes
         (let [want (set (str/split sel #","))]
           (filter (fn [node] (want (:name node))) nodes)))))))

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
                         :online? (try-oracle
                                   #(not (oracle/bool->host
                                          (o 'line-has-offline? [(str line)])))
                                   #(not (str/includes? line offline-token)))}])))

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
