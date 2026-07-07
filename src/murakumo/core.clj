;; murakumo.core — control plane for the kotoba WASM lattice/mesh across the
;; Tailscale Mac-mini fleet. One terminal drives provisioning, residence (launchd),
;; fleet-wide status aggregation, and WASM-component deployment.
;;
;; Why this exists: kotoba ships a single-node `kotoba lattice ps` and no
;; fleet-facing control surface. murakumo is that surface — a thin bb/clj operator
;; that SSHes the fleet (no agent to install on nodes beyond the kotoba binaries),
;; renders a resident LaunchAgent per node, and folds every node's /health +
;; lattice participation into one view.

(ns murakumo.core
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [murakumo.config :as config]
            [murakumo.connect :as connect]
            [murakumo.dash.state :as state]
            [murakumo.deploy.plan :as deploy]
            [murakumo.fleet :as fleet]
            [murakumo.identity :as identity]
            [murakumo.provision.plan :as provision]
            [murakumo.report :as report]
            [murakumo.reconcile :as reconcile]
            [murakumo.ssh :as ssh]
            [murakumo.tunnel :as tunnel]))

;; Binary resolution (raced-checkout safety): murakumo deploys a PINNED binary
;; set it owns (./bin/, gitignored) so a concurrent rebuild of the shared kotoba
;; checkout can't swap the CLI/server protocol out from under a live fleet.
;; Fallbacks: $MURAKUMO_BIN, then the kotoba checkout's target/.
(def ^:private runtime
  (config/current-runtime-context))

(def ^:private kotoba-dir (:kotoba-dir runtime))
(def ^:private local-bin (:local-bin runtime))
(def ^:private user-dir (:user-dir runtime))

;; ── identity ──────────────────────────────────────────────────────────────
;; Derive a deterministic per-node Ed25519 seed from the shared operator seed +
;; node name, so node identities are stable + reproducible without storing a
;; secret per node. The operator seed itself comes from the env (never committed).

(defn- operator-seed [fleet]
  (config/current-operator-seed fleet))

(defn- did-for [node-seed-hex]
  ;; ask the local kotoba CLI to derive the did:key for this seed.
  (identity/did-from-command-result
   (apply p/sh (identity/did-derive-argv (:kotoba runtime) node-seed-hex))))

;; ── commands ────────────────────────────────────────────────────────────────

