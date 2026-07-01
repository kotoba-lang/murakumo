;; murakumo.cloud.plan — portable murakumo.cloud overlay planning.
;;
;; This is the pure control-plane model for replacing Tailscale/WireGuard with a
;; Murakumo-native overlay. The live drivers still need host networking, relay, and
;; packet plumbing; this namespace owns deterministic cloud records and routing
;; choices so the CLI can plan/publish them without an external VPN control plane.

(ns murakumo.cloud.plan
  (:require [clojure.string :as str]
            [murakumo.config :as config]
            [murakumo.fleet.inventory :as inv]
            [murakumo.identity :as identity]
            [murakumo.provision.plan :as provision]))

(def default-cloud-path config/default-cloud-path)

(def default-driver "murakumo-overlay")

(def default-cloud
  {:cloud/name "murakumo.cloud"
   :cloud/domain "murakumo.cloud"
   :cloud/graph "murakumo-cloud"
   :overlay/version 1
   :overlay/address-family :identity
   :overlay/direct [:quic :webrtc :webtransport]
   :overlay/relay [:murakumo-relay]
   :overlay/auth-key-env "MURAKUMO_OVERLAY_AUTH_KEY"
   :overlay/auth-key-source :operator-seed
   :relays []
   :policy {:default :deny :allow []}})

(defn merge-defaults [cloud]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b)) (merge a b) b))
              default-cloud
              cloud))

(defn overlay-id
  "Stable CID for an overlay namespace."
  [cloud]
  (identity/graph-cid (or (:overlay/id cloud) (:cloud/name cloud) "murakumo.cloud")))

(defn node-id
  "Stable node CID inside an overlay."
  [cloud node]
  (identity/graph-cid (str (overlay-id cloud) ":" (:name node))))

(defn node-region [node]
  (or (get-in node [:labels :zone])
      (get-in node [:labels :region])
      (:region node)
      "global"))

(defn relay-score [node relay]
  (if (= (node-region node) (:region relay)) 0 1))

