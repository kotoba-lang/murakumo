;; murakumo.provision.plan — portable provision/mesh planning helpers.
;;
;; Host effects stay in murakumo.core: SSH reachability, rsync, launchctl, local
;; kotoba DID derivation, and filesystem reads. This namespace owns deterministic
;; strings and defaults used by those effects.
;;
;; W6 product-shell (ADR-260728-w6-provision-plist-ph-pure-oracle):
;; constants + port/multiaddr + launch/peer/link shell + rsync argv +
;; peer-entry + home-bin-path + label/roles join seps + peer-id DID/body
;; patterns + render-plist placeholder tokens DELEGATE to kotoba
;; provision_plan_core when oracle is loadable (JVM classpath or cljs/nbb).
;; bootstrap fold, peer-id re-find host, write-plist heredoc body, template
;; replace fold stay host. cljs mirrors remain fallback when oracle is not ready.

(ns murakumo.provision.plan
  "Portable provision/mesh planning helpers.
   W6 product-shell: path/port + shell/rsync/peer-entry/plist/peer-id pure via provision_plan_core."
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

(def ^:private mirror-peer-id-log-command
  "grep -ho 'did:key:12D3[A-Za-z0-9]*' ~/.murakumo/mesh.log 2>/dev/null | tail -1")

(def ^:private mirror-live-link-count-command
  "grep 'kotoba-net: peer connected' ~/.murakumo/mesh.log 2>/dev/null | grep -o '12D3[A-Za-z0-9]*' | sort -u | wc -l")

(def ^:private mirror-watchdog-label "com.murakumo.kotoba-mesh-watchdog")

(defn- mirror-launch-status-command []
  (str "sudo launchctl print system/" mirror-plist-label
       " >/dev/null 2>&1 && echo running || echo stopped"))

(defn- mirror-launch-up-command []
  (str "sudo launchctl bootstrap system /Library/LaunchDaemons/" mirror-plist-label
       ".plist 2>/dev/null; sudo launchctl kickstart -k system/" mirror-plist-label))

(defn- mirror-launch-down-command []
  (str "sudo launchctl bootout system/" mirror-plist-label))

(defn- mirror-reprovision-command []
  (str "sudo launchctl bootout system/" mirror-plist-label " 2>/dev/null || true; sleep 1; "
       "sudo launchctl bootstrap system /Library/LaunchDaemons/" mirror-plist-label
       ".plist 2>/dev/null || true; "
       "sudo launchctl kickstart -k system/" mirror-plist-label))

(defn- mirror-watchdog-reprovision-command []
  (str "sudo launchctl bootout system/" mirror-watchdog-label " 2>/dev/null || true; sleep 1; "
       "sudo launchctl bootstrap system /Library/LaunchDaemons/" mirror-watchdog-label
       ".plist 2>/dev/null || true; "
       "sudo launchctl kickstart -k system/" mirror-watchdog-label))

(defn- mirror-operator-seed-missing? [operator-seed]
  (str/blank? (str operator-seed)))

(defn- mirror-node-p2p-port [fleet node]
  (or (:p2p-port node) (:fleet/p2p-port fleet) mirror-default-p2p-port))

(defn- mirror-multiaddr [ip port]
  (str "/ip4/" ip "/udp/" port "/quic-v1"))

(def ^:private mirror-rsync-bin "rsync")
(def ^:private mirror-rsync-az-flag "-az")
(def ^:private mirror-rsync-e-flag "-e")
(def ^:private mirror-plist-heredoc-footer "\nPLIST")

(defn- mirror-local-bin-path [local-bin bin]
  (str local-bin "/" bin))

(defn- mirror-remote-bin-dest [host bin]
  (str host ":.murakumo/bin/" bin))

(defn- mirror-launchd-daemon-path [label]
  (str "/Library/LaunchDaemons/" label ".plist"))

(defn- mirror-tee-plist-prefix [label]
  (str "sudo tee " (mirror-launchd-daemon-path label)
       " >/dev/null <<'PLIST'\n"))

(defn- mirror-label-kv [k v]
  (str k "=" v))

(def ^:private mirror-peer-at-sep "@")
(def ^:private mirror-peer-join-sep ",")
(def ^:private mirror-did-key-prefix "did:key:")
(def ^:private mirror-peer-id-body-prefix "12D3")
(def ^:private mirror-peer-id-body-pattern "12D3[A-Za-z0-9]*")
(def ^:private mirror-peer-id-did-pattern "did:key:12D3[A-Za-z0-9]*")
(def ^:private mirror-home-bin-suffix "/.murakumo/bin")
(def ^:private mirror-label-join-sep ",")
(def ^:private mirror-roles-join-sep ",")
(def ^:private mirror-plist-ph-user "{{USER}}")
(def ^:private mirror-plist-ph-bin "{{BIN}}")
(def ^:private mirror-plist-ph-port "{{PORT}}")
(def ^:private mirror-plist-ph-roles "{{ROLES}}")
(def ^:private mirror-plist-ph-labels "{{LABELS}}")
(def ^:private mirror-plist-ph-home "{{HOME}}")
(def ^:private mirror-plist-ph-ed25519 "{{ED25519}}")
(def ^:private mirror-plist-ph-x25519 "{{X25519}}")
(def ^:private mirror-plist-ph-did "{{DID}}")
(def ^:private mirror-plist-ph-p2pport "{{P2PPORT}}")
(def ^:private mirror-plist-ph-p2pseed "{{P2PSEED}}")
(def ^:private mirror-plist-ph-extaddr "{{EXTADDR}}")
(def ^:private mirror-plist-ph-bootstrap "{{BOOTSTRAP}}")
(def ^:private mirror-plist-ph-webrtc "{{WEBRTC}}")

(defn- mirror-peer-entry [peer-id multiaddr]
  (str peer-id "@" multiaddr))

(defn- mirror-did-peer-id [peer-id]
  (str mirror-did-key-prefix peer-id))

(defn- mirror-home-bin-path [home]
  (str home "/.murakumo/bin"))

(defn- mirror-peer-id-from-log [out]
  (some-> (re-find #"did:key:(12D3[A-Za-z0-9]*)" (str out)) second))

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

(def rsync-bin
  "rsync binary name. Kotoba `rsync-bin` when ready."
  (oracle-str-const 'rsync-bin mirror-rsync-bin))

(def rsync-az-flag
  "rsync -az flag. Kotoba `rsync-az-flag` when ready."
  (oracle-str-const 'rsync-az-flag mirror-rsync-az-flag))

(def rsync-e-flag
  "rsync -e flag. Kotoba `rsync-e-flag` when ready."
  (oracle-str-const 'rsync-e-flag mirror-rsync-e-flag))

(def plist-heredoc-footer
  "Heredoc closer for write-plist. Kotoba `plist-heredoc-footer` when ready."
  (oracle-str-const 'plist-heredoc-footer mirror-plist-heredoc-footer))

(defn local-bin-path
  "Local pin path for one binary. Kotoba `local-bin-path` when ready."
  [local-bin bin]
  (try-oracle
   #(o 'local-bin-path [(str local-bin) (str bin)])
   #(mirror-local-bin-path local-bin bin)))

(defn remote-bin-dest
  "Remote rsync dest for one binary. Kotoba `remote-bin-dest` when ready."
  [host bin]
  (try-oracle
   #(o 'remote-bin-dest [(str host) (str bin)])
   #(mirror-remote-bin-dest host bin)))

(defn launchd-daemon-path
  "System LaunchDaemon path for label. Kotoba when ready."
  [label]
  (try-oracle
   #(o 'launchd-daemon-path [(str label)])
   #(mirror-launchd-daemon-path label)))

(defn tee-plist-prefix
  "sudo tee … <<'PLIST'\\n prefix. Heredoc body stays host."
  [label]
  (try-oracle
   #(o 'tee-plist-prefix [(str label)])
   #(mirror-tee-plist-prefix label)))

(defn label-kv
  "Single k=v label pair. Host joins with comma. Kotoba when ready."
  [k v]
  (try-oracle
   #(o 'label-kv [(str k) (str v)])
   #(mirror-label-kv k v)))

(def peer-at-sep
  "Separator between peer-id and multiaddr. Kotoba when ready."
  (oracle-str-const 'peer-at-sep mirror-peer-at-sep))

(def peer-join-sep
  "Comma separator for bootstrap peer list. Kotoba when ready."
  (oracle-str-const 'peer-join-sep mirror-peer-join-sep))

(def did-key-prefix
  "DID key URI prefix for mesh PeerIds. Kotoba when ready."
  (oracle-str-const 'did-key-prefix mirror-did-key-prefix))

(def peer-id-body-prefix
  "libp2p PeerId body prefix in mesh logs. Kotoba when ready."
  (oracle-str-const 'peer-id-body-prefix mirror-peer-id-body-prefix))

(def peer-id-body-pattern
  "grep -o pattern for PeerId body. Kotoba when ready."
  (oracle-str-const 'peer-id-body-pattern mirror-peer-id-body-pattern))

(def peer-id-did-pattern
  "grep -ho pattern for did:key:PeerId. Kotoba when ready."
  (oracle-str-const 'peer-id-did-pattern mirror-peer-id-did-pattern))

(defn peer-entry
  "One bootstrap peer `peer-id@multiaddr`. Kotoba `peer-entry` when ready."
  [peer-id multiaddr]
  (try-oracle
   #(o 'peer-entry [(str peer-id) (str multiaddr)])
   #(mirror-peer-entry peer-id multiaddr)))

(defn did-peer-id
  "DID URI for a mesh PeerId (`did:key:` + body). Kotoba when ready."
  [peer-id]
  (try-oracle
   #(o 'did-peer-id [(str peer-id)])
   #(mirror-did-peer-id peer-id)))

(def home-bin-suffix
  "Path under node home for murakumo binaries ({{BIN}}). Kotoba when ready."
  (oracle-str-const 'home-bin-suffix mirror-home-bin-suffix))

(def label-join-sep
  "Comma separator for labels-env. Kotoba when ready."
  (oracle-str-const 'label-join-sep mirror-label-join-sep))

(def roles-join-sep
  "Comma separator for node roles CSV. Kotoba when ready."
  (oracle-str-const 'roles-join-sep mirror-roles-join-sep))

(def plist-ph-user
  "LaunchDaemon template placeholder {{USER}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-user mirror-plist-ph-user))

(def plist-ph-bin
  "LaunchDaemon template placeholder {{BIN}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-bin mirror-plist-ph-bin))

(def plist-ph-port
  "LaunchDaemon template placeholder {{PORT}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-port mirror-plist-ph-port))

(def plist-ph-roles
  "LaunchDaemon template placeholder {{ROLES}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-roles mirror-plist-ph-roles))

(def plist-ph-labels
  "LaunchDaemon template placeholder {{LABELS}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-labels mirror-plist-ph-labels))

(def plist-ph-home
  "LaunchDaemon template placeholder {{HOME}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-home mirror-plist-ph-home))

(def plist-ph-ed25519
  "LaunchDaemon template placeholder {{ED25519}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-ed25519 mirror-plist-ph-ed25519))

(def plist-ph-x25519
  "LaunchDaemon template placeholder {{X25519}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-x25519 mirror-plist-ph-x25519))

(def plist-ph-did
  "LaunchDaemon template placeholder {{DID}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-did mirror-plist-ph-did))

(def plist-ph-p2pport
  "LaunchDaemon template placeholder {{P2PPORT}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-p2pport mirror-plist-ph-p2pport))

(def plist-ph-p2pseed
  "LaunchDaemon template placeholder {{P2PSEED}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-p2pseed mirror-plist-ph-p2pseed))

(def plist-ph-extaddr
  "LaunchDaemon template placeholder {{EXTADDR}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-extaddr mirror-plist-ph-extaddr))

(def plist-ph-bootstrap
  "LaunchDaemon template placeholder {{BOOTSTRAP}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-bootstrap mirror-plist-ph-bootstrap))

(def plist-ph-webrtc
  "LaunchDaemon template placeholder {{WEBRTC}}. Kotoba when ready."
  (oracle-str-const 'plist-ph-webrtc mirror-plist-ph-webrtc))

(defn home-bin-path
  "Absolute `{{BIN}}` path under node home. Kotoba `home-bin-path` when ready."
  [home]
  (try-oracle
   #(o 'home-bin-path [(str home)])
   #(mirror-home-bin-path home)))

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
  "Comma-list of `peerid@multiaddr` for every other node with a known PeerId.
   Pair format dual-sourced via `peer-entry`; fold + join stay host."
  [fleet peers self]
  (->> (:nodes fleet)
       (remove #(= (:name %) (:name self)))
       (keep (fn [node]
               (when-let [peer-id (get peers (:name node))]
                 (peer-entry peer-id (multiaddr (:ip node) (node-p2p-port fleet node))))))
       (str/join peer-join-sep)))

(defn peer-id-from-log
  "Extract the libp2p PeerId from kotoba mesh log output containing `did:key:<peerid>`.
   Pattern fragments dual-sourced via `did-key-prefix` / `peer-id-body-prefix`;
   re-find stays host."
  [out]
  (try-oracle
   #(let [re (re-pattern
              (str did-key-prefix
                   "(" peer-id-body-prefix "[A-Za-z0-9]*)"))]
      (some-> (re-find re (str out)) second))
   #(mirror-peer-id-from-log out)))

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
  "argv for copying one pinned binary to a fleet node.
   Bin/flags + path fragments dual-sourced via rsync-* / local-bin-path /
   remote-bin-dest; ssh-rsync-options already dual-sourced."
  [local-bin host bin]
  [rsync-bin rsync-az-flag rsync-e-flag ssh-rsync-options
   (local-bin-path local-bin bin)
   (remote-bin-dest host bin)])

(defn launch-status-command
  "Remote shell command that reports whether the resident launchd label is running.
   Kotoba `launch-status-command` when ready."
  []
  (try-oracle
   #(o 'launch-status-command [])
   mirror-launch-status-command))

(defn write-plist-command
  "Remote shell command that writes plist content to the system LaunchDaemon path.
   tee prefix + footer dual-sourced; heredoc body stays host (quoting)."
  [plist]
  (str (tee-plist-prefix plist-label) plist plist-heredoc-footer))

(defn peer-id-log-command
  "Remote shell command that prints the latest node PeerId DID from mesh.log.
   Kotoba when ready."
  []
  (try-oracle
   #(o 'peer-id-log-command [])
   (fn [] mirror-peer-id-log-command)))

(defn live-link-count-command
  "Remote shell command that counts distinct connected libp2p peers.
   Kotoba when ready."
  []
  (try-oracle
   #(o 'live-link-count-command [])
   (fn [] mirror-live-link-count-command)))

(defn live-link-count-output
  "Normalise the stdout from live-link-count-command.
   Kotoba `live-link-count-output` (trim) when ready."
  [out]
  (try-oracle
   #(o 'live-link-count-output [(str out)])
   #(str/trim (str out))))

(defn labels-env
  "Render node labels as the launchd env string `k=v,k=v`.
   Pair format dual-sourced via `label-kv`; sep dual-sourced via `label-join-sep`;
   map fold stays host."
  [labels]
  (->> labels
       (map (fn [[k v]] (label-kv (name k) v)))
       (str/join label-join-sep)))

(defn render-plist
  "Render the LaunchDaemon plist template for a node.

   `identity` supplies host-derived or crypto-derived values:
   :operator-seed, :x25519-seed, :did, and :p2p-seed.
   Placeholder tokens + {{BIN}} path + roles/labels join seps dual-sourced;
   template replace fold stays host."
  [template fleet connect-spec peers node {:keys [user home operator-seed x25519-seed did p2p-seed]}]
  (-> template
      (str/replace plist-ph-user user)
      (str/replace plist-ph-bin (home-bin-path home))
      (str/replace plist-ph-port (str (inv/node-port fleet node)))
      (str/replace plist-ph-roles (str/join roles-join-sep (:roles node)))
      (str/replace plist-ph-labels (labels-env (:labels node)))
      (str/replace plist-ph-home home)
      (str/replace plist-ph-ed25519 operator-seed)
      (str/replace plist-ph-x25519 x25519-seed)
      (str/replace plist-ph-did did)
      (str/replace plist-ph-p2pport (str (node-p2p-port fleet node)))
      (str/replace plist-ph-p2pseed p2p-seed)
      (str/replace plist-ph-extaddr (if (:ip node) (multiaddr (:ip node) (node-p2p-port fleet node)) ""))
      (str/replace plist-ph-bootstrap (bootstrap-str fleet peers node))
      (str/replace plist-ph-webrtc (str (node-webrtc-port fleet connect-spec node)))))

(defn launch-command
  "Shell command used to start or stop the resident LaunchDaemon.
   Kotoba launch-up/down-command when ready."
  [action]
  (case action
    :up (try-oracle
         #(o 'launch-up-command [])
         mirror-launch-up-command)
    :down (try-oracle
           #(o 'launch-down-command [])
           mirror-launch-down-command)))

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
  "Shell command used after writing the plist to reload and kickstart it.
   Kotoba `reprovision-command` when ready."
  []
  (try-oracle
   #(o 'reprovision-command [])
   mirror-reprovision-command))

;; ── HTTP-wedge watchdog (com.murakumo.kotoba-mesh-watchdog) ─────────────────
;; kotoba-server can wedge its HTTP surface while libp2p stays alive (2026-07-02,
;; pin 4f38b74a): the process never exits, so the mesh daemon's KeepAlive cannot
;; heal it. A sibling StartInterval daemon probes /health and kills the server on
;; two consecutive failures — KeepAlive then restarts it.

(def watchdog-label
  (oracle-str-const 'watchdog-label mirror-watchdog-label))

(defn render-watchdog-plist
  "Substitute the watchdog template's placeholders for one node.
   Placeholder tokens dual-sourced; replace fold stays host."
  [tmpl fleet node {:keys [user home]}]
  (-> tmpl
      (str/replace plist-ph-user user)
      (str/replace plist-ph-home home)
      (str/replace plist-ph-port (str (inv/node-port fleet node)))))

(defn write-watchdog-plist-command
  "Remote shell command that writes the watchdog plist to the system LaunchDaemon path.
   tee prefix + footer dual-sourced; heredoc body stays host."
  [plist]
  (str (tee-plist-prefix watchdog-label) plist plist-heredoc-footer))

(defn watchdog-reprovision-command
  "Reload + kickstart the watchdog (same bootout-settle-bootstrap dance as the mesh).
   Kotoba `watchdog-reprovision-command` when ready."
  []
  (try-oracle
   #(o 'watchdog-reprovision-command [])
   mirror-watchdog-reprovision-command))
