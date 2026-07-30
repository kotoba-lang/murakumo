;; murakumo.cloud.plan — portable murakumo.cloud overlay planning.
;;
;; This is the pure control-plane model for replacing Tailscale/WireGuard with a
;; Murakumo-native overlay. The live drivers still need host networking, relay, and
;; packet plumbing; this namespace owns deterministic cloud records and routing
;; choices so the CLI can plan/publish them without an external VPN control plane.
;;
;; W6 product-shell + T6.4: defaults + endpoints + CLI presentation + summary
;; lines + parse-flags classifiers + command/flag tokens + record $type +
;; capability name tokens require the shipped `:cloud-plan` KIR on **every**
;; platform. Host pure mirrors are gone — cljs/nbb must preload shipped KIR
;; (resources/ via nbb cwd, register-kir!, or set-resource-loader!) before
;; requiring this ns (ADR-260731-w6-t64-cloud-mirror-delete).
;; Host remains: record assembly, choose-relay sort, width fmt, policy walk,
;; argv/map assembly, reduce fold for parse-flags.

(ns murakumo.cloud.plan
  "Portable murakumo.cloud overlay planning.
   W6 product-shell: defaults + endpoints + CLI lines + flag classifiers
   + record types via cloud_plan_core (required)."
  (:require [clojure.string :as str]
            [murakumo.config :as config]
            [murakumo.fleet.inventory :as inv]
            [murakumo.identity :as identity]
            [murakumo.provision.plan :as provision]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :cloud-plan)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

;; ── constants (oracle SSoT) ────────────────────────────────────────────

(def default-cloud-path config/default-cloud-path)

(def default-driver
  (o 'default-driver []))

(def node-record-type
  "Record $type for node control-plane entries. Kotoba SSoT (required)."
  (o 'node-record-type []))

(def route-record-type
  "Record $type for route entries. Kotoba SSoT (required)."
  (o 'route-record-type []))

(def relay-record-type
  "Record $type for relay entries. Kotoba SSoT (required)."
  (o 'relay-record-type []))

(def policy-record-type
  "Record $type for policy entries. Kotoba SSoT (required)."
  (o 'policy-record-type []))

(def bootstrap-record-type
  "Record $type for bootstrap manifest. Kotoba SSoT (required)."
  (o 'bootstrap-record-type []))

(def cap-ssh
  "Default node capability name: ssh. Kotoba SSoT (required)."
  (o 'cap-ssh []))

(def cap-http
  "Default node capability name: http. Kotoba SSoT (required)."
  (o 'cap-http []))

(def cap-gossip
  "Default node capability name: gossip. Kotoba SSoT (required)."
  (o 'cap-gossip []))

(def cap-deploy
  "Default node capability name: deploy. Kotoba SSoT (required)."
  (o 'cap-deploy []))

(def cap-reconcile
  "Default node capability name: reconcile. Kotoba SSoT (required)."
  (o 'cap-reconcile []))

(def default-node-capabilities
  "Default node capability keywords (oracle SSoT names)."
  [(keyword cap-ssh) (keyword cap-http) (keyword cap-gossip)
   (keyword cap-deploy) (keyword cap-reconcile)])

(def default-cloud
  {:cloud/name (o 'default-cloud-name [])
   :cloud/domain (o 'default-cloud-domain [])
   :cloud/graph (o 'default-cloud-graph [])
   :overlay/version (oracle/i64->host (o 'overlay-version []))
   :overlay/address-family :identity
   :overlay/direct [:quic :webrtc :webtransport]
   :overlay/relay [:murakumo-relay]
   :overlay/auth-key-env (o 'default-auth-key-env [])
   :overlay/auth-key-source :operator-seed
   :relays []
   :policy {:default :deny :allow []}})

;; ── oracle-required CLI presentation labels ──────────────────────────────

(def dash-placeholder
  (o 'dash-placeholder []))

(def summary-nodes-header
  (o 'summary-nodes-header []))

(def routes-header
  (o 'routes-header []))

(def direct-candidates-label
  (o 'direct-candidates-label []))

(def relays-section-label
  (o 'relays-section-label []))

(def connects-section-label
  (o 'connects-section-label []))

(defn summary-title
  "CLI title for plan summary. Kotoba `summary-title` (required)."
  [domain overlay]
  (o 'summary-title [(str domain) (str overlay)]))

(defn routes-title
  "CLI title for routes listing. Kotoba `routes-title` (required)."
  [overlay]
  (o 'routes-title [(str overlay)]))

(defn bootstrap-title
  "CLI title for bootstrap listing. Kotoba `bootstrap-title` (required)."
  [overlay]
  (o 'bootstrap-title [(str overlay)]))

(defn unknown-node-line
  "Unknown node error line. Kotoba `unknown-node-line` (required)."
  [node-name]
  (o 'unknown-node-line [(str node-name)]))

(defn unknown-relay-line
  "Unknown relay error line. Kotoba `unknown-relay-line` (required)."
  [relay-name]
  (o 'unknown-relay-line [(str relay-name)]))

(defn dial-denied-line
  "Dial policy-denied title. Kotoba `dial-denied-line` (required)."
  [node-name]
  (o 'dial-denied-line [(str node-name)]))

(defn connect-denied-line
  "Connect policy-denied title. Kotoba `connect-denied-line` (required)."
  [node-name]
  (o 'connect-denied-line [(str node-name)]))

(defn dial-ok-title
  "Dial authorized title. Kotoba `dial-ok-title` (required)."
  [route-name node]
  (o 'dial-ok-title [(str route-name) (str node)]))

(defn connect-ok-title
  "Connect authorized title. Kotoba `connect-ok-title` (required)."
  [node-name]
  (o 'connect-ok-title [(str node-name)]))

(defn relay-ok-title
  "Relay ok title. Kotoba `relay-ok-title` (required)."
  [relay-name]
  (o 'relay-ok-title [(str relay-name)]))

(defn from-to-cap-reason
  "from/to/capability/reason detail line. Kotoba SSoT (required)."
  [from to capability reason]
  (o 'from-to-cap-reason [(str from) (str to) (str capability) (str reason)]))

(defn authorized-line
  "authorized from/to/capability line. Kotoba SSoT (required)."
  [from to capability]
  (o 'authorized-line [(str from) (str to) (str capability)]))

(defn relay-fallback-line
  "relay fallback detail line. Kotoba SSoT (required)."
  [endpoint]
  (o 'relay-fallback-line [(str endpoint)]))

(defn reason-line
  "reason= detail line. Kotoba SSoT (required)."
  [reason]
  (o 'reason-line [(str reason)]))

(defn indent-argv-line
  "Two-space indented argv join line. Kotoba SSoT (required)."
  [argv-joined]
  (o 'indent-argv-line [(str argv-joined)]))

(defn address-family-line
  "Summary address-family + node/relay counts. Kotoba SSoT (required)."
  [af nodes relays]
  (o 'address-family-line [(str af)
                             (oracle/as-i64 nodes)
                             (oracle/as-i64 relays)]))

(defn policy-line
  "Summary policy default + allow count. Kotoba SSoT (required)."
  [default allow-n]
  (o 'policy-line [(str default) (oracle/as-i64 allow-n)]))

(defn skipped-reason-suffix
  "Trailing ' skipped reason=…' fragment (name column padding stays host)."
  [reason]
  (o 'skipped-reason-suffix [(str reason)]))

;; ── oracle-required parse-flags tokens + classifiers ─────────────────────

(def cmd-plan
  "CLI command token `plan`. Kotoba SSoT (required)."
  (o 'cmd-plan []))

(def cmd-records
  (o 'cmd-records []))

(def cmd-routes
  (o 'cmd-routes []))

(def cmd-dial
  (o 'cmd-dial []))

(def cmd-connect
  (o 'cmd-connect []))

(def cmd-relay
  (o 'cmd-relay []))

(def cmd-bootstrap
  (o 'cmd-bootstrap []))

(def default-command-token
  "Default parse-flags command token. Kotoba SSoT (required)."
  (o 'default-command-token []))

(def flag-dash-prefix
  (o 'flag-dash-prefix []))

(def flag-cloud-prefix
  (o 'flag-cloud-prefix []))

(def flag-fleet-prefix
  (o 'flag-fleet-prefix []))

(def flag-target-prefix
  (o 'flag-target-prefix []))

(def flag-from-prefix
  (o 'flag-from-prefix []))

(def flag-to-prefix
  (o 'flag-to-prefix []))

(def flag-capability-prefix
  (o 'flag-capability-prefix []))

(def flag-driver-prefix
  (o 'flag-driver-prefix []))

(def flag-format-prefix
  (o 'flag-format-prefix []))

(def flag-auth-key-prefix
  (o 'flag-auth-key-prefix []))

(defn command-token
  "Known CLI command name for argv token, or \"\". Kotoba SSoT (required)."
  [a]
  (o 'command-token [(str a)]))

(defn- is-cmd-plan? [a]
  (oracle/bool->host (o 'is-cmd-plan? [(str a)])))

(defn- is-cmd-records? [a]
  (oracle/bool->host (o 'is-cmd-records? [(str a)])))

(defn- is-cmd-routes? [a]
  (oracle/bool->host (o 'is-cmd-routes? [(str a)])))

(defn- is-cmd-dial? [a]
  (oracle/bool->host (o 'is-cmd-dial? [(str a)])))

(defn- is-cmd-connect? [a]
  (oracle/bool->host (o 'is-cmd-connect? [(str a)])))

(defn- is-cmd-relay? [a]
  (oracle/bool->host (o 'is-cmd-relay? [(str a)])))

(defn- is-cmd-bootstrap? [a]
  (oracle/bool->host (o 'is-cmd-bootstrap? [(str a)])))

(defn- is-flag-cloud? [a]
  (oracle/bool->host (o 'is-flag-cloud? [(str a)])))

(defn- is-flag-fleet? [a]
  (oracle/bool->host (o 'is-flag-fleet? [(str a)])))

(defn- is-flag-target? [a]
  (oracle/bool->host (o 'is-flag-target? [(str a)])))

(defn- is-flag-from? [a]
  (oracle/bool->host (o 'is-flag-from? [(str a)])))

(defn- is-flag-to? [a]
  (oracle/bool->host (o 'is-flag-to? [(str a)])))

(defn- is-flag-capability? [a]
  (oracle/bool->host (o 'is-flag-capability? [(str a)])))

(defn- is-flag-driver? [a]
  (oracle/bool->host (o 'is-flag-driver? [(str a)])))

(defn- is-flag-format? [a]
  (oracle/bool->host (o 'is-flag-format? [(str a)])))

(defn- is-flag-auth-key? [a]
  (oracle/bool->host (o 'is-flag-auth-key? [(str a)])))

(defn- is-flag-dash? [a]
  (oracle/bool->host (o 'is-flag-dash? [(str a)])))

(defn- is-positional-target? [a]
  (oracle/bool->host (o 'is-positional-target? [(str a)])))

(defn- flag-cloud-value [a]
  (o 'flag-cloud-value [(str a)]))

(defn- flag-fleet-value [a]
  (o 'flag-fleet-value [(str a)]))

(defn- flag-target-value [a]
  (o 'flag-target-value [(str a)]))

(defn- flag-from-value [a]
  (o 'flag-from-value [(str a)]))

(defn- flag-to-value [a]
  (o 'flag-to-value [(str a)]))

(defn- flag-capability-value [a]
  (o 'flag-capability-value [(str a)]))

(defn- flag-driver-value [a]
  (o 'flag-driver-value [(str a)]))

(defn- flag-format-value [a]
  (o 'flag-format-value [(str a)]))

(defn- flag-auth-key-value [a]
  (o 'flag-auth-key-value [(str a)]))

(defn merge-defaults [cloud]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b)) (merge a b) b))
              default-cloud
              cloud))

(defn overlay-id
  "Stable CID for an overlay namespace.
   Preimage via kotoba `overlay-id-input` (required)."
  [cloud]
  (identity/graph-cid
   (o 'overlay-id-input
        [(str (or (:overlay/id cloud) ""))
         (str (or (:cloud/name cloud) ""))])))

(defn node-id
  "Stable node CID inside an overlay.
   Preimage via kotoba `node-id-input` (required)."
  [cloud node]
  (identity/graph-cid
   (o 'node-id-input
        [(str (overlay-id cloud)) (str (:name node))])))

(defn node-region
  "Kotoba `node-region` (zone / region-label / region / global) (required)."
  [node]
  (o 'node-region
       [(str (or (get-in node [:labels :zone]) ""))
        (str (or (get-in node [:labels :region]) ""))
        (str (or (:region node) ""))]))

(defn relay-score
  "Kotoba `relay-score` (required)."
  [node relay]
  (oracle/i64->host
     (o 'relay-score
        [(str (node-region node))
         (str (or (:region relay) ""))])))

(defn choose-relay
  "Choose a deterministic relay for node fallback."
  [cloud node]
  (first (sort-by (juxt #(relay-score node %) :name) (:relays cloud))))

(defn node-record
  "Cloud control-plane record for one fleet node.
   $type + default capabilities oracle SSoT; map assembly stays host."
  [cloud fleet node]
  (let [relay (choose-relay cloud node)]
    {:$type node-record-type
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
     :capabilities default-node-capabilities}))

(defn direct-endpoint
  "Transport endpoint candidate for one node.
   These are identity-overlay dial hints, not subnet routes."
  [cloud fleet node transport]
  (let [host (or (:host node) (:name node))
        p2p-port (provision/node-p2p-port fleet node)
        http-port (inv/node-port fleet node)]
    (case transport
      :quic {:transport :quic
             :endpoint (o 'quic-endpoint [(str host) (oracle/as-i64 p2p-port)])}
      :webrtc {:transport :webrtc
               :endpoint (o 'webrtc-endpoint [(str host) (oracle/as-i64 p2p-port)])}
      :webtransport {:transport :webtransport
                     :endpoint (o 'webtransport-endpoint
                                    [(str host) (oracle/as-i64 http-port)])}
      {:transport transport
       :endpoint (o 'transport-endpoint
                      [(name transport) (str host)])})))

(defn relay-endpoint
  "Endpoint URL via kotoba `relay-endpoint-url` (required)."
  [relay node-id]
  (when relay
    {:relay (:name relay)
     :transport (first (:transports relay))
     :endpoint (o 'relay-endpoint-url
                    [(str (:url relay)) (str node-id)])}))

(defn- fmt
  "CLI line formatter. On JVM uses clojure.core/format; on cljs interpolates %s/%d left-to-right."
  [template & args]
  #?(:clj (apply format template args)
     :cljs
     (loop [s template args (seq args)]
       (if-not args
         s
         (let [a (first args)
               i-s (str/index-of s "%s")
               i-d (str/index-of s "%d")
               i-p (str/index-of s "%-")
               candidates (remove nil? [i-s i-d i-p])
               i (when (seq candidates) (apply min candidates))]
           (if (nil? i)
             (throw (js/Error. (str "fmt: leftover args for " s)))
             (cond
               (= i i-s)
               (recur (str (subs s 0 i) a (subs s (+ i 2))) (next args))
               (= i i-d)
               (recur (str (subs s 0 i) a (subs s (+ i 2))) (next args))
               :else
               ;; %-Ns padded field
               (let [m (re-find #"%-[0-9]+s" (subs s i))
                     _ (when-not m (throw (js/Error. (str "fmt: bad pad at " (subs s i)))))
                     width (js/parseInt (re-find #"[0-9]+" m) 10)
                     pad (str a (apply str (repeat (max 0 (- width (count (str a)))) " ")))]
                 (recur (str (subs s 0 i) pad (subs s (+ i (count m)))) (next args))))))))))

(defn route-record
  "Identity-overlay route hints for one node: direct candidates plus relay fallback.
   $type oracle SSoT; map assembly stays host."
  [cloud fleet node]
  (let [relay (choose-relay cloud node)
        node-id (node-id cloud node)]
    {:$type route-record-type
     :overlay (overlay-id cloud)
     :node node-id
     :name (:name node)
     :direct (mapv #(direct-endpoint cloud fleet node %) (:overlay/direct cloud))
     :relay (relay-endpoint relay node-id)}))

(defn relay-record [cloud relay]
  {:$type relay-record-type
   :overlay (overlay-id cloud)
   :name (:name relay)
   :region (:region relay)
   :url (:url relay)
   :transports (vec (:transports relay))})

(defn policy-record [cloud]
  {:$type policy-record-type
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
  "Machine-readable bootstrap manifest for native overlay execution.
   $type oracle SSoT; phase assembly stays host."
  [plan opts]
  (let [{:keys [relays connects]} (bootstrap-plan plan opts)]
    {:$type bootstrap-record-type
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
      [(summary-title (:domain plan) (:overlay plan))
       (address-family-line (name (:address_family plan)) node-count relay-count)
       summary-nodes-header]
      (for [node (:nodes plan)]
        (fmt "  %-14s %-10s %-14s %s"
                (:name node)
                (:region node)
                (or (:relay node) dash-placeholder)
                (str/join "," (map name (:direct node)))))
      [(policy-line (name (get-in plan [:policy :default]))
                    (count (get-in plan [:policy :allow])))]))))

(defn route-lines [plan]
  (vec
   (concat
    [(routes-title (:overlay plan))
     routes-header]
    (for [route (:routes plan)]
      (fmt "  %-14s %-43s %s"
              (:name route)
              (str/join "," (map (comp name :transport) (:direct route)))
              (or (get-in route [:relay :relay]) dash-placeholder))))))

(defn dial-lines [plan node-name opts]
  (let [{:keys [request route allowed? reason]} (dial-plan plan node-name opts)]
    (cond
      (nil? route)
      [(unknown-node-line node-name)]

      (not allowed?)
      [(dial-denied-line node-name)
       (from-to-cap-reason (name (:from request))
                           (name (:to request))
                           (name (:capability request))
                           (name reason))]

      :else
      (vec
       (concat
        [(dial-ok-title (:name route) (:node route))
         (authorized-line (name (:from request))
                          (name (:to request))
                          (name (:capability request)))
         direct-candidates-label]
        (map (fn [{:keys [transport endpoint]}]
               (fmt "    %-12s %s" (name transport) endpoint))
             (:direct route))
        [(relay-fallback-line
          (or (some-> route :relay :endpoint) dash-placeholder))])))))

(defn connect-lines [plan node-name opts]
  (let [{:keys [request argv allowed? reason route]} (connect-plan plan node-name opts)]
    (cond
      (nil? route)
      [(unknown-node-line node-name)]

      (not allowed?)
      [(connect-denied-line node-name)
       (from-to-cap-reason (name (:from request))
                           (name (:to request))
                           (name (:capability request))
                           (name reason))]

      :else
      [(connect-ok-title node-name)
       (indent-argv-line (str/join " " argv))])))

(defn relay-lines [plan relay-name opts]
  (let [{:keys [argv ok? reason]} (relay-plan plan relay-name opts)]
    (if ok?
      [(relay-ok-title relay-name)
       (indent-argv-line (str/join " " argv))]
      [(unknown-relay-line relay-name)
       (reason-line (name reason))])))

(defn bootstrap-text-lines [plan opts]
  (let [{:keys [relays connects]} (bootstrap-plan plan opts)]
    (vec
     (concat
      [(bootstrap-title (:overlay plan))
       relays-section-label]
      (map (fn [{:keys [relay argv reason]}]
             (if argv
               (fmt "    %-14s %s" (:name relay) (str/join " " argv))
               (str (fmt "    %-14s" dash-placeholder)
                    (skipped-reason-suffix (name reason)))))
           relays)
      [connects-section-label]
      (map (fn [{:keys [route argv reason]}]
             (if argv
               (fmt "    %-14s %s" (:name route) (str/join " " argv))
               (str (fmt "    %-14s" (or (:name route) dash-placeholder))
                    (skipped-reason-suffix (name reason)))))
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

(defn parse-flags
  "Parse cloud CLI argv. Command/flag tokens + classifiers oracle SSoT via
   cloud_plan_core (required); reduce fold + keyword mapping stay host."
  [args]
  (reduce (fn [m arg]
            (let [cmd (command-token arg)]
              (if-not (str/blank? cmd)
                (assoc m :command (keyword cmd))
                (cond
                  (is-flag-cloud? arg) (assoc m :cloud-path (flag-cloud-value arg))
                  (is-flag-fleet? arg) (assoc m :fleet-path (flag-fleet-value arg))
                  (is-flag-target? arg) (assoc m :target (flag-target-value arg))
                  (is-flag-from? arg) (assoc m :from (keyword (flag-from-value arg)))
                  (is-flag-to? arg) (assoc m :to (keyword (flag-to-value arg)))
                  (is-flag-capability? arg) (assoc m :capability (keyword (flag-capability-value arg)))
                  (is-flag-driver? arg) (assoc m :driver (flag-driver-value arg))
                  (is-flag-format? arg) (assoc m :format (keyword (flag-format-value arg)))
                  (is-flag-auth-key? arg) (assoc m :auth-key (flag-auth-key-value arg))
                  (is-positional-target? arg) (assoc m :target arg)
                  (is-flag-dash? arg) m
                  :else m))))
          {:command (keyword default-command-token)
           :cloud-path default-cloud-path
           :fleet-path config/default-fleet-path}
          args))
