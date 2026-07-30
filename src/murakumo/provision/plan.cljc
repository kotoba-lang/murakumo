;; murakumo.provision.plan — portable provision/mesh planning helpers.
;;
;; Host effects stay in murakumo.core: SSH reachability, rsync, launchctl, local
;; kotoba DID derivation, and filesystem reads. This namespace owns deterministic
;; strings and defaults used by those effects.
;;
;; W6 product-shell (ADR-260728-w6-provision-launchctl-tokens-pure-oracle +
;; ADR-260728-w6-provision-multiaddr-tokens-pure-oracle +
;; ADR-260728-w6-provision-peerid-plist-pure-oracle):
;; constants + port/multiaddr path tokens + launchctl shell tokens +
;; peer/link shell + rsync argv + peer-entry + home-bin-path + label/roles
;; join seps + peer-id DID/body patterns + render-plist placeholder tokens +
;; fold steps + peer-id-from-log scan + write-plist-shell DELEGATE to kotoba
;; provision_plan_core when oracle is loadable (JVM classpath or cljs/nbb).
;; Collection walks stay host; plist body content is still host-rendered XML.
;; cljs mirrors remain fallback.

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

(def ^:private mirror-launchctl-print-prefix "sudo launchctl print system/")
(def ^:private mirror-launchctl-bootout-prefix "sudo launchctl bootout system/")
(def ^:private mirror-launchctl-bootstrap-sys "sudo launchctl bootstrap system ")
(def ^:private mirror-launchctl-kickstart-prefix "sudo launchctl kickstart -k system/")
(def ^:private mirror-launchctl-status-suffix
  " >/dev/null 2>&1 && echo running || echo stopped")
(def ^:private mirror-launchctl-plist-quiet-semi ".plist 2>/dev/null; ")
(def ^:private mirror-launchctl-quiet-true-sleep " 2>/dev/null || true; sleep 1; ")
(def ^:private mirror-launchctl-plist-quiet-true-semi ".plist 2>/dev/null || true; ")
(def ^:private mirror-launchd-daemons-dir "/Library/LaunchDaemons/")
(def ^:private mirror-plist-ext ".plist")

