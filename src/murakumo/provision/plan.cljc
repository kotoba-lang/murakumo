;; murakumo.provision.plan — portable provision/mesh planning helpers.
;;
;; Host effects stay in murakumo.core: SSH reachability, rsync, launchctl, local
;; kotoba DID derivation, and filesystem reads. This namespace owns deterministic
;; strings and defaults used by those effects.
;;
;; W6 product-shell (ADR-260728-w6-cljs-clj-residual-dual):
;; constants + port/multiaddr pure helpers DELEGATE to kotoba provision_plan_core
;; when oracle is loadable (JVM classpath or cljs/nbb). cljs mirrors remain
;; fallback when oracle is not ready.

(ns murakumo.provision.plan
  "Portable provision/mesh planning helpers.
   W6 product-shell: constants + port/multiaddr pure helpers via kotoba
   provision_plan_core when oracle ready."
  (:require [clojure.string :as str]
            [murakumo.connect :as connect]
            [murakumo.fleet.inventory :as inv]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :provision-plan)

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

(def ^:private mirror-plist-label "com.murakumo.kotoba-mesh")
(def ^:private mirror-remote-bin "$HOME/.murakumo/bin")
(def ^:private mirror-remote-store "$HOME/.murakumo/store")
(def ^:private mirror-ssh-rsync-options "ssh -o BatchMode=yes -o ConnectTimeout=8")
(def ^:private mirror-peer-advertise-wait-ms 8000)
(def ^:private mirror-default-p2p-port 4001)

(def ^:private mirror-mesh-binary-status-command
  "test -x $HOME/.murakumo/bin/kotoba-server && echo installed || echo absent")

(def ^:private mirror-remote-store-command
  "mkdir -p $HOME/.murakumo/bin $HOME/.murakumo/store")

(defn- mirror-operator-seed-missing? [operator-seed]
  (str/blank? (str operator-seed)))

(defn- mirror-node-p2p-port [fleet node]
  (or (:p2p-port node) (:fleet/p2p-port fleet) mirror-default-p2p-port))

(defn- mirror-multiaddr [ip port]
  (str "/ip4/" ip "/udp/" port "/quic-v1"))

;; ── dual-source constants ────────────────────────────────────────────

(def plist-label
  (oracle-str-const 'plist-label mirror-plist-label))

(def remote-bin
  (oracle-str-const 'remote-bin mirror-remote-bin))

(def remote-store
  (oracle-str-const 'remote-store mirror-remote-store))

(def ssh-rsync-options
  (oracle-str-const 'ssh-rsync-options mirror-ssh-rsync-options))

(def peer-advertise-wait-ms
  (oracle-i64-const 'peer-advertise-wait-ms mirror-peer-advertise-wait-ms))

(defn operator-seed-missing?
  "True when a command requiring the fleet operator seed should fail.
   Kotoba `operator-seed-missing?` when oracle ready."
  [operator-seed]
  (try-oracle
   #(= 1 (oracle/i64->host
          (o 'operator-seed-missing? [(str (or operator-seed ""))])))
   #(mirror-operator-seed-missing? operator-seed)))

(defn provision-command-error
  "Validation error keyword for provision, or nil."
  [operator-seed]
  (when (operator-seed-missing? operator-seed)
    :missing-operator-seed-hex))

(defn mesh-command-error
  "Validation error keyword for mesh, or nil."
  [operator-seed]
  (when (operator-seed-missing? operator-seed)
    :missing-operator-seed))

(defn node-p2p-port
  "Resolve a node's p2p QUIC port, defaulting to fleet p2p port, then 4001.
   Kotoba `resolve-p2p-port` with Product Value ABI optional ports when ready."
  [fleet node]
  (try-oracle
   #(oracle/i64->host
     (o 'resolve-p2p-port
        [(oracle/option-i64 (:p2p-port node))
         (oracle/option-i64 (:fleet/p2p-port fleet))]))
   #(mirror-node-p2p-port fleet node)))

(defn multiaddr
  "Tailscale QUIC multiaddr for a node ip/port. Kotoba `multiaddr` when ready."
  [ip port]
  (try-oracle
   #(o 'multiaddr [(str ip) (oracle/as-i64 port)])
   #(mirror-multiaddr ip port)))

(defn node-webrtc-port
  "The /webrtc-direct UDP port for nodes whose class speaks :webrtc on :live.
   Offset +100 from the p2p port so it never clashes with the QUIC port."
  [fleet connect-spec node]
  (when (and connect-spec
             (some #{:webrtc}
                   (set (connect/class-transports
                         connect-spec
                         (connect/node-class connect-spec node)
                         :live))))
    (let [p2p (node-p2p-port fleet node)]
      (try-oracle
       #(oracle/i64->host (o 'webrtc-port [(oracle/as-i64 p2p)]))
       #(+ 100 p2p)))))

(defn bootstrap-str
  "Comma-list of `peerid@multiaddr` for every other node with a known PeerId."
  [fleet peers self]
  (->> (:nodes fleet)
       (remove #(= (:name %) (:name self)))
       (keep (fn [node]
               (when-let [peer-id (get peers (:name node))]
                 (str peer-id "@" (multiaddr (:ip node) (node-p2p-port fleet node))))))
       (str/join ",")))

(defn peer-id-from-log
  "Extract the libp2p PeerId from kotoba mesh log output containing `did:key:<peerid>`."
  [out]
  (some-> (re-find #"did:key:(12D3[A-Za-z0-9]*)" (str out)) second))

(defn collected-peers
  "Build the persisted node-name → PeerId map from node/peer pairs."
  [node-peer-pairs]
  (into {}
        (keep (fn [[node peer-id]]
                (when peer-id
                  [(:name node) peer-id])))
        node-peer-pairs))

(defn peer-probe-targets
  "Nodes eligible for PeerId probing after mesh pass 1."
  [nodes reachable-node?]
  (filterv #(and (:ip %) (reachable-node? %)) nodes))

(defn peer-probe-plan
  "Nodes that should be probed for PeerIds after mesh pass 1."
  [nodes reachable-node?]
  (mapv (fn [node] {:node node :host (:host node)})
        (peer-probe-targets nodes reachable-node?)))

(defn peer-probe-results
  "Probe PeerIds for every eligible node using a caller-supplied host reader."
  [nodes reachable-node? read-peer-id]
  (mapv (fn [{:keys [node host]}]
          [node (read-peer-id host)])
        (peer-probe-plan nodes reachable-node?)))

(defn collected-peers-from-results
  "Build peers from peer-probe-plan results shaped as [node peer-id]."
  [node-peer-results]
  (collected-peers node-peer-results))

(defn mesh-binary-status-command
  "Kotoba `mesh-binary-status-command` when oracle ready."
  []
  (try-oracle
   #(o 'mesh-binary-status-command [])
   (fn [] mirror-mesh-binary-status-command)))

(defn remote-store-command
  "Kotoba `remote-store-command` when oracle ready."
  []
  (try-oracle
   #(o 'remote-store-command [])
   (fn [] mirror-remote-store-command)))

(defn rsync-binary-argv
  "argv for copying one pinned binary to a fleet node."
  [local-bin host bin]
  ["rsync" "-az" "-e" ssh-rsync-options
   (str local-bin "/" bin)
   (str host ":.murakumo/bin/" bin)])

(defn launch-status-command
  "Remote shell command that reports whether the resident launchd label is running."
  []
  (str "sudo launchctl print system/" plist-label
       " >/dev/null 2>&1 && echo running || echo stopped"))

(defn write-plist-command
  "Remote shell command that writes plist content to the system LaunchDaemon path."
  [plist]
  (str "sudo tee /Library/LaunchDaemons/" plist-label ".plist >/dev/null <<'PLIST'\n"
       plist "\nPLIST"))

(defn peer-id-log-command
  "Remote shell command that prints the latest node PeerId DID from mesh.log."
  []
  "grep -ho 'did:key:12D3[A-Za-z0-9]*' ~/.murakumo/mesh.log 2>/dev/null | tail -1")

(defn live-link-count-command
  "Remote shell command that counts distinct connected libp2p peers."
  []
  "grep 'kotoba-net: peer connected' ~/.murakumo/mesh.log 2>/dev/null | grep -o '12D3[A-Za-z0-9]*' | sort -u | wc -l")

(defn live-link-count-output
  "Normalise the stdout from live-link-count-command."
  [out]
  (str/trim (str out)))

(defn labels-env
  "Render node labels as the launchd env string `k=v,k=v`."
  [labels]
  (->> labels
       (map (fn [[k v]] (str (name k) "=" v)))
       (str/join ",")))

(defn render-plist
  "Render the LaunchDaemon plist template for a node.

   `identity` supplies host-derived or crypto-derived values:
   :operator-seed, :x25519-seed, :did, and :p2p-seed."
  [template fleet connect-spec peers node {:keys [user home operator-seed x25519-seed did p2p-seed]}]
  (-> template
      (str/replace "{{USER}}" user)
      (str/replace "{{BIN}}" (str home "/.murakumo/bin"))
      (str/replace "{{PORT}}" (str (inv/node-port fleet node)))
      (str/replace "{{ROLES}}" (str/join "," (:roles node)))
      (str/replace "{{LABELS}}" (labels-env (:labels node)))
      (str/replace "{{HOME}}" home)
      (str/replace "{{ED25519}}" operator-seed)
      (str/replace "{{X25519}}" x25519-seed)
      (str/replace "{{DID}}" did)
      (str/replace "{{P2PPORT}}" (str (node-p2p-port fleet node)))
      (str/replace "{{P2PSEED}}" p2p-seed)
      (str/replace "{{EXTADDR}}" (if (:ip node) (multiaddr (:ip node) (node-p2p-port fleet node)) ""))
      (str/replace "{{BOOTSTRAP}}" (bootstrap-str fleet peers node))
      (str/replace "{{WEBRTC}}" (str (node-webrtc-port fleet connect-spec node)))))

(defn launch-command
  "Shell command used to start or stop the resident LaunchDaemon."
  [action]
  (case action
    :up (str "sudo launchctl bootstrap system /Library/LaunchDaemons/" plist-label
             ".plist 2>/dev/null; sudo launchctl kickstart -k system/" plist-label)
    :down (str "sudo launchctl bootout system/" plist-label)))

(defn launch-plan
  "Host command plan for changing one resident node state."
  [node action]
  {:node node
   :host (:host node)
   :command (launch-command action)})

(defn launch-plans
  "Host command plans for changing resident node state."
  [nodes action]
  (mapv #(launch-plan % action) nodes))

(defn launch-results
  "Run launch plans with a caller-supplied host command runner."
  [nodes action run-host-command]
  (mapv (fn [{:keys [node host command]}]
          [node (run-host-command host command)])
        (launch-plans nodes action)))

(defn reprovision-command
  "Shell command used after writing the plist to reload and kickstart it."
  []
  (str "sudo launchctl bootout system/" plist-label " 2>/dev/null || true; sleep 1; "
       "sudo launchctl bootstrap system /Library/LaunchDaemons/" plist-label ".plist 2>/dev/null || true; "
       "sudo launchctl kickstart -k system/" plist-label))

;; ── HTTP-wedge watchdog (com.murakumo.kotoba-mesh-watchdog) ─────────────────
;; kotoba-server can wedge its HTTP surface while libp2p stays alive (2026-07-02,
;; pin 4f38b74a): the process never exits, so the mesh daemon's KeepAlive cannot
;; heal it. A sibling StartInterval daemon probes /health and kills the server on
;; two consecutive failures — KeepAlive then restarts it.

(def watchdog-label "com.murakumo.kotoba-mesh-watchdog")

(defn render-watchdog-plist
  "Substitute the watchdog template's placeholders for one node."
  [tmpl fleet node {:keys [user home]}]
  (-> tmpl
      (str/replace "{{USER}}" user)
      (str/replace "{{HOME}}" home)
      (str/replace "{{PORT}}" (str (inv/node-port fleet node)))))

(defn write-watchdog-plist-command
  "Remote shell command that writes the watchdog plist to the system LaunchDaemon path."
  [plist]
  (str "sudo tee /Library/LaunchDaemons/" watchdog-label ".plist >/dev/null <<'PLIST'\n"
       plist "\nPLIST"))

(defn watchdog-reprovision-command
  "Reload + kickstart the watchdog (same bootout-settle-bootstrap dance as the mesh)."
  []
  (str "sudo launchctl bootout system/" watchdog-label " 2>/dev/null || true; sleep 1; "
       "sudo launchctl bootstrap system /Library/LaunchDaemons/" watchdog-label ".plist 2>/dev/null || true; "
       "sudo launchctl kickstart -k system/" watchdog-label))