(defn cmd-nodes
  "List the fleet with Tailscale reachability + whether the mesh binary/agent is
  present. Read-only — the at-a-glance fleet map."
  [fleet _]
  (let [fleet (fleet/enrich fleet)]
    (println (report/nodes-header))
    (doseq [{:keys [action node]}
            (state/collect-node-plans (:nodes fleet) #(ssh/reachable? (:host %)))]
      (case action
        :down (println (report/nodes-row node false "-"))
        :probe (let [b (:out (ssh/sh (:host node) (provision/mesh-binary-status-command)))
                     up (:out (ssh/sh (:host node) (provision/launch-status-command)))]
                 (println (report/nodes-row node true (report/mesh-status b up))))))))

(def ^:private peers-file (config/peers-path user-dir))  ; node-name → libp2p PeerId (control-plane state)

(defn- node-port [fleet n] (fleet/node-port fleet n))
(defn- node-p2p-port [fleet n] (provision/node-p2p-port fleet n))

(defn- node-p2p-seed [fleet n] (identity/node-p2p-seed (operator-seed fleet) n))

(defn- provision-node
  "rsync the binaries + render & load the LaunchDaemon for one (enriched) node.
   `peers` is the name→PeerId map (for KOTOBA_BOOTSTRAP_PEERS; may be empty)."
  [fleet connect-spec tmpl peers n]
  (let [host (:host n)]
    (print (report/node-prefix n)) (flush)
    (if-not (ssh/reachable? host)
      (println report/unreachable-skipped-line)
      (let [op-seed (operator-seed fleet)
            user (:out (ssh/sh host "whoami"))
            home (:out (ssh/sh host "echo $HOME"))
            plist (provision/render-plist
                   tmpl fleet connect-spec peers n
                   {:user user
                    :home home
                    :operator-seed op-seed
                    :x25519-seed (identity/x25519-seed op-seed)
                    :did (did-for op-seed)
                    :p2p-seed (node-p2p-seed fleet n)})]
        (ssh/sh host (provision/remote-store-command))
        (print "rsync… ") (flush)
        (doseq [bin deploy/pinned-binaries]
          (apply p/sh (provision/rsync-binary-argv local-bin host bin)))
        (do
          (ssh/sh host (provision/write-plist-command plist))
          ;; RE-provision safety: on a node already running the service, an immediate
          ;; bootstrap after bootout races launchd and leaves the service UNLOADED
          ;; (caught by the asher canary). Settle after bootout, tolerate a re-bootstrap
          ;; of an already-loaded label, then kickstart to force the (re)start.
          (ssh/sh host (provision/reprovision-command)))
        ;; sibling watchdog: kotoba-server can wedge its HTTP surface without
        ;; exiting (KeepAlive can't heal that) — probe /health, kill on 2 strikes.
        (let [wd (provision/render-watchdog-plist
                  (slurp "deploy/com.murakumo.kotoba-mesh-watchdog.plist.tmpl")
                  fleet n {:user user :home home})]
          (ssh/sh host (provision/write-watchdog-plist-command wd))
          (ssh/sh host (provision/watchdog-reprovision-command)))
        (println (report/provision-result-line (seq (provision/bootstrap-str fleet peers n))))))))

(defn- load-peers [] (config/read-edn-file-or peers-file {}))

(defn- build-manifest []
  (config/read-edn-file-or (:build-manifest runtime) nil))

(defn- ensure-pinned!
  "BUILD.edn (tracked in git) pins the fleet's expected kotoba version; the binary
   itself is distributed out-of-band (built + `murakumo pin`). Refuse to roll out
   if a version is pinned but ./bin is empty — so the fleet always gets the exact
   version the repo declares. Returns the manifest (or nil if none)."
  []
  (let [bm (build-manifest)]
    (when (deploy/missing-pinned-binaries?
           bm
           (.exists (java.io.File. (config/kotoba-server-bin local-bin))))
      (binding [*out* *err*]
        (doseq [line (report/missing-pinned-binaries-lines bm)]
          (println line)))
      (System/exit 2))
    bm))

(defn cmd-provision
  "Push binaries + a resident LaunchDaemon to selected node(s). Idempotent.
   Uses any known PeerIds (.murakumo-peers.edn) for bootstrap. Requires
   MURAKUMO_OPERATOR_SEED."
  [fleet [sel]]
  (when-let [error (provision/provision-command-error (operator-seed fleet))]
    (binding [*out* *err*]
      (println (report/command-error-line :provision error)))
    (System/exit 2))
  (when-let [bm (ensure-pinned!)]
    (println (report/rollout-line bm)))
  (let [tmpl (slurp (config/launchd-template-path user-dir))
        connect-spec (connect/load-connect)
        fleet (fleet/enrich fleet)
        peers (load-peers)]
    (doseq [n (fleet/select fleet sel)] (provision-node fleet connect-spec tmpl peers n))))

(defn- node-peer-id
  "Read a node's stable libp2p PeerId from its mesh.log (net_actor logs it as
   `node_did=did:key:<peerid>` on lattice startup)."
  [host]
  (let [out (:out (ssh/sh host (provision/peer-id-log-command)))]
    (provision/peer-id-from-log out)))

(defn cmd-mesh
  "Form ONE gossipsub lattice across the fleet (2-pass): provision every node
   with a fixed P2P port + stable PeerId, collect the PeerIds, then re-provision
  with each node's KOTOBA_BOOTSTRAP_PEERS = all the others. After this, nodes
   dial each other over Tailscale and a component placed anywhere can run anywhere."
  [fleet [sel]]
  (when-let [error (provision/mesh-command-error (operator-seed fleet))]
    (binding [*out* *err*]
      (println (report/command-error-line :mesh error)))
    (System/exit 2))
  (ensure-pinned!)
  (let [tmpl (slurp (config/launchd-template-path user-dir))
        connect-spec (connect/load-connect)
        fleet (fleet/enrich fleet)
        nodes (fleet/select fleet sel)]
    (println report/mesh-pass1-line)
    (doseq [n nodes] (provision-node fleet connect-spec tmpl {} n))
    (println report/mesh-wait-peerid-line)
    (Thread/sleep provision/peer-advertise-wait-ms)
    (let [peers (provision/collected-peers-from-results
                 (provision/peer-probe-results
                  nodes
                  #(ssh/reachable? (:host %))
                  node-peer-id))]
      (config/write-edn-file peers-file peers)
      (println (report/collected-peers-line (count peers) peers-file))
      (println report/mesh-pass2-line)
      (doseq [n nodes] (provision-node fleet connect-spec tmpl peers n))
      (println report/mesh-forming-line))))

(defn cmd-up
  [fleet [sel]]
  (doseq [[n result] (provision/launch-results
                      (fleet/select fleet sel)
                      :up
                      ssh/sh)]
    (println (report/launch-result-line n result))))

(defn cmd-down
  [fleet [sel]]
  (doseq [[n result] (provision/launch-results
                      (fleet/select fleet sel)
                      :down
                      ssh/sh)]
    (println (report/launch-result-line n result))))

(defn- node-links
  "Distinct libp2p peers this node has connected to (from its mesh.log — the real
   lattice connectivity signal; the /health peer_count is the KDHT neighborhood,
   not the live link set)."
  [host]
  (let [out (:out (ssh/sh host (provision/live-link-count-command)))]
    (provision/live-link-count-output out)))

(defn cmd-status
  "Fold every node's /health into one view — the management read. LINKS is the
  number of distinct mesh peers the node has connected to (>0 once `mesh` ran)."
  [fleet [sel]]
  (println (report/status-header))
  (let [fleet (fleet/enrich fleet)]
    (doseq [{:keys [action node]}
            (state/collect-node-plans
             (fleet/select fleet sel)
             #(ssh/reachable? (:host %)))]
      (case action
        :down (println (report/status-down-row node))
        :probe (let [h (ssh/curl-local (:host node) (fleet/node-health-url fleet node))
                     j (state/parse-health #(json/parse-string % true) h)
                     row (state/status-row-input node j (node-links (:host node)) (node-p2p-port fleet node))]
                 (println (report/status-row* row)))))))

(defn- pinned-wit []
  (:wit runtime))

(defn- fwd [host port lport]
  (p/sh "bash" "-c" (tunnel/replace-forward-command lport port host))
  (Thread/sleep deploy/forward-settle-ms))

(defn- distribute-artifact
  "Block-put the WASM artifact to EVERY reachable node so the lattice can place
   the component anywhere and the winner always has the bytes (no IPFS/bitswap
   dependency). Operator-authed."
  [fleet token wasm nodes]
  (print "  distributing artifact →") (flush)
  (doseq [{:keys [node host remote-port local-port]}
          (deploy/reachable-artifact-distribution-plan fleet nodes ssh/reachable?)]
    (fwd host remote-port local-port)
    (let [r (apply p/sh (deploy/block-put-argv (:kotoba runtime) token wasm local-port))]
      (print (report/artifact-node-status node r)) (flush)))
  (p/sh "bash" "-c" (deploy/stop-forward-command deploy/artifact-forward-port))
  (println))

(defn- placed-on
  "Which nodes have executed the component CID (from their logs) — i.e. where the
   lattice actually placed + ran it."
  [fleet cid nodes]
  (deploy/observed-nodes
   (deploy/placement-probe-results
    cid nodes
    (fn [host command] (:out (ssh/sh host command))))))

(defn cmd-deploy
  "Deploy a kotoba app manifest to the FLEET: resolve its component CID, distribute
   the artifact to every node, publish its triggers/routes to one node's lattice,
   and report which node the lattice PLACED it on (honouring the manifest's
  :placement constraints over node labels). Usage: deploy <app.edn> [publish-node]."
  [fleet [manifest pubsel]]
  (when-let [error (deploy/deploy-command-error manifest (operator-seed fleet))]
    (binding [*out* *err*]
      (println (report/command-error-line :deploy error)))
    (System/exit 2))
  (let [fleet (fleet/enrich fleet)
        kotoba (:kotoba runtime)
        wit (pinned-wit)
        token (identity/op-token (did-for (operator-seed fleet)))
        input (deploy/deployment-input manifest (slurp manifest))
        dplan (deploy/deployment-plan (:manifest input) (:manifest-text input))
        src (:src dplan)
        wasm (:wasm dplan)
        ;; compile :src → wasm+CID, or use an explicit :cid (artifact must already be on nodes).
        build-output (when src
                       ;; `component build` writes the wasm AND prints its CID (last line).
                       (:out (apply p/sh (deploy/component-build-argv kotoba (:src-path dplan) wit wasm))))
        cid (deploy/deployment-cid dplan build-output)
        pub (first (fleet/select fleet (deploy/publish-selector pubsel)))]
    (println (report/deploy-start-line manifest cid))
    (when src (distribute-artifact fleet token wasm (:nodes fleet)))
    ;; publish PutApp+PutRoutes from one node → gossiped fleet-wide → lattice auctions + places.
    (fwd (:host pub) (node-port fleet pub) deploy/publish-forward-port)
    (let [{:keys [out err]} (apply p/sh (deploy/app-deploy-argv kotoba manifest wit deploy/publish-forward-port))]
      (println (report/deploy-command-output out err)))
    (p/sh "bash" "-c" (deploy/stop-forward-command deploy/publish-forward-port))
    ;; let the auction close + first trigger fire, then report placement.
    (println report/deploy-wait-placement-line)
    (Thread/sleep deploy/placement-wait-ms)
    (let [where (placed-on fleet cid (:nodes fleet))]
      (println (report/deploy-observed-row where pub)))))

(defn cmd-pin
  "Copy a consistent kotoba binary set into murakumo's own ./bin (gitignored) and
   record its provenance, so the fleet always gets the SAME cli+server the control
   plane drives — independent of the shared kotoba checkout. Usage: pin <src-dir>
  (defaults to the kotoba checkout's release target)."
  [_ [src]]
  (let [src (deploy/pin-source src kotoba-dir)
        dest (config/pinned-bin-dir user-dir)
        pin (deploy/pin-copy-plan src dest)]
    (.mkdirs (java.io.File. dest))
    (doseq [{:keys [src]} (deploy/missing-pin-binaries pin #(.exists (java.io.File. %)))]
      (binding [*out* *err*] (println (report/missing-binary-line src)))
      (System/exit 2))
    (doseq [argv (deploy/pin-binary-copy-argvs pin)]
      (apply p/sh argv))
    ;; pin the WIT alongside the binary (a release dir is target/<triple>/release,
    ;; so its WIT is ../../../crates/kotoba-runtime/wit) — deploy compiles against
    ;; the SAME WIT version the binary expects (avoids world-name skew).
    (doseq [argv (deploy/pin-wit-argvs pin (.exists (java.io.File. (get-in pin [:wit :src]))))]
      (apply p/sh argv))
    (let [sha (deploy/command-output (:out (apply p/sh (deploy/git-short-sha-argv src))))
          ver (deploy/command-output (:out (apply p/sh (deploy/version-argv dest))))]
      ;; webrtc: the fleet's browser-Live transport (connect.edn :native :live ⊇
      ;; :webrtc). Build kotoba with `--features p2p,realtime-wasm,webrtc` so the
      ;; KOTOBA_WEBRTC /webrtc-direct listen actually binds.
      (config/write-edn-file (:build-manifest runtime) (deploy/build-manifest src sha ver))
      (println (report/pin-success-line src sha ver)))))

(defn cmd-dash
  "Web dashboard + Datom-log snapshotter. Args: [port=8899] [interval-s=15]."
  [_ args]
  (require 'murakumo.dash)
  (apply (resolve 'murakumo.dash/-main) args))

(defn cmd-cloud
  "Plan murakumo.cloud overlay records. Args: [plan|records] [--cloud=cloud.edn] [--fleet=fleet.edn]."
  [_ args]
  (require 'murakumo.cloud)
  (apply (resolve 'murakumo.cloud/-main) args))

(defn cmd-reconcile
  "Declarative fleet reconcile (murakumo's wadm). Folds a desired-state manifest
   (murakumo.app.edn) against live placement and reports/converges the drift.
   Placement is imperative — deploys to each planner-chosen target node
   directly (`cmd-deploy`'s publish-selector); no cross-node lattice auction
   is wired (ADR-2606271600). See murakumo.reconcile."
  [fleet args]
  (let [deploy-fn (fn [a manifest-dir target]
                    (cmd-deploy fleet [(deploy/app-manifest-path manifest-dir a) target]))]
    (reconcile/cmd-reconcile fleet (cons "reconcile" args) deploy-fn)))

(defn cmd-fleet
  "Coordination-plane view: fold a kotoba-fleet Datom log into one snapshot
   (per-work holders, active leases, pending proposals). Args: [datom-log.edn] [now-ms].
   See murakumo.fleet-view / kotoba.fleet.view."
  [_ args]
  (require 'murakumo.fleet-view)
  (apply (resolve 'murakumo.fleet-view/-main) args))

(defn cmd-infer
  "Distributed inference across the fleet, exo-style (memory-weighted layer
   sharding, llama.cpp RPC / MLX ring engines). See murakumo.infer."
  [_ args]
  (require 'murakumo.infer)
  (apply (resolve 'murakumo.infer/-main) args))

(def ^:private commands
  {"nodes" cmd-nodes "provision" cmd-provision "up" cmd-up "down" cmd-down
   "status" cmd-status "deploy" cmd-deploy "mesh" cmd-mesh "pin" cmd-pin
   "dash" cmd-dash "reconcile" cmd-reconcile "fleet" cmd-fleet
   "cloud" cmd-cloud "infer" cmd-infer})

(defn -main [& args]
  (let [[cmd & rest] args
        fleet (fleet/load-fleet)]
    (if-let [f (get commands cmd)]
      (f fleet rest)
      (println (report/command-help)))))