(def launchctl-print-prefix
  "sudo launchctl print system/ prefix. Kotoba when ready."
  (oracle-str-const 'launchctl-print-prefix mirror-launchctl-print-prefix))

(def launchctl-bootout-prefix
  "sudo launchctl bootout system/ prefix. Kotoba when ready."
  (oracle-str-const 'launchctl-bootout-prefix mirror-launchctl-bootout-prefix))

(def launchctl-bootstrap-sys
  "sudo launchctl bootstrap system  verb prefix. Kotoba when ready."
  (oracle-str-const 'launchctl-bootstrap-sys mirror-launchctl-bootstrap-sys))

(def launchd-daemons-dir
  "System LaunchDaemons directory. Kotoba when ready."
  (oracle-str-const 'launchd-daemons-dir mirror-launchd-daemons-dir))

(def launchctl-bootstrap-prefix
  "bootstrap + LaunchDaemons path. Kotoba when ready."
  (oracle-str-const 'launchctl-bootstrap-prefix
                    (str mirror-launchctl-bootstrap-sys mirror-launchd-daemons-dir)))

(def launchctl-kickstart-prefix
  "sudo launchctl kickstart -k system/ prefix. Kotoba when ready."
  (oracle-str-const 'launchctl-kickstart-prefix mirror-launchctl-kickstart-prefix))

(def launchctl-status-suffix
  "running/stopped status probe suffix. Kotoba when ready."
  (oracle-str-const 'launchctl-status-suffix mirror-launchctl-status-suffix))

(def launchctl-plist-quiet-semi
  ".plist 2>/dev/null; mid for launch-up. Kotoba when ready."
  (oracle-str-const 'launchctl-plist-quiet-semi mirror-launchctl-plist-quiet-semi))

(def launchctl-quiet-true-sleep
  "quiet bootout + sleep mid for reprovision. Kotoba when ready."
  (oracle-str-const 'launchctl-quiet-true-sleep mirror-launchctl-quiet-true-sleep))

(def launchctl-plist-quiet-true-semi
  ".plist quiet-true mid for reprovision. Kotoba when ready."
  (oracle-str-const 'launchctl-plist-quiet-true-semi
                    mirror-launchctl-plist-quiet-true-semi))

(def plist-ext
  "LaunchDaemon .plist extension. Kotoba when ready."
  (oracle-str-const 'plist-ext mirror-plist-ext))

(defn- mirror-launch-status-command []
  (str launchctl-print-prefix mirror-plist-label launchctl-status-suffix))

(defn- mirror-launch-up-command []
  (str launchctl-bootstrap-prefix mirror-plist-label
       launchctl-plist-quiet-semi launchctl-kickstart-prefix mirror-plist-label))

(defn- mirror-launch-down-command []
  (str launchctl-bootout-prefix mirror-plist-label))

(defn- mirror-reprovision-command []
  (str launchctl-bootout-prefix mirror-plist-label launchctl-quiet-true-sleep
       launchctl-bootstrap-prefix mirror-plist-label
       launchctl-plist-quiet-true-semi
       launchctl-kickstart-prefix mirror-plist-label))

(defn- mirror-watchdog-reprovision-command []
  (str launchctl-bootout-prefix mirror-watchdog-label launchctl-quiet-true-sleep
       launchctl-bootstrap-prefix mirror-watchdog-label
       launchctl-plist-quiet-true-semi
       launchctl-kickstart-prefix mirror-watchdog-label))

(defn- mirror-operator-seed-missing? [operator-seed]
  (str/blank? (str operator-seed)))

(defn- mirror-node-p2p-port [fleet node]
  (or (:p2p-port node) (:fleet/p2p-port fleet) mirror-default-p2p-port))

(def ^:private mirror-multiaddr-ip4-prefix "/ip4/")
(def ^:private mirror-multiaddr-udp-mid "/udp/")
(def ^:private mirror-multiaddr-quic-suffix "/quic-v1")

(def multiaddr-ip4-prefix
  "libp2p multiaddr IP4 protocol prefix. Kotoba when ready."
  (oracle-str-const 'multiaddr-ip4-prefix mirror-multiaddr-ip4-prefix))

(def multiaddr-udp-mid
  "Between host and UDP port in multiaddr. Kotoba when ready."
  (oracle-str-const 'multiaddr-udp-mid mirror-multiaddr-udp-mid))

(def multiaddr-quic-suffix
  "Tailscale QUIC transport multiaddr suffix. Kotoba when ready."
  (oracle-str-const 'multiaddr-quic-suffix mirror-multiaddr-quic-suffix))

(defn- mirror-multiaddr [ip port]
  (str multiaddr-ip4-prefix ip multiaddr-udp-mid port multiaddr-quic-suffix))

(def ^:private mirror-rsync-bin "rsync")
(def ^:private mirror-rsync-az-flag "-az")
(def ^:private mirror-rsync-e-flag "-e")
(def ^:private mirror-plist-heredoc-footer "\nPLIST")

(defn- mirror-local-bin-path [local-bin bin]
  (str local-bin "/" bin))

(defn- mirror-remote-bin-dest [host bin]
  (str host ":.murakumo/bin/" bin))

(defn- mirror-launchd-daemon-path [label]
  (str launchd-daemons-dir label plist-ext))

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

(defn- mirror-join-append [acc sep next]
  (if (str/blank? (str acc))
    (str next)
    (str acc sep next)))

(defn- mirror-bootstrap-append [acc entry]
  (mirror-join-append acc mirror-peer-join-sep entry))

(defn- mirror-labels-append [acc pair]
  (mirror-join-append acc mirror-label-join-sep pair))

(defn- mirror-roles-append [acc role]
  (mirror-join-append acc mirror-roles-join-sep role))

(defn- mirror-plist-replace [tmpl ph val]
  (str/replace (str tmpl) (str ph) (str val)))

(defn- mirror-peer-id-from-log [out]
  (some-> (re-find #"did:key:(12D3[A-Za-z0-9]*)" (str out)) second))

(defn- mirror-write-plist-shell [label body]
  (str (mirror-tee-plist-prefix label) body mirror-plist-heredoc-footer))

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

(defn join-append
  "CSV-style fold step: empty acc ⇒ next only. Kotoba when ready."
  [acc sep next]
  (try-oracle
   #(o 'join-append [(str (or acc "")) (str sep) (str next)])
   #(mirror-join-append acc sep next)))

(defn bootstrap-append
  "Append one peer-entry to bootstrap-str acc. Kotoba when ready."
  [acc entry]
  (try-oracle
   #(o 'bootstrap-append [(str (or acc "")) (str entry)])
   #(mirror-bootstrap-append acc entry)))

(defn labels-append
  "Append one label-kv pair to labels-env acc. Kotoba when ready."
  [acc pair]
  (try-oracle
   #(o 'labels-append [(str (or acc "")) (str pair)])
   #(mirror-labels-append acc pair)))

(defn roles-append
  "Append one role name to roles CSV acc. Kotoba when ready."
  [acc role]
  (try-oracle
   #(o 'roles-append [(str (or acc "")) (str role)])
   #(mirror-roles-append acc role)))

(defn plist-replace
  "Substitute one placeholder token in a LaunchDaemon template. Kotoba when ready."
  [tmpl ph val]
  (try-oracle
   #(o 'plist-replace [(str tmpl) (str ph) (str (or val ""))])
   #(mirror-plist-replace tmpl ph val)))

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
   #(oracle/bool->host
     (o 'operator-seed-missing? [(str (or operator-seed ""))]))
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
  "Tailscale QUIC multiaddr for a node ip/port.
   Path tokens dual-sourced; multiaddr recomposes via kotoba when ready."
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
   Pair format dual-sourced via `peer-entry`; join step dual-sourced via
   `bootstrap-append`; node walk / peer lookup stay host."
  [fleet peers self]
  (reduce (fn [acc node]
            (if (= (:name node) (:name self))
              acc
              (if-let [peer-id (get peers (:name node))]
                (bootstrap-append
                 acc
                 (peer-entry peer-id (multiaddr (:ip node) (node-p2p-port fleet node))))
                acc)))
          ""
          (:nodes fleet)))

(defn peer-id-from-log
  "Extract the libp2p PeerId from kotoba mesh log output containing `did:key:<peerid>`.
   Kotoba pure scan (`peer-id-from-log`) when ready; blank → nil."
  [out]
  (try-oracle
   #(let [s (o 'peer-id-from-log [(str out)])]
      (when-not (str/blank? (str s)) s))
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
   Launchctl tokens dual-sourced; command recomposes via kotoba when ready."
  []
  (try-oracle
   #(o 'launch-status-command [])
   mirror-launch-status-command))

(defn write-plist-shell
  "Assemble sudo tee … <<'PLIST' shell for a LaunchDaemon label + body.
   Kotoba `write-plist-shell` when ready."
  [label body]
  (try-oracle
   #(o 'write-plist-shell [(str label) (str body)])
   #(mirror-write-plist-shell label body)))

(defn write-plist-command
  "Remote shell command that writes plist content to the system LaunchDaemon path.
   Shell assembly dual-sourced via `write-plist-shell`; body content is host XML."
  [plist]
  (write-plist-shell plist-label plist))

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
   Pair format dual-sourced via `label-kv`; join step dual-sourced via
   `labels-append`; map walk stays host."
  [labels]
  (reduce (fn [acc [k v]]
            (labels-append acc (label-kv (name k) v)))
          ""
          labels))

(defn- roles-csv
  "Comma-joined role names. Join step dual-sourced via `roles-append`."
  [roles]
  (reduce (fn [acc role] (roles-append acc (str role)))
          ""
          (or roles [])))

(defn render-plist
  "Render the LaunchDaemon plist template for a node.

   `identity` supplies host-derived or crypto-derived values:
   :operator-seed, :x25519-seed, :did, and :p2p-seed.
   Placeholder tokens + {{BIN}} path + roles/labels joins dual-sourced;
   each substitution dual-sourced via `plist-replace`; placeholder chain
   order stays host."
  [template fleet connect-spec peers node {:keys [user home operator-seed x25519-seed did p2p-seed]}]
  (-> template
      (plist-replace plist-ph-user user)
      (plist-replace plist-ph-bin (home-bin-path home))
      (plist-replace plist-ph-port (str (inv/node-port fleet node)))
      (plist-replace plist-ph-roles (roles-csv (:roles node)))
      (plist-replace plist-ph-labels (labels-env (:labels node)))
      (plist-replace plist-ph-home home)
      (plist-replace plist-ph-ed25519 operator-seed)
      (plist-replace plist-ph-x25519 x25519-seed)
      (plist-replace plist-ph-did did)
      (plist-replace plist-ph-p2pport (str (node-p2p-port fleet node)))
      (plist-replace plist-ph-p2pseed p2p-seed)
      (plist-replace plist-ph-extaddr (if (:ip node) (multiaddr (:ip node) (node-p2p-port fleet node)) ""))
      (plist-replace plist-ph-bootstrap (bootstrap-str fleet peers node))
      (plist-replace plist-ph-webrtc (str (node-webrtc-port fleet connect-spec node)))))

(defn launch-command
  "Shell command used to start or stop the resident LaunchDaemon.
   Launchctl tokens dual-sourced; up/down recomposes via kotoba when ready."
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
   Launchctl tokens dual-sourced; recomposes via kotoba when ready."
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
   Placeholder tokens + replace step dual-sourced; chain order stays host."
  [tmpl fleet node {:keys [user home]}]
  (-> tmpl
      (plist-replace plist-ph-user user)
      (plist-replace plist-ph-home home)
      (plist-replace plist-ph-port (str (inv/node-port fleet node)))))

(defn write-watchdog-plist-command
  "Remote shell command that writes the watchdog plist to the system LaunchDaemon path.
   Shell assembly dual-sourced via `write-plist-shell`; body content is host XML."
  [plist]
  (write-plist-shell watchdog-label plist))

(defn watchdog-reprovision-command
  "Reload + kickstart the watchdog (same bootout-settle-bootstrap dance as the mesh).
   Launchctl tokens dual-sourced; recomposes via kotoba when ready."
  []
  (try-oracle
   #(o 'watchdog-reprovision-command [])
   mirror-watchdog-reprovision-command))
