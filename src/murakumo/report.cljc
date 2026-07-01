;; murakumo.report — portable CLI report formatting.

(ns murakumo.report
  (:require [clojure.string :as str]))

(defn nodes-header []
  (format "%-10s %-16s %-8s %-9s %s" "NODE" "TAILSCALE-IP" "ONLINE" "SSH" "MESH"))

(defn nodes-row
  "Format one `murakumo nodes` row."
  [node ssh-ok mesh]
  (format "%-10s %-16s %-8s %-9s %s"
          (:name node)
          (or (:ip node) "?")
          (if (:online? node) "yes" "no")
          (if ssh-ok "ok" "no")
          mesh))

(defn mesh-status
  "Render installed/running probe outputs into a compact mesh status."
  [binary-status launch-status]
  (str binary-status "/" launch-status))

(defn status-header []
  (format "%-10s %-8s %-12s %-6s %s" "NODE" "HEALTH" "WASM-EXEC" "LINKS" "P2P-PORT"))

(defn status-down-row [node]
  (format "%-10s %-8s" (:name node) "down"))

(defn status-row
  "Format one `murakumo status` row."
  [node health-json links p2p-port]
  (let [subsystems (:subsystems health-json)]
    (format "%-10s %-8s %-12s %-6s %d"
            (:name node)
            (if health-json "ok" "no-resp")
            (or (:wasm_executor subsystems) "?")
            (if health-json links "-")
            p2p-port)))

(defn status-row* [{:keys [node health-json links p2p-port]}]
  (status-row node health-json links p2p-port))

(defn deploy-observed-row [where publish-node]
  (if (seq where)
    (format "  ✓ placed + running on: %s  (deployed from %s)"
            (str/join ", " where) (:name publish-node))
    "  ⚠ not yet observed running on any node (check `murakumo status` / node logs)"))

(defn node-prefix [node]
  (format "[%s] " (:name node)))

(def unreachable-skipped-line
  "unreachable — skipped")

(defn provision-result-line [peered?]
  (str "provisioned + loaded" (when peered? " (peered)")))

(defn launch-result-line
  "Format one launchctl up/down result row."
  [node result]
  (str (:name node) " " (:exit result)))

(defn missing-pinned-binaries-lines [build-manifest]
  [(format "fleet pins kotoba %s (sha %s) but ./bin has no binaries."
           (:version build-manifest)
           (:git-sha build-manifest))
   "Build that version and `murakumo pin <its release dir>` before provisioning."])

(defn rollout-line [build-manifest]
  (format "rolling out kotoba %s (sha %s, %s)"
          (:version build-manifest)
          (:git-sha build-manifest)
          (:features build-manifest)))

(defn collected-peers-line [count peers-file]
  (format "── collected %d PeerIds → %s ──" count peers-file))

(def mesh-pass1-line
  "── pass 1: provision with fixed P2P port + stable PeerId ──")

(def mesh-wait-peerid-line
  "── waiting for nodes to advertise their PeerId ──")

(def mesh-pass2-line
  "── pass 2: re-provision with KOTOBA_BOOTSTRAP_PEERS = the others ──")

(def mesh-forming-line
  "── lattice forming; check `murakumo status` (PEERS should climb) ──")

(defn artifact-node-status [node result]
  (format " %s%s" (:name node) (if (zero? (:exit result)) "✓" "✗")))

(defn deploy-start-line [manifest cid]
  (format "deploy %s  (component %s)" manifest cid))

(defn deploy-command-output [out err]
  (str (str/trim (str out)) (str err)))

(defn pin-success-line [src sha version]
  (format "pinned kotoba + kotoba-server → bin/  (src %s @ %s, %s)" src sha version))

(defn missing-binary-line [path]
  (str "missing binary: " path))

(def deploy-wait-placement-line
  "  waiting for the lattice to place + run it…")

(defn alert-line [alert]
  (format "[alert/%s] %s — %s" (:level alert) (:node alert) (:msg alert)))

(defn snapshot-error-line [message]
  (str "snapshot error: " message))

(defn reconcile-persist-error-line [message]
  (str "reconcile persist error: " message))

(defn dashboard-start-line [port interval]
  (format "murakumo dashboard → http://localhost:%d  (snapshot every %ds → Datom log)"
          port interval))

