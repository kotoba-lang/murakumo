;; murakumo.report — portable CLI report formatting.
;;
;; W6 product-shell (ADR-260728-w6-report-csv-fold-pure-oracle):
;; pure string helpers + CSV join seps + cid-display max + CSV fold steps
;; (join-append / csv-append / csv-spaced-append) DELEGATE to precompiled
;; kotoba report_core when oracle is loadable (JVM classpath or cljs/nbb).
;; Host remains: map/keyword projection, collection walks, and the
;; reconcile-lines mapcat structure over apps. cljs mirrors remain fallback
;; when oracle is not ready.

(ns murakumo.report
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :report-core)

(defn- o
  "Execute a report_core export on the precompiled KIR oracle."
  [export args]
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

(def ^:private mirror-report-csv-sep ",")
(def ^:private mirror-report-csv-spaced-sep ", ")
(def ^:private mirror-mesh-status-sep "/")
(def ^:private mirror-cid-display-max-len 16)

(def report-csv-sep
  "CSV join for reconcile targets/running/reach. Kotoba when ready."
  (oracle-str-const 'report-csv-sep mirror-report-csv-sep))

(def report-csv-spaced-sep
  "Spaced CSV join for deploy-observed where list. Kotoba when ready."
  (oracle-str-const 'report-csv-spaced-sep mirror-report-csv-spaced-sep))

(def mesh-status-sep
  "Separator between binary/launch status. Kotoba when ready."
  (oracle-str-const 'mesh-status-sep mirror-mesh-status-sep))

(def cid-display-max-len
  "Max chars for reconcile CID display truncate. Kotoba when ready."
  (oracle-i64-const 'cid-display-max-len mirror-cid-display-max-len))

(defn- mirror-join-append [acc sep next]
  (if (str/blank? (str acc))
    (str next)
    (str acc sep next)))

(defn- mirror-csv-append [acc next]
  (mirror-join-append acc mirror-report-csv-sep next))

(defn- mirror-csv-spaced-append [acc next]
  (mirror-join-append acc mirror-report-csv-spaced-sep next))

(defn join-append
  "CSV-style fold step: empty acc ⇒ next only. Kotoba when ready."
  [acc sep next]
  (try-oracle
   #(o 'join-append [(str (or acc "")) (str sep) (str next)])
   #(mirror-join-append acc sep next)))

(defn csv-append
  "Append one CSV cell (comma sep). Kotoba when ready."
  [acc next]
  (try-oracle
   #(o 'csv-append [(str (or acc "")) (str next)])
   #(mirror-csv-append acc next)))

(defn csv-spaced-append
  "Append one CSV cell (comma+space sep). Kotoba when ready."
  [acc next]
  (try-oracle
   #(o 'csv-spaced-append [(str (or acc "")) (str next)])
   #(mirror-csv-spaced-append acc next)))

(defn csv-join
  "Join items with report-csv-sep via dual-sourced csv-append fold."
  [items]
  (reduce (fn [acc x] (csv-append acc (str x)))
          ""
          (or items [])))

(defn csv-spaced-join
  "Join items with report-csv-spaced-sep via dual-sourced csv-spaced-append."
  [items]
  (reduce (fn [acc x] (csv-spaced-append acc (str x)))
          ""
          (or items [])))

;; ── portable pad (cljs-safe mirrors of format %-Ns) ──────────────────

(defn- pad-right-n [s n]
  (str s (apply str (repeat (max 0 n) " "))))

(defn- pad-to [s width]
  (let [s (str s)]
    (pad-right-n s (- width (count s)))))

;; ── host-mirror pure helpers ─────────────────────────────────────────

(defn- mirror-nodes-header []
  "NODE       TAILSCALE-IP     ONLINE   SSH       MESH")

(defn- mirror-status-header []
  "NODE       HEALTH   WASM-EXEC    LINKS  P2P-PORT")

(defn- mirror-nodes-row [node ssh-ok mesh]
  (str (pad-to (:name node) 10) " "
       (pad-to (or (:ip node) "?") 16) " "
       (pad-to (if (:online? node) "yes" "no") 8) " "
       (pad-to (if ssh-ok "ok" "no") 9) " "
       mesh))

(defn- mirror-status-down-row [node]
  (str (pad-to (:name node) 10) " " "down    "))

(defn- mirror-status-row [node health-json links p2p-port]
  (let [subsystems (:subsystems health-json)]
    (str (pad-to (:name node) 10) " "
         (pad-to (if health-json "ok" "no-resp") 8) " "
         (pad-to (or (:wasm_executor subsystems) "?") 12) " "
         (pad-to (if health-json (str links) "-") 6) " "
         (str p2p-port))))

(defn- mirror-mesh-status [binary-status launch-status]
  (str binary-status mirror-mesh-status-sep launch-status))

(defn- mirror-deploy-observed-row [where publish-node]
  (if (seq where)
    (str "  ✓ placed + running on: "
         (reduce (fn [acc x] (mirror-csv-spaced-append acc (str x))) "" where)
         "  (deployed from " (:name publish-node) ")")
    "  ⚠ not yet observed running on any node (check `murakumo status` / node logs)"))

(defn- mirror-node-prefix [node]
  (str "[" (:name node) "] "))

(def ^:private mirror-unreachable-skipped-line "unreachable — skipped")

(defn- mirror-provision-result-line [peered?]
  (str "provisioned + loaded" (when peered? " (peered)")))

(defn- mirror-launch-result-line [node result]
  (str (:name node) " " (:exit result)))

(defn- mirror-missing-pinned-binaries-lines [build-manifest]
  [(str "fleet pins kotoba " (:version build-manifest)
        " (sha " (:git-sha build-manifest) ") but ./bin has no binaries.")
   "Build that version and `murakumo pin <its release dir>` before provisioning."])

(defn- mirror-rollout-line [build-manifest]
  (str "rolling out kotoba " (:version build-manifest)
       " (sha " (:git-sha build-manifest) ", " (:features build-manifest) ")"))

(defn- mirror-collected-peers-line [count peers-file]
  (str "── collected " count " PeerIds → " peers-file " ──"))

(def ^:private mirror-mesh-pass1-line
  "── pass 1: provision with fixed P2P port + stable PeerId ──")
(def ^:private mirror-mesh-wait-peerid-line
  "── waiting for nodes to advertise their PeerId ──")
(def ^:private mirror-mesh-pass2-line
  "── pass 2: re-provision with KOTOBA_BOOTSTRAP_PEERS = the others ──")
(def ^:private mirror-mesh-forming-line
  "── lattice forming; check `murakumo status` (PEERS should climb) ──")

(defn- mirror-artifact-node-status [node result]
  (str " " (:name node) (if (zero? (:exit result)) "✓" "✗")))

(defn- mirror-deploy-start-line [manifest cid]
  (str "deploy " manifest "  (component " cid ")"))

(defn- mirror-deploy-command-output [out err]
  (str (str/trim (str out)) (str err)))

(defn- mirror-pin-success-line [src sha version]
  (str "pinned kotoba + kotoba-server → bin/  (src " src " @ " sha ", " version ")"))

(defn- mirror-missing-binary-line [path]
  (str "missing binary: " path))

(def ^:private mirror-deploy-wait-placement-line
  "  waiting for the lattice to place + run it…")

(defn- mirror-alert-line [alert]
  (str "[alert/" (:level alert) "] " (:node alert) " — " (:msg alert)))

(defn- mirror-snapshot-error-line [message]
  (str "snapshot error: " message))

(defn- mirror-reconcile-persist-error-line [message]
  (str "reconcile persist error: " message))

(defn- mirror-dashboard-start-line [port interval]
  (str "murakumo dashboard → http://localhost:" port
       "  (snapshot every " interval "s → Datom log)"))

(defn- mirror-apply-target-line [app target]
  (str "  applying " (:app app)
       " → deploy to " target
       " (no cross-node auction; murakumo picks the target directly)"))

(defn- mirror-watch-start-line [seconds]
  (str "── reconcile --watch (every " seconds "s) ; Ctrl-C to stop ──"))

(def ^:private mirror-operator-seed-required-line
  "set MURAKUMO_OPERATOR_SEED first")
(def ^:private mirror-operator-seed-hex-required-line
  "set MURAKUMO_OPERATOR_SEED (32-byte hex) first")
(def ^:private mirror-deploy-usage-line
  "usage: deploy <app.edn> [publish-node]")
(def ^:private mirror-reconcile-usage-line
  "usage: reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]]")
(def ^:private mirror-dashboard-no-persistence-line
  "(no MURAKUMO_OPERATOR_SEED → dashboard live-only, no Datom persistence)")
(def ^:private mirror-reconcile-no-persistence-line
  "(no MURAKUMO_OPERATOR_SEED → watch without Datom persistence)")
(def ^:private mirror-reconcile-converged-line
  "  ✓ converged")
(def ^:private mirror-reconcile-dry-run-line
  "\n(dry-run; re-run with --apply to converge, or --watch to keep it converged)")

(defn- mirror-command-error-line [command error]
  (case [command error]
    [:provision :missing-operator-seed-hex] mirror-operator-seed-hex-required-line
    [:mesh :missing-operator-seed] mirror-operator-seed-required-line
    [:deploy :missing-manifest] mirror-deploy-usage-line
    [:deploy :missing-operator-seed] mirror-operator-seed-required-line
    [:reconcile :missing-manifest] mirror-reconcile-usage-line
    (str "unknown " (name command) " error: " (name error))))

(defn- mirror-fmt-cid [cid]
  (if cid (subs cid 0 (min mirror-cid-display-max-len (count cid))) "—"))

(defn- mirror-reconcile-lines [plan]
  (let [header [(str "reconcile " (or (:fleet plan) "fleet") "  @ " (:ts plan))
                (str "  " (pad-to "APP" 14) " " (pad-to "CID" 10) " "
                     (pad-to "DESIRED" 7) " " (pad-to "RUNNING" 7) " "
                     (pad-to "ACTION" 9) " " "DETAIL")]]
    (vec
     (concat
      header
      (mapcat
       (fn [app]
         (let [detail (case (:action app)
                        :place (str "→ " (reduce (fn [acc x] (mirror-csv-append acc (str x)))
                                                 "" (:targets app)))
                        :satisfied (if (seq (:running app))
                                     (str "on " (reduce (fn [acc x] (mirror-csv-append acc (str x)))
                                                        "" (:running app)))
                                     "")
                        (str (or (:reason app) "")))
               base [(str "  " (pad-to (:app app) 14) " "
                          (pad-to (mirror-fmt-cid (:cid app)) 10) " "
                          (pad-to (str (:desired app)) 7) " "
                          (pad-to (str (count (:running app))) 7) " "
                          (pad-to (name (:action app)) 9) " "
                          detail)]
               reach (when (seq (:reach app))
                       [(str "  " (pad-to "" 14) " " (pad-to "" 10)
                             " reach: " (reduce (fn [acc x] (mirror-csv-append acc (str x)))
                                                "" (map str (:reach app)))
                             " → eligible(by transport)="
                             (reduce (fn [acc x] (mirror-csv-append acc (str x)))
                                     "" (:eligible app)))])
               misplaced (when (seq (:misplaced app))
                           [(str "  " (pad-to "" 14) " " (pad-to "" 10)
                                 " drift: running on non-eligible node(s): "
                                 (reduce (fn [acc x] (mirror-csv-append acc (str x)))
                                         "" (:misplaced app)))])]
           (concat base reach misplaced)))
       (:apps plan))))))

(def ^:private mirror-command-help
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
    "  fleet     <datom-log.edn>    fold a kotoba-fleet Datom log into one coordination view"
    "  infer     probe|plan <model>|provision|up|down|ps|serve|generate  distributed inference (exo-style shard plan)"
    "  model     plan|setup|status <model> [node|all] [cache-dir]  Hugging Face model provisioning"
    "  revive    [node|all]        wake offline fleet Macs via a live LAN peer"
    ""
    "env: MURAKUMO_OPERATOR_SEED (32-byte hex), MURAKUMO_KOTOBA_DIR"]))

;; ── dual-source public API ───────────────────────────────────────────

(defn nodes-header []
  (try-oracle #(o 'nodes-header []) mirror-nodes-header))

(defn nodes-row
  "Format one `murakumo nodes` row (oracle nodes-row + host projection)."
  [node ssh-ok mesh]
  (try-oracle
   #(o 'nodes-row
       [(str (:name node))
        (str (or (:ip node) "?"))
        (oracle/as-i64 (if (:online? node) 1 0))
        (oracle/as-i64 (if ssh-ok 1 0))
        (str mesh)])
   #(mirror-nodes-row node ssh-ok mesh)))

(defn mesh-status
  "Render installed/running probe outputs into a compact mesh status."
  [binary-status launch-status]
  (try-oracle
   #(o 'mesh-status [(str binary-status) (str launch-status)])
   #(mirror-mesh-status binary-status launch-status)))

(defn status-header []
  (try-oracle #(o 'status-header []) mirror-status-header))

(defn status-down-row [node]
  (try-oracle
   #(o 'status-down-row [(str (:name node))])
   #(mirror-status-down-row node)))

(defn status-row
  "Format one `murakumo status` row.
   Health presence via Product Value ABI optional i64 when oracle ready."
  [node health-json links p2p-port]
  (let [subsystems (:subsystems health-json)
        health? (when health-json 1)
        wasm (or (:wasm_executor subsystems) "?")
        links-str (if health-json (str links) "-")]
    (try-oracle
     #(o 'status-row
         [(str (:name node))
          (oracle/option-i64 health?)
          (str wasm)
          (str links-str)
          (oracle/as-i64 p2p-port)])
     #(mirror-status-row node health-json links p2p-port))))

(defn status-row* [{:keys [node health-json links p2p-port]}]
  (status-row node health-json links p2p-port))

(defn deploy-observed-row [where publish-node]
  (try-oracle
   #(if (seq where)
      (o 'deploy-observed-placed-line
         [(csv-spaced-join where) (str (:name publish-node))])
      (o 'deploy-observed-empty-line []))
   #(mirror-deploy-observed-row where publish-node)))

(defn node-prefix [node]
  (try-oracle
   #(o 'node-prefix [(str (:name node))])
   #(mirror-node-prefix node)))

(def unreachable-skipped-line
  (oracle-str-const 'unreachable-skipped-line mirror-unreachable-skipped-line))

(defn provision-result-line [peered?]
  (try-oracle
   #(o 'provision-result-line [(oracle/as-i64 (if peered? 1 0))])
   #(mirror-provision-result-line peered?)))

(defn launch-result-line
  "Format one launchctl up/down result row."
  [node result]
  (try-oracle
   #(o 'launch-result-line [(str (:name node)) (str (:exit result))])
   #(mirror-launch-result-line node result)))

(defn missing-pinned-binaries-lines [build-manifest]
  (try-oracle
   #(vector
     (o 'missing-pinned-binaries-line1
        [(str (:version build-manifest)) (str (:git-sha build-manifest))])
     (o 'missing-pinned-binaries-line2 []))
   #(mirror-missing-pinned-binaries-lines build-manifest)))

(defn rollout-line [build-manifest]
  (try-oracle
   #(o 'rollout-line
       [(str (:version build-manifest))
        (str (:git-sha build-manifest))
        (str (:features build-manifest))])
   #(mirror-rollout-line build-manifest)))

(defn collected-peers-line [count peers-file]
  (try-oracle
   #(o 'collected-peers-line [(oracle/as-i64 count) (str peers-file)])
   #(mirror-collected-peers-line count peers-file)))

(def mesh-pass1-line
  (oracle-str-const 'mesh-pass1-line mirror-mesh-pass1-line))

(def mesh-wait-peerid-line
  (oracle-str-const 'mesh-wait-peerid-line mirror-mesh-wait-peerid-line))

(def mesh-pass2-line
  (oracle-str-const 'mesh-pass2-line mirror-mesh-pass2-line))

(def mesh-forming-line
  (oracle-str-const 'mesh-forming-line mirror-mesh-forming-line))

(defn artifact-node-status [node result]
  (try-oracle
   #(o 'artifact-node-status
       [(str (:name node)) (oracle/as-i64 (if (zero? (:exit result)) 1 0))])
   #(mirror-artifact-node-status node result)))

(defn deploy-start-line [manifest cid]
  (try-oracle
   #(o 'deploy-start-line [(str manifest) (str cid)])
   #(mirror-deploy-start-line manifest cid)))

(defn deploy-command-output [out err]
  (try-oracle
   #(o 'deploy-command-output [(str out) (str err)])
   #(mirror-deploy-command-output out err)))

(defn pin-success-line [src sha version]
  (try-oracle
   #(o 'pin-success-line [(str src) (str sha) (str version)])
   #(mirror-pin-success-line src sha version)))

(defn missing-binary-line [path]
  (try-oracle
   #(o 'missing-binary-line [(str path)])
   #(mirror-missing-binary-line path)))

(def deploy-wait-placement-line
  (oracle-str-const 'deploy-wait-placement-line mirror-deploy-wait-placement-line))

(defn alert-line [alert]
  (try-oracle
   #(o 'alert-line
       [(str (:level alert)) (str (:node alert)) (str (:msg alert))])
   #(mirror-alert-line alert)))

(defn snapshot-error-line [message]
  (try-oracle
   #(o 'snapshot-error-line [(str message)])
   #(mirror-snapshot-error-line message)))

(defn reconcile-persist-error-line [message]
  (try-oracle
   #(o 'reconcile-persist-error-line [(str message)])
   #(mirror-reconcile-persist-error-line message)))

(defn dashboard-start-line [port interval]
  (try-oracle
   #(o 'dashboard-start-line [(oracle/as-i64 port) (oracle/as-i64 interval)])
   #(mirror-dashboard-start-line port interval)))

(defn apply-target-line [app target]
  (try-oracle
   #(o 'apply-target-line [(str (:app app)) (str target)])
   #(mirror-apply-target-line app target)))

(defn watch-start-line [seconds]
  (try-oracle
   #(o 'watch-start-line [(oracle/as-i64 seconds)])
   #(mirror-watch-start-line seconds)))

(def operator-seed-required-line
  (oracle-str-const 'operator-seed-required-line mirror-operator-seed-required-line))

(def operator-seed-hex-required-line
  (oracle-str-const 'operator-seed-hex-required-line mirror-operator-seed-hex-required-line))

(def deploy-usage-line
  (oracle-str-const 'deploy-usage-line mirror-deploy-usage-line))

(def reconcile-usage-line
  (oracle-str-const 'reconcile-usage-line mirror-reconcile-usage-line))

(defn command-error-line
  "Render a validation error keyword for a command."
  [command error]
  (try-oracle
   #(o 'command-error-line [(name command) (name error)])
   #(mirror-command-error-line command error)))

(def dashboard-no-persistence-line
  (oracle-str-const 'dashboard-no-persistence-line mirror-dashboard-no-persistence-line))

(def reconcile-no-persistence-line
  (oracle-str-const 'reconcile-no-persistence-line mirror-reconcile-no-persistence-line))

(def reconcile-converged-line
  (oracle-str-const 'reconcile-converged-line mirror-reconcile-converged-line))

(def reconcile-dry-run-line
  (oracle-str-const 'reconcile-dry-run-line mirror-reconcile-dry-run-line))

;; ── reconcile pure builders + host mapcat ────────────────────────────

(defn- pad-field
  "Left-align field via oracle pad-right; host supplies remaining pad from
  Clojure string count (not UTF-8 byte-length) so multi-byte glyphs like
  em-dash CID placeholder keep layout parity."
  [s width]
  (let [s (str s)
        pad (max 0 (- width (count s)))]
    (try-oracle
     #(o 'pad-right [s (oracle/as-i64 pad)])
     #(pad-right-n s pad))))

(defn- fmt-cid [cid]
  (try-oracle
   #(if cid
      (o 'cid-display [(subs cid 0 (min cid-display-max-len (count cid)))
                       (oracle/as-i64 1)])
      (o 'cid-display ["" (oracle/as-i64 0)]))
   #(mirror-fmt-cid cid)))

(defn reconcile-lines
  "Render a reconcile plan as operator table lines.
   Pure title/col/row/detail/reach/drift from oracle when ready; host mapcats
   apps and CSV-joins targets/running/reach/misplaced via dual-sourced
   csv-append fold steps."
  [plan]
  (try-oracle
   #(let [title (o 'reconcile-title
                   [(str (or (:fleet plan) "fleet")) (str (:ts plan))])
          col (o 'reconcile-col-header [])]
      (vec
       (concat
        [title col]
        (mapcat
         (fn [app]
           (let [action (name (:action app))
                 targets-csv (csv-join (:targets app))
                 running-csv (csv-join (:running app))
                 running-empty (if (seq (:running app)) 0 1)
                 reason (str (or (:reason app) ""))
                 detail (o 'action-detail
                           [action targets-csv running-csv
                            (oracle/as-i64 running-empty) reason])
                 app14 (pad-field (:app app) 14)
                 cid10 (pad-field (fmt-cid (:cid app)) 10)
                 act9 (pad-field action 9)
                 front (o 'reconcile-app-row
                          [app14 cid10
                           (oracle/as-i64 (:desired app))
                           (oracle/as-i64 (count (:running app)))
                           act9])
                 base [(o 'reconcile-app-line [front detail])]
                 reach (when (seq (:reach app))
                         [(o 'reach-line
                             [(csv-join (map str (:reach app)))
                              (csv-join (:eligible app))])])
                 misplaced (when (seq (:misplaced app))
                             [(o 'drift-line
                                 [(csv-join (:misplaced app))])])]
             (concat base reach misplaced)))
         (:apps plan)))))
   #(mirror-reconcile-lines plan)))

(defn command-help []
  (try-oracle #(o 'command-help []) (fn [] mirror-command-help)))
