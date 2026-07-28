;; murakumo.report — CLI report formatting.
;;
;; W6 product-shell authority (ADR-260728-w6-report-oracle-authority):
;; Pure string helpers DELEGATE to precompiled kotoba/report_core.kotoba KIR
;; (resources/murakumo/oracle/report_core.kir.edn) via murakumo.kotoba.oracle.
;; Kotoba is SSoT. reconcile-lines mapcats apps on the host; row/title pure
;; pieces come from the oracle.

(ns murakumo.report
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(defn- o
  "Call report product-shell oracle export."
  [export & args]
  (oracle/call :report export (vec args)))

;; ── table headers / rows ─────────────────────────────────────────────

(defn nodes-header []
  (o 'nodes-header))

(defn nodes-row
  "Format one `murakumo nodes` row."
  [node ssh-ok mesh]
  (o 'nodes-row
     (str (:name node))
     (str (or (:ip node) "?"))
     (long (if (:online? node) 1 0))
     (long (if ssh-ok 1 0))
     (str mesh)))

(defn mesh-status
  "Render installed/running probe outputs into a compact mesh status."
  [binary-status launch-status]
  (o 'mesh-status (str binary-status) (str launch-status)))

(defn status-header []
  (o 'status-header))

(defn status-down-row [node]
  (o 'status-down-row (str (:name node))))

(defn status-row
  "Format one `murakumo status` row."
  [node health-json links p2p-port]
  (let [subsystems (:subsystems health-json)
        has-health (if health-json 1 0)
        wasm (str (or (:wasm_executor subsystems) "?"))
        links-str (str (if health-json links "-"))]
    (o 'status-row
       (str (:name node))
       (long has-health)
       wasm
       links-str
       (long p2p-port))))

(defn status-row* [{:keys [node health-json links p2p-port]}]
  (status-row node health-json links p2p-port))

(defn deploy-observed-row [where publish-node]
  (if (seq where)
    (o 'deploy-observed-placed-line
       (str/join ", " where)
       (str (:name publish-node)))
    (o 'deploy-observed-empty-line)))

(defn node-prefix [node]
  (o 'node-prefix (str (:name node))))

;; Constants: evaluated once at ns load via oracle (string values for callers).
(def unreachable-skipped-line (o 'unreachable-skipped-line))

(defn provision-result-line [peered?]
  (o 'provision-result-line (long (if peered? 1 0))))

(defn launch-result-line
  "Format one launchctl up/down result row."
  [node result]
  (o 'launch-result-line (str (:name node)) (str (:exit result))))

(defn missing-pinned-binaries-lines [build-manifest]
  [(o 'missing-pinned-binaries-line1
      (str (:version build-manifest))
      (str (:git-sha build-manifest)))
   (o 'missing-pinned-binaries-line2)])

(defn rollout-line [build-manifest]
  (o 'rollout-line
     (str (:version build-manifest))
     (str (:git-sha build-manifest))
     (str (:features build-manifest))))

(defn collected-peers-line [count peers-file]
  (o 'collected-peers-line (long count) (str peers-file)))

(def mesh-pass1-line (o 'mesh-pass1-line))
(def mesh-wait-peerid-line (o 'mesh-wait-peerid-line))
(def mesh-pass2-line (o 'mesh-pass2-line))
(def mesh-forming-line (o 'mesh-forming-line))

(defn artifact-node-status [node result]
  (o 'artifact-node-status
     (str (:name node))
     (long (if (zero? (:exit result)) 1 0))))

(defn deploy-start-line [manifest cid]
  (o 'deploy-start-line (str manifest) (str cid)))

(defn deploy-command-output [out err]
  (o 'deploy-command-output (str (or out "")) (str (or err ""))))

(defn pin-success-line [src sha version]
  (o 'pin-success-line (str src) (str sha) (str version)))

(defn missing-binary-line [path]
  (o 'missing-binary-line (str path)))

(def deploy-wait-placement-line (o 'deploy-wait-placement-line))

(defn alert-line [alert]
  (o 'alert-line
     (str (:level alert))
     (str (:node alert))
     (str (:msg alert))))

(defn snapshot-error-line [message]
  (o 'snapshot-error-line (str message)))

(defn reconcile-persist-error-line [message]
  (o 'reconcile-persist-error-line (str message)))

(defn dashboard-start-line [port interval]
  (o 'dashboard-start-line (long port) (long interval)))

(defn apply-target-line [app target]
  (o 'apply-target-line (str (:app app)) (str target)))

(defn watch-start-line [seconds]
  (o 'watch-start-line (long seconds)))

(def operator-seed-required-line (o 'operator-seed-required-line))
(def operator-seed-hex-required-line (o 'operator-seed-hex-required-line))
(def deploy-usage-line (o 'deploy-usage-line))
(def reconcile-usage-line (o 'reconcile-usage-line))

(defn command-error-line
  "Render a validation error keyword for a command."
  [command error]
  (o 'command-error-line (name command) (name error)))

(def dashboard-no-persistence-line (o 'dashboard-no-persistence-line))
(def reconcile-no-persistence-line (o 'reconcile-no-persistence-line))
(def reconcile-converged-line (o 'reconcile-converged-line))
(def reconcile-dry-run-line (o 'reconcile-dry-run-line))

(defn- fmt-cid [cid]
  (if cid (subs cid 0 (min 16 (count cid))) "—"))

(defn- pad-field
  "Host char-width pad (Unicode-safe). kotoba pad-to is ASCII-byte oriented."
  [s w]
  (format (str "%-" w "s") (str s)))

(defn reconcile-lines
  "Render a reconcile plan as operator table lines.
   Title/col/app-row pure pieces from oracle; mapcat of apps + char pad on host."
  [plan]
  (let [header [(o 'reconcile-title
                   (str (or (:fleet plan) "fleet"))
                   (str (:ts plan)))
                (o 'reconcile-col-header)]]
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
                        (str (:reason app "")))
               front (o 'reconcile-app-row
                        (pad-field (:app app) 14)
                        (pad-field (fmt-cid (:cid app)) 10)
                        (long (:desired app))
                        (long (count (:running app)))
                        (pad-field (name (:action app)) 9))
               base [(o 'reconcile-app-line front detail)]
               reach (when (seq (:reach app))
                       [(o 'reach-line
                           (str/join "," (map str (:reach app)))
                           (str/join "," (:eligible app)))])
               misplaced (when (seq (:misplaced app))
                           [(o 'drift-line
                               (str/join "," (:misplaced app)))])]
           (concat base reach misplaced)))
       (:apps plan))))))

(defn command-help []
  (o 'command-help))