(defn choose-relay
  "Choose a deterministic relay for node fallback."
  [cloud node]
  (first (sort-by (juxt #(relay-score node %) :name) (:relays cloud))))

(defn node-record
  "Cloud control-plane record for one fleet node."
  [cloud fleet node]
  (let [relay (choose-relay cloud node)]
    {:$type "cloud.murakumo.node"
     :overlay (overlay-id cloud)
     :node (node-id cloud node)
     :name (:name node)
     :fleet (:fleet/name fleet)
     :region (node-region node)
     :roles (vec (:roles node))
     :labels (or (:labels node) {})
     :direct (vec (:overlay/direct cloud))
     :relay (:name relay)
     :relay_url (:url relay)
     :capabilities [:ssh :http :gossip :deploy :reconcile]}))

(defn direct-endpoint
  "Transport endpoint candidate for one node.
   These are identity-overlay dial hints, not subnet routes."
  [cloud fleet node transport]
  (let [host (or (:host node) (:name node))
        p2p-port (provision/node-p2p-port fleet node)
        http-port (inv/node-port fleet node)]
    (case transport
      :quic {:transport :quic
             :endpoint (format "quic://%s:%d" host p2p-port)}
      :webrtc {:transport :webrtc
               :endpoint (format "webrtc://%s:%d" host (+ 100 p2p-port))}
      :webtransport {:transport :webtransport
                     :endpoint (format "https://%s:%d/.well-known/murakumo/webtransport" host http-port)}
      {:transport transport
       :endpoint (format "%s://%s" (name transport) host)})))

(defn relay-endpoint [relay node-id]
  (when relay
    {:relay (:name relay)
     :transport (first (:transports relay))
     :endpoint (str (:url relay) "/" node-id)}))

(defn route-record
  "Identity-overlay route hints for one node: direct candidates plus relay fallback."
  [cloud fleet node]
  (let [relay (choose-relay cloud node)
        node-id (node-id cloud node)]
    {:$type "cloud.murakumo.route"
     :overlay (overlay-id cloud)
     :node node-id
     :name (:name node)
     :direct (mapv #(direct-endpoint cloud fleet node %) (:overlay/direct cloud))
     :relay (relay-endpoint relay node-id)}))

(defn relay-record [cloud relay]
  {:$type "cloud.murakumo.relay"
   :overlay (overlay-id cloud)
   :name (:name relay)
   :region (:region relay)
   :url (:url relay)
   :transports (vec (:transports relay))})

(defn policy-record [cloud]
  {:$type "cloud.murakumo.policy"
   :overlay (overlay-id cloud)
   :default (get-in cloud [:policy :default] :deny)
   :allow (vec (get-in cloud [:policy :allow] []))})

(defn cloud-plan
  "Build all murakumo.cloud records from fleet/cloud declarations."
  [fleet cloud]
  (let [cloud (merge-defaults cloud)]
    {:cloud (:cloud/name cloud)
     :domain (:cloud/domain cloud)
     :graph (:cloud/graph cloud)
     :overlay (overlay-id cloud)
     :address_family (:overlay/address-family cloud)
     :auth-key (:overlay/auth-key cloud)
     :auth-key-env (:overlay/auth-key-env cloud)
     :auth-key-source (:overlay/auth-key-source cloud)
     :relays (mapv #(relay-record cloud %) (:relays cloud))
     :nodes (mapv #(node-record cloud fleet %) (:nodes fleet))
     :routes (mapv #(route-record cloud fleet %) (:nodes fleet))
     :policy (policy-record cloud)}))

(defn plan-records [plan]
  (vec (concat (:relays plan) (:nodes plan) (:routes plan) [(:policy plan)])))

(defn route-for [plan node-name]
  (first (filter #(= node-name (:name %)) (:routes plan))))

(defn relay-for [plan relay-name]
  (first (filter #(= relay-name (:name %)) (:relays plan))))

(defn- policy-value-matches? [rule-value requested-value]
  (or (= rule-value requested-value)
      (= rule-value :*)
      (= rule-value :any)))

(defn policy-allows?
  "True when a default-deny cloud policy grants from/to/capability."
  [policy from to capability]
  (boolean
   (some (fn [rule]
           (and (policy-value-matches? (:from rule) from)
                (policy-value-matches? (:to rule) to)
                (some #(policy-value-matches? % capability)
                      (:capabilities rule))))
         (:allow policy))))

(defn dial-request
  "Normalise dial options into policy dimensions."
  [opts]
  {:from (or (:from opts) :operator)
   :to (or (:to opts) :fleet)
   :capability (or (:capability opts) :ssh)})

(defn dial-plan
  "Policy-aware identity dial plan for one target node."
  [plan node-name opts]
  (let [{:keys [from to capability] :as request} (dial-request opts)
        route (route-for plan node-name)
        allowed? (policy-allows? (:policy plan) from to capability)]
    {:request request
     :route route
     :allowed? (boolean (and route allowed?))
     :reason (cond
               (nil? route) :unknown-node
               allowed? :allowed
               :else :policy-denied)}))

(defn preferred-direct
  "Choose the first direct endpoint in the route as the initial dial path."
  [route]
  (first (:direct route)))

(defn driver-argv
  "Canonical argv for the native murakumo overlay driver."
  [driver plan dial-plan]
  (let [{:keys [request route]} dial-plan
        direct (preferred-direct route)
        relay (:relay route)]
    (cond-> [driver "dial"
             "--overlay" (:overlay plan)
             "--node" (:node route)
             "--name" (:name route)
             "--from" (name (:from request))
             "--to" (name (:to request))
             "--capability" (name (:capability request))]
      direct (into ["--direct" (:endpoint direct)
                    "--transport" (name (:transport direct))])
      relay (into ["--relay" (:endpoint relay)
                   "--relay-transport" (name (:transport relay))])
      (:auth-key plan) (into ["--auth-key" (:auth-key plan)]))))

(defn relay-driver-argv
  "Canonical argv for starting a native murakumo relay process."
  [driver plan relay]
  (cond-> [driver "relay"
           "--overlay" (:overlay plan)
           "--name" (:name relay)
           "--region" (:region relay)
           "--url" (:url relay)
           "--transports" (str/join "," (map name (:transports relay)))]
    (:auth-key plan) (into ["--auth-key" (:auth-key plan)])))

(defn connect-plan
  "Policy-aware executable overlay connection plan."
  [plan node-name opts]
  (let [dial (dial-plan plan node-name opts)
        driver (or (:driver opts) default-driver)]
    (assoc dial
           :driver driver
           :argv (when (:allowed? dial)
                   (driver-argv driver plan dial)))))

(defn relay-plan
  "Executable overlay relay process plan."
  [plan relay-name opts]
  (let [relay (relay-for plan relay-name)
        driver (or (:driver opts) default-driver)]
    {:relay relay
     :driver driver
     :ok? (boolean relay)
     :reason (if relay :ready :unknown-relay)
     :argv (when relay
             (relay-driver-argv driver plan relay))}))

(defn bootstrap-plan
  "Executable overlay bootstrap plan: relays first, then node dials."
  [plan opts]
  {:relays (mapv #(relay-plan plan (:name %) opts) (:relays plan))
   :connects (mapv #(connect-plan plan (:name %) opts) (:nodes plan))})

(defn bootstrap-step
  "Normalise a relay/connect plan into a machine-readable bootstrap step."
  [phase item]
  (let [target (or (get-in item [:relay :name])
                   (get-in item [:route :name])
                   "-")]
    {:phase phase
     :target target
     :ok? (boolean (:argv item))
     :reason (:reason item)
     :argv (:argv item)}))

(defn bootstrap-manifest
  "Machine-readable bootstrap manifest for native overlay execution."
  [plan opts]
  (let [{:keys [relays connects]} (bootstrap-plan plan opts)]
    {:$type "cloud.murakumo.bootstrap"
     :overlay (:overlay plan)
     :driver (or (:driver opts) default-driver)
     :phases [{:name :relays
               :steps (mapv #(bootstrap-step :relay %) relays)}
              {:name :connects
               :steps (mapv #(bootstrap-step :connect %) connects)}]}))

(defn summary-lines [plan]
  (let [relay-count (count (:relays plan))
        node-count (count (:nodes plan))]
    (vec
     (concat
      [(format "murakumo.cloud %s  overlay %s" (:domain plan) (:overlay plan))
       (format "  address-family %s ; nodes %d ; relays %d"
               (name (:address_family plan)) node-count relay-count)
       "  NODE           REGION     RELAY          DIRECT"]
      (for [node (:nodes plan)]
        (format "  %-14s %-10s %-14s %s"
                (:name node)
                (:region node)
                (or (:relay node) "-")
                (str/join "," (map name (:direct node)))))
      [(format "  policy default=%s allow=%d"
               (name (get-in plan [:policy :default]))
               (count (get-in plan [:policy :allow])))]))))

(defn route-lines [plan]
  (vec
   (concat
    [(format "murakumo.cloud routes overlay %s" (:overlay plan))
     "  NODE           DIRECT                                      RELAY"]
    (for [route (:routes plan)]
      (format "  %-14s %-43s %s"
              (:name route)
              (str/join "," (map (comp name :transport) (:direct route)))
              (or (get-in route [:relay :relay]) "-"))))))

(defn dial-lines [plan node-name opts]
  (let [{:keys [request route allowed? reason]} (dial-plan plan node-name opts)]
    (cond
      (nil? route)
      [(format "unknown murakumo.cloud node: %s" node-name)]

      (not allowed?)
      [(format "murakumo.cloud dial %s denied by policy" node-name)
       (format "  from=%s to=%s capability=%s reason=%s"
               (name (:from request))
               (name (:to request))
               (name (:capability request))
               (name reason))]

      :else
      (vec
       (concat
        [(format "murakumo.cloud dial %s  node %s" (:name route) (:node route))
         (format "  authorized: from=%s to=%s capability=%s"
                 (name (:from request))
                 (name (:to request))
                 (name (:capability request)))
         "  direct candidates:"]
        (map (fn [{:keys [transport endpoint]}]
               (format "    %-12s %s" (name transport) endpoint))
             (:direct route))
        [(format "  relay fallback: %s"
                 (or (some-> route :relay :endpoint) "-"))])))))

(defn connect-lines [plan node-name opts]
  (let [{:keys [request argv allowed? reason route]} (connect-plan plan node-name opts)]
    (cond
      (nil? route)
      [(format "unknown murakumo.cloud node: %s" node-name)]

      (not allowed?)
      [(format "murakumo.cloud connect %s denied by policy" node-name)
       (format "  from=%s to=%s capability=%s reason=%s"
               (name (:from request))
               (name (:to request))
               (name (:capability request))
               (name reason))]

      :else
      [(format "murakumo.cloud connect %s" node-name)
       (str "  " (str/join " " argv))])))

(defn relay-lines [plan relay-name opts]
  (let [{:keys [argv ok? reason]} (relay-plan plan relay-name opts)]
    (if ok?
      [(format "murakumo.cloud relay %s" relay-name)
       (str "  " (str/join " " argv))]
      [(format "unknown murakumo.cloud relay: %s" relay-name)
       (format "  reason=%s" (name reason))])))

(defn bootstrap-text-lines [plan opts]
  (let [{:keys [relays connects]} (bootstrap-plan plan opts)]
    (vec
     (concat
      [(format "murakumo.cloud bootstrap overlay %s" (:overlay plan))
       "  relays:"]
      (map (fn [{:keys [relay argv reason]}]
             (if argv
               (format "    %-14s %s" (:name relay) (str/join " " argv))
               (format "    %-14s skipped reason=%s" "-" (name reason))))
           relays)
      ["  connects:"]
      (map (fn [{:keys [route argv reason]}]
             (if argv
               (format "    %-14s %s" (:name route) (str/join " " argv))
               (format "    %-14s skipped reason=%s" (or (:name route) "-") (name reason))))
           connects)))))

(defn bootstrap-lines [plan opts]
  (if (= :edn (:format opts))
    [(pr-str (bootstrap-manifest plan opts))]
    (bootstrap-text-lines plan opts)))

(defn command-lines
  "Render a cloud CLI command result as printable lines."
  ([command plan] (command-lines command plan nil))
  ([command plan target] (command-lines command plan target {}))
  ([command plan target opts]
   (case command
     :records (mapv pr-str (plan-records plan))
     :routes (route-lines plan)
     :dial (dial-lines plan target opts)
     :connect (connect-lines plan target opts)
     :relay (relay-lines plan target opts)
     :bootstrap (bootstrap-lines plan opts)
     :plan (summary-lines plan))))

(defn parse-flags [args]
  (reduce (fn [m arg]
            (cond
              (= arg "plan") (assoc m :command :plan)
              (= arg "records") (assoc m :command :records)
              (= arg "routes") (assoc m :command :routes)
              (= arg "dial") (assoc m :command :dial)
              (= arg "connect") (assoc m :command :connect)
              (= arg "relay") (assoc m :command :relay)
              (= arg "bootstrap") (assoc m :command :bootstrap)
              (str/starts-with? arg "--cloud=") (assoc m :cloud-path (subs arg 8))
              (str/starts-with? arg "--fleet=") (assoc m :fleet-path (subs arg 8))
              (str/starts-with? arg "--target=") (assoc m :target (subs arg 9))
              (str/starts-with? arg "--from=") (assoc m :from (keyword (subs arg 7)))
              (str/starts-with? arg "--to=") (assoc m :to (keyword (subs arg 5)))
              (str/starts-with? arg "--capability=") (assoc m :capability (keyword (subs arg 13)))
              (str/starts-with? arg "--driver=") (assoc m :driver (subs arg 9))
              (str/starts-with? arg "--format=") (assoc m :format (keyword (subs arg 9)))
              (str/starts-with? arg "--auth-key=") (assoc m :auth-key (subs arg 11))
              (not (str/starts-with? arg "--")) (assoc m :target arg)
              :else m))
          {:command :plan
           :cloud-path default-cloud-path
           :fleet-path config/default-fleet-path}
          args))