(defn apply-app-line [app]
  (format "  applying %s → publish (auction will place on eligible: %s)"
          (:app app)
          (str/join "," (:targets app))))

(defn watch-start-line [seconds]
  (format "── reconcile --watch (every %ds) ; Ctrl-C to stop ──" seconds))

(def operator-seed-required-line
  "set MURAKUMO_OPERATOR_SEED first")

(def operator-seed-hex-required-line
  "set MURAKUMO_OPERATOR_SEED (32-byte hex) first")

(def deploy-usage-line
  "usage: deploy <app.edn> [publish-node]")

(def reconcile-usage-line
  "usage: reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]]")

(defn command-error-line
  "Render a validation error keyword for a command."
  [command error]
  (case [command error]
    [:provision :missing-operator-seed-hex] operator-seed-hex-required-line
    [:mesh :missing-operator-seed] operator-seed-required-line
    [:deploy :missing-manifest] deploy-usage-line
    [:deploy :missing-operator-seed] operator-seed-required-line
    [:reconcile :missing-manifest] reconcile-usage-line
    (str "unknown " (name command) " error: " (name error))))

(def dashboard-no-persistence-line
  "(no MURAKUMO_OPERATOR_SEED → dashboard live-only, no Datom persistence)")

(def reconcile-no-persistence-line
  "(no MURAKUMO_OPERATOR_SEED → watch without Datom persistence)")

(def reconcile-converged-line
  "  ✓ converged")

(def reconcile-dry-run-line
  "\n(dry-run; re-run with --apply to converge, or --watch to keep it converged)")

(defn- fmt-cid [cid]
  (if cid (subs cid 0 (min 16 (count cid))) "—"))

(defn reconcile-lines
  "Render a reconcile plan as operator table lines."
  [plan]
  (let [header [(format "reconcile %s  @ %s" (or (:fleet plan) "fleet") (:ts plan))
                (format "  %-14s %-10s %-7s %-7s %-9s %s"
                        "APP" "CID" "DESIRED" "RUNNING" "ACTION" "DETAIL")]]
    (vec
     (concat
      header
      (mapcat
       (fn [app]
         (let [detail (case (:action app)
                        :place (str "→ " (str/join "," (:targets app)))
                        :satisfied (if (seq (:running app))
                                     (str "on " (str/join "," (:running app)))
                                     "")
                        (:reason app ""))
               base [(format "  %-14s %-10s %-7d %-7d %-9s %s"
                             (:app app)
                             (fmt-cid (:cid app))
                             (:desired app)
                             (count (:running app))
                             (name (:action app))
                             detail)]
               reach (when (seq (:reach app))
                       [(format "  %-14s %-10s reach: %s → eligible(by transport)=%s"
                                "" ""
                                (str/join "," (map str (:reach app)))
                                (str/join "," (:eligible app)))])
               misplaced (when (seq (:misplaced app))
                           [(format "  %-14s %-10s drift: running on non-eligible node(s): %s"
                                    "" "" (str/join "," (:misplaced app)))])]
           (concat base reach misplaced)))
       (:apps plan))))))

(defn command-help []
  (str/join
   "\n"
   ["murakumo — kotoba WASM mesh control plane"
    ""
    "commands:"
    "  nodes                       fleet reachability + mesh presence"
    "  pin       [src-dir]         copy a consistent kotoba cli+server into ./bin (own it)"
    "  provision [node|all]        rsync binaries + install resident LaunchDaemon"
    "  mesh      [node|all]        form ONE gossipsub lattice (2-pass: peer-id + bootstrap)"
    "  up/down   [node|all]        start/stop the resident mesh node"
    "  status    [node|all]        fold /health across the fleet (PEERS = live links)"
    "  deploy    <app.edn> [node]  compile clj→WASM + distribute + publish to the lattice"
    "  reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]]  declarative desired-state (wadm)"
    "  cloud     [plan|records|routes|dial|connect <node>|relay <name>|bootstrap]    plan murakumo.cloud identity overlay"
    "  dash      [port] [interval]  web dashboard + persist heartbeat/placement to the Datom log"
    ""
    "env: MURAKUMO_OPERATOR_SEED (32-byte hex), MURAKUMO_KOTOBA_DIR"]))
