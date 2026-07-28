;; murakumo.report — portable CLI report formatting.
;;
;; W6 product-shell authority (ADR-260728-w6-report-oracle-authority):
;; On the JVM, pure string helpers DELEGATE to the precompiled kotoba oracle
;; (kotoba/report_core.kotoba → resources/murakumo/oracle/report_core.kir.edn).
;; Kotoba is SSoT. Host remains: map/keyword projection, CSV joins, and the
;; reconcile-lines mapcat structure over apps.

(ns murakumo.report
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :report-core)

(defn- ocall
  "Execute a report_core export on the precompiled KIR oracle."
  [export args]
  (oracle/call oid export args))

;; ── headers / pad / table rows ───────────────────────────────────────

(defn nodes-header []
  (ocall 'nodes-header []))

(defn nodes-row
  "Format one `murakumo nodes` row (oracle nodes-row + host projection)."
  [node ssh-ok mesh]
  (ocall 'nodes-row
         [(str (:name node))
          (str (or (:ip node) "?"))
          (long (if (:online? node) 1 0))
          (long (if ssh-ok 1 0))
          (str mesh)]))

(defn mesh-status
  "Render installed/running probe outputs into a compact mesh status."
  [binary-status launch-status]
  (ocall 'mesh-status [(str binary-status) (str launch-status)]))

(defn status-header []
  (ocall 'status-header []))

(defn status-down-row [node]
  (ocall 'status-down-row [(str (:name node))]))

(defn status-row
  "Format one `murakumo status` row.
   JVM: health presence via Product Value ABI optional i64."
  [node health-json links p2p-port]
  (let [subsystems (:subsystems health-json)
        health? (when health-json 1)
        wasm (or (:wasm_executor subsystems) "?")
        links-str (if health-json (str links) "-")]
    (ocall 'status-row
           [(str (:name node))
            (oracle/option-i64 health?)
            (str wasm)
            (str links-str)
            (long p2p-port)])))

(defn status-row* [{:keys [node health-json links p2p-port]}]
  (status-row node health-json links p2p-port))

(defn deploy-observed-row [where publish-node]
  (if (seq where)
    (ocall 'deploy-observed-placed-line
           [(str/join ", " where) (str (:name publish-node))])
    (ocall 'deploy-observed-empty-line [])))

(defn node-prefix [node]
  (ocall 'node-prefix [(str (:name node))]))

(def unreachable-skipped-line
  (ocall 'unreachable-skipped-line []))

(defn provision-result-line [peered?]
  (ocall 'provision-result-line [(long (if peered? 1 0))]))

(defn launch-result-line
  "Format one launchctl up/down result row."
  [node result]
  (ocall 'launch-result-line [(str (:name node)) (str (:exit result))]))

(defn missing-pinned-binaries-lines [build-manifest]
  [(ocall 'missing-pinned-binaries-line1
          [(str (:version build-manifest)) (str (:git-sha build-manifest))])
   (ocall 'missing-pinned-binaries-line2 [])])

(defn rollout-line [build-manifest]
  (ocall 'rollout-line
         [(str (:version build-manifest))
          (str (:git-sha build-manifest))
          (str (:features build-manifest))]))

(defn collected-peers-line [count peers-file]
  (ocall 'collected-peers-line [(long count) (str peers-file)]))

(def mesh-pass1-line
  (ocall 'mesh-pass1-line []))

(def mesh-wait-peerid-line
  (ocall 'mesh-wait-peerid-line []))

(def mesh-pass2-line
  (ocall 'mesh-pass2-line []))

(def mesh-forming-line
  (ocall 'mesh-forming-line []))

(defn artifact-node-status [node result]
  (ocall 'artifact-node-status
         [(str (:name node)) (long (if (zero? (:exit result)) 1 0))]))

(defn deploy-start-line [manifest cid]
  (ocall 'deploy-start-line [(str manifest) (str cid)]))

(defn deploy-command-output [out err]
  (ocall 'deploy-command-output [(str out) (str err)]))

(defn pin-success-line [src sha version]
  (ocall 'pin-success-line [(str src) (str sha) (str version)]))

(defn missing-binary-line [path]
  (ocall 'missing-binary-line [(str path)]))

(def deploy-wait-placement-line
  (ocall 'deploy-wait-placement-line []))

(defn alert-line [alert]
  (ocall 'alert-line
         [(str (:level alert)) (str (:node alert)) (str (:msg alert))]))

(defn snapshot-error-line [message]
  (ocall 'snapshot-error-line [(str message)]))

(defn reconcile-persist-error-line [message]
  (ocall 'reconcile-persist-error-line [(str message)]))

(defn dashboard-start-line [port interval]
  (ocall 'dashboard-start-line [(long port) (long interval)]))

(defn apply-target-line [app target]
  (ocall 'apply-target-line [(str (:app app)) (str target)]))

(defn watch-start-line [seconds]
  (ocall 'watch-start-line [(long seconds)]))

(def operator-seed-required-line
  (ocall 'operator-seed-required-line []))

(def operator-seed-hex-required-line
  (ocall 'operator-seed-hex-required-line []))

(def deploy-usage-line
  (ocall 'deploy-usage-line []))

(def reconcile-usage-line
  (ocall 'reconcile-usage-line []))

(defn command-error-line
  "Render a validation error keyword for a command."
  [command error]
  (ocall 'command-error-line [(name command) (name error)]))

(def dashboard-no-persistence-line
  (ocall 'dashboard-no-persistence-line []))

(def reconcile-no-persistence-line
  (ocall 'reconcile-no-persistence-line []))

(def reconcile-converged-line
  (ocall 'reconcile-converged-line []))

(def reconcile-dry-run-line
  (ocall 'reconcile-dry-run-line []))

;; ── reconcile pure builders + host mapcat ────────────────────────────

(defn- pad-field
  "Left-align field via oracle pad-right; host supplies remaining pad from
  Clojure string count (not UTF-8 byte-length) so multi-byte glyphs like
  em-dash CID placeholder keep %-Ns layout parity."
  [s width]
  (let [s (str s)]
    (ocall 'pad-right [s (long (max 0 (- width (count s))))])))

(defn- fmt-cid [cid]
  (if cid
    (ocall 'cid-display [(subs cid 0 (min 16 (count cid))) 1])
    (ocall 'cid-display ["" 0])))

(defn reconcile-lines
  "Render a reconcile plan as operator table lines.
   Pure title/col/row/detail/reach/drift from oracle; host mapcats apps
   and joins CSV for targets/running/reach/misplaced."
  [plan]
  (let [title (ocall 'reconcile-title
                     [(str (or (:fleet plan) "fleet")) (str (:ts plan))])
        col (ocall 'reconcile-col-header [])]
    (vec
     (concat
      [title col]
      (mapcat
       (fn [app]
         (let [action (name (:action app))
               targets-csv (str/join "," (:targets app))
               running-csv (str/join "," (:running app))
               running-empty (if (seq (:running app)) 0 1)
               reason (str (or (:reason app) ""))
               detail (ocall 'action-detail
                             [action targets-csv running-csv
                              (long running-empty) reason])
               app14 (pad-field (:app app) 14)
               cid10 (pad-field (fmt-cid (:cid app)) 10)
               act9 (pad-field action 9)
               front (ocall 'reconcile-app-row
                            [app14 cid10
                             (long (:desired app))
                             (long (count (:running app)))
                             act9])
               base [(ocall 'reconcile-app-line [front detail])]
               reach (when (seq (:reach app))
                       [(ocall 'reach-line
                               [(str/join "," (map str (:reach app)))
                                (str/join "," (:eligible app))])])
               misplaced (when (seq (:misplaced app))
                           [(ocall 'drift-line
                                   [(str/join "," (:misplaced app))])])]
           (concat base reach misplaced)))
       (:apps plan))))))

(defn command-help []
  (ocall 'command-help []))
