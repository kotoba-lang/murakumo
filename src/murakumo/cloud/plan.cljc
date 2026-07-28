;; murakumo.cloud.plan — portable murakumo.cloud overlay planning.
;;
;; This is the pure control-plane model for replacing Tailscale/WireGuard with a
;; Murakumo-native overlay. The live drivers still need host networking, relay, and
;; packet plumbing; this namespace owns deterministic cloud records and routing
;; choices so the CLI can plan/publish them without an external VPN control plane.
;;
;; W6 product-shell (ADR-260728-w6-cloud-summary-pure-oracle):
;; defaults + region/score/endpoints + CLI presentation + summary
;; address-family/policy lines DELEGATE to kotoba cloud_plan_core when oracle
;; is loadable (JVM classpath or cljs/nbb). Record assembly, choose-relay sort,
;; width fmt / collection folds stay host. cljs mirrors remain fallback.

(ns murakumo.cloud.plan
  "Portable murakumo.cloud overlay planning.
   W6 product-shell: defaults + endpoints + CLI/summary lines via cloud_plan_core
   when oracle ready."
  (:require [clojure.string :as str]
            [murakumo.config :as config]
            [murakumo.fleet.inventory :as inv]
            [murakumo.identity :as identity]
            [murakumo.provision.plan :as provision]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :cloud-plan)

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

(defn- oracle-str-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(defn- oracle-i64-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid export []))
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

;; ── host-mirror pure helpers ─────────────────────────────────────────

(def ^:private mirror-default-driver "murakumo-overlay")
(def ^:private mirror-default-cloud-name "murakumo.cloud")
(def ^:private mirror-default-cloud-domain "murakumo.cloud")
(def ^:private mirror-default-cloud-graph "murakumo-cloud")
(def ^:private mirror-default-auth-key-env "MURAKUMO_OVERLAY_AUTH_KEY")
(def ^:private mirror-overlay-version 1)

(defn- mirror-node-region [node]
  (or (get-in node [:labels :zone])
      (get-in node [:labels :region])
      (:region node)
      "global"))

(defn- mirror-relay-score [node relay]
  (if (= (mirror-node-region node) (:region relay)) 0 1))

(defn- mirror-overlay-id-input [cloud]
  (or (:overlay/id cloud) (:cloud/name cloud) "murakumo.cloud"))

(defn- mirror-node-id-input [overlay-cid node-name]
  (str overlay-cid ":" node-name))

(defn- mirror-quic-endpoint [host port]
  (str "quic://" host ":" port))

(defn- mirror-webrtc-endpoint [host p2p-port]
  (str "webrtc://" host ":" (+ 100 p2p-port)))

(defn- mirror-relay-endpoint-url [relay-url node-id]
  (str relay-url "/" node-id))

(defn- mirror-webtransport-endpoint [host http-port]
  (str "https://" host ":" http-port "/.well-known/murakumo/webtransport"))

(defn- mirror-transport-endpoint [scheme host]
  (str scheme "://" host))

(def ^:private mirror-dash-placeholder "-")
(def ^:private mirror-summary-nodes-header
  "  NODE           REGION     RELAY          DIRECT")
(def ^:private mirror-routes-header
  "  NODE           DIRECT                                      RELAY")
(def ^:private mirror-direct-candidates-label "  direct candidates:")
(def ^:private mirror-relays-section-label "  relays:")
(def ^:private mirror-connects-section-label "  connects:")

(defn- mirror-summary-title [domain overlay]
  (str "murakumo.cloud " domain "  overlay " overlay))

(defn- mirror-routes-title [overlay]
  (str "murakumo.cloud routes overlay " overlay))

(defn- mirror-bootstrap-title [overlay]
  (str "murakumo.cloud bootstrap overlay " overlay))

(defn- mirror-unknown-node-line [node-name]
  (str "unknown murakumo.cloud node: " node-name))

(defn- mirror-unknown-relay-line [relay-name]
  (str "unknown murakumo.cloud relay: " relay-name))

(defn- mirror-dial-denied-line [node-name]
  (str "murakumo.cloud dial " node-name " denied by policy"))

(defn- mirror-connect-denied-line [node-name]
  (str "murakumo.cloud connect " node-name " denied by policy"))

(defn- mirror-dial-ok-title [route-name node]
  (str "murakumo.cloud dial " route-name "  node " node))

(defn- mirror-connect-ok-title [node-name]
  (str "murakumo.cloud connect " node-name))

(defn- mirror-relay-ok-title [relay-name]
  (str "murakumo.cloud relay " relay-name))

(defn- mirror-from-to-cap-reason [from to capability reason]
  (str "  from=" from " to=" to " capability=" capability " reason=" reason))

(defn- mirror-authorized-line [from to capability]
  (str "  authorized: from=" from " to=" to " capability=" capability))

(defn- mirror-relay-fallback-line [endpoint]
  (str "  relay fallback: " endpoint))

(defn- mirror-reason-line [reason]
  (str "  reason=" reason))

(defn- mirror-indent-argv-line [argv-joined]
  (str "  " argv-joined))

(defn- mirror-address-family-line [af nodes relays]
  (str "  address-family " af " ; nodes " nodes " ; relays " relays))

(defn- mirror-policy-line [default allow-n]
  (str "  policy default=" default " allow=" allow-n))

(defn- mirror-skipped-reason-suffix [reason]
  (str " skipped reason=" reason))

;; ── dual-source defaults ─────────────────────────────────────────────

(def default-cloud-path config/default-cloud-path)

(def default-driver
  (oracle-str-const 'default-driver mirror-default-driver))

(def default-cloud
  {:cloud/name (oracle-str-const 'default-cloud-name mirror-default-cloud-name)
   :cloud/domain (oracle-str-const 'default-cloud-domain mirror-default-cloud-domain)
   :cloud/graph (oracle-str-const 'default-cloud-graph mirror-default-cloud-graph)
   :overlay/version (oracle-i64-const 'overlay-version mirror-overlay-version)
   :overlay/address-family :identity
   :overlay/direct [:quic :webrtc :webtransport]
   :overlay/relay [:murakumo-relay]
   :overlay/auth-key-env (oracle-str-const 'default-auth-key-env mirror-default-auth-key-env)
   :overlay/auth-key-source :operator-seed
   :relays []
   :policy {:default :deny :allow []}})

;; ── dual-source CLI presentation labels ──────────────────────────────

(def dash-placeholder
  (oracle-str-const 'dash-placeholder mirror-dash-placeholder))

(def summary-nodes-header
  (oracle-str-const 'summary-nodes-header mirror-summary-nodes-header))

(def routes-header
  (oracle-str-const 'routes-header mirror-routes-header))

(def direct-candidates-label
  (oracle-str-const 'direct-candidates-label mirror-direct-candidates-label))

(def relays-section-label
  (oracle-str-const 'relays-section-label mirror-relays-section-label))

(def connects-section-label
  (oracle-str-const 'connects-section-label mirror-connects-section-label))

(defn summary-title
  "CLI title for plan summary. Kotoba `summary-title` when ready."
  [domain overlay]
  (try-oracle
   #(o 'summary-title [(str domain) (str overlay)])
   #(mirror-summary-title domain overlay)))

(defn routes-title
  "CLI title for routes listing. Kotoba `routes-title` when ready."
  [overlay]
  (try-oracle
   #(o 'routes-title [(str overlay)])
   #(mirror-routes-title overlay)))

(defn bootstrap-title
  "CLI title for bootstrap listing. Kotoba `bootstrap-title` when ready."
  [overlay]
  (try-oracle
   #(o 'bootstrap-title [(str overlay)])
   #(mirror-bootstrap-title overlay)))

(defn unknown-node-line
  "Unknown node error line. Kotoba `unknown-node-line` when ready."
  [node-name]
  (try-oracle
   #(o 'unknown-node-line [(str node-name)])
   #(mirror-unknown-node-line node-name)))

(defn unknown-relay-line
  "Unknown relay error line. Kotoba `unknown-relay-line` when ready."
  [relay-name]
  (try-oracle
   #(o 'unknown-relay-line [(str relay-name)])
   #(mirror-unknown-relay-line relay-name)))

(defn dial-denied-line
  "Dial policy-denied title. Kotoba `dial-denied-line` when ready."
  [node-name]
  (try-oracle
   #(o 'dial-denied-line [(str node-name)])
   #(mirror-dial-denied-line node-name)))

(defn connect-denied-line
  "Connect policy-denied title. Kotoba `connect-denied-line` when ready."
  [node-name]
  (try-oracle
   #(o 'connect-denied-line [(str node-name)])
   #(mirror-connect-denied-line node-name)))

(defn dial-ok-title
  "Dial authorized title. Kotoba `dial-ok-title` when ready."
  [route-name node]
  (try-oracle
   #(o 'dial-ok-title [(str route-name) (str node)])
   #(mirror-dial-ok-title route-name node)))

(defn connect-ok-title
  "Connect authorized title. Kotoba `connect-ok-title` when ready."
  [node-name]
  (try-oracle
   #(o 'connect-ok-title [(str node-name)])
   #(mirror-connect-ok-title node-name)))

(defn relay-ok-title
  "Relay ok title. Kotoba `relay-ok-title` when ready."
  [relay-name]
  (try-oracle
   #(o 'relay-ok-title [(str relay-name)])
   #(mirror-relay-ok-title relay-name)))

(defn from-to-cap-reason
  "from/to/capability/reason detail line. Kotoba when ready."
  [from to capability reason]
  (try-oracle
   #(o 'from-to-cap-reason [(str from) (str to) (str capability) (str reason)])
   #(mirror-from-to-cap-reason from to capability reason)))

(defn authorized-line
  "authorized from/to/capability line. Kotoba when ready."
  [from to capability]
  (try-oracle
   #(o 'authorized-line [(str from) (str to) (str capability)])
   #(mirror-authorized-line from to capability)))

(defn relay-fallback-line
  "relay fallback detail line. Kotoba when ready."
  [endpoint]
  (try-oracle
   #(o 'relay-fallback-line [(str endpoint)])
   #(mirror-relay-fallback-line endpoint)))

(defn reason-line
  "reason= detail line. Kotoba when ready."
  [reason]
  (try-oracle
   #(o 'reason-line [(str reason)])
   #(mirror-reason-line reason)))

(defn indent-argv-line
  "Two-space indented argv join line. Kotoba when ready."
  [argv-joined]
  (try-oracle
   #(o 'indent-argv-line [(str argv-joined)])
   #(mirror-indent-argv-line argv-joined)))

(defn address-family-line
  "Summary address-family + node/relay counts. Kotoba when ready."
  [af nodes relays]
  (try-oracle
   #(o 'address-family-line [(str af)
                             (oracle/as-i64 nodes)
                             (oracle/as-i64 relays)])
   #(mirror-address-family-line af nodes relays)))

(defn policy-line
  "Summary policy default + allow count. Kotoba when ready."
  [default allow-n]
  (try-oracle
   #(o 'policy-line [(str default) (oracle/as-i64 allow-n)])
   #(mirror-policy-line default allow-n)))

(defn skipped-reason-suffix
  "Trailing ' skipped reason=…' fragment (name column padding stays host)."
  [reason]
  (try-oracle
   #(o 'skipped-reason-suffix [(str reason)])
   #(mirror-skipped-reason-suffix reason)))

(defn merge-defaults [cloud]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b)) (merge a b) b))
              default-cloud
              cloud))

(defn overlay-id
  "Stable CID for an overlay namespace.
   Preimage via kotoba `overlay-id-input` when oracle ready."
  [cloud]
  (identity/graph-cid
   (try-oracle
    #(o 'overlay-id-input
        [(str (or (:overlay/id cloud) ""))
         (str (or (:cloud/name cloud) ""))])
    #(mirror-overlay-id-input cloud))))

(defn node-id
  "Stable node CID inside an overlay.
   Preimage via kotoba `node-id-input` when oracle ready."
  [cloud node]
  (identity/graph-cid
   (try-oracle
    #(o 'node-id-input
        [(str (overlay-id cloud)) (str (:name node))])
    #(mirror-node-id-input (overlay-id cloud) (:name node)))))

(defn node-region
  "Kotoba `node-region` (zone / region-label / region / global) when ready."
  [node]
  (try-oracle
   #(o 'node-region
       [(str (or (get-in node [:labels :zone]) ""))
        (str (or (get-in node [:labels :region]) ""))
        (str (or (:region node) ""))])
   #(mirror-node-region node)))

(defn relay-score
  "Kotoba `relay-score` when oracle ready."
  [node relay]
  (try-oracle
   #(oracle/i64->host
     (o 'relay-score
        [(str (node-region node))
         (str (or (:region relay) ""))]))
   #(mirror-relay-score node relay)))

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
             :endpoint (try-oracle
                        #(o 'quic-endpoint [(str host) (oracle/as-i64 p2p-port)])
                        #(mirror-quic-endpoint host p2p-port))}
      :webrtc {:transport :webrtc
               :endpoint (try-oracle
                          #(o 'webrtc-endpoint [(str host) (oracle/as-i64 p2p-port)])
                          #(mirror-webrtc-endpoint host p2p-port))}
      :webtransport {:transport :webtransport
                     :endpoint (try-oracle
                                #(o 'webtransport-endpoint
                                    [(str host) (oracle/as-i64 http-port)])
                                #(mirror-webtransport-endpoint host http-port))}
      {:transport transport
       :endpoint (try-oracle
                  #(o 'transport-endpoint
                      [(name transport) (str host)])
                  #(mirror-transport-endpoint (name transport) host))})))

(defn relay-endpoint
  "Endpoint URL via kotoba `relay-endpoint-url` when ready."
  [relay node-id]
  (when relay
    {:relay (:name relay)
     :transport (first (:transports relay))
     :endpoint (try-oracle
                #(o 'relay-endpoint-url
                    [(str (:url relay)) (str node-id)])
                #(mirror-relay-endpoint-url (:url relay) node-id))}))


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
