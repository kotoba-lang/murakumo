;; murakumo.report — portable CLI report formatting.
;;
;; W6 product-shell + T6.4: pure string helpers + CSV join seps + cid-display max
;; + CSV fold steps + table/line builders require the shipped `:report-core` KIR
;; on **every** platform. Host pure mirrors are gone — cljs/nbb must preload
;; shipped KIR (resources/ via nbb cwd, register-kir!, or set-resource-loader!)
;; before requiring this ns (ADR-260731-w6-t64-report-mirror-delete).
;; Host remains: map/keyword projection, collection walks, and the
;; reconcile-lines mapcat structure over apps.

(ns murakumo.report
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :report-core)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

;; ── constants (oracle SSoT) ────────────────────────────────────────────

(def report-csv-sep
  "CSV join for reconcile targets/running/reach. Kotoba SSoT (required)."
  (o 'report-csv-sep []))

(def report-csv-spaced-sep
  "Spaced CSV join for deploy-observed where list. Kotoba SSoT (required)."
  (o 'report-csv-spaced-sep []))

(def mesh-status-sep
  "Separator between binary/launch status. Kotoba SSoT (required)."
  (o 'mesh-status-sep []))

(def cid-display-max-len
  "Max chars for reconcile CID display truncate. Kotoba SSoT (required)."
  (oracle/i64->host (o 'cid-display-max-len [])))

(defn join-append
  "CSV-style fold step: empty acc ⇒ next only. Kotoba (required)."
  [acc sep next]
  (o 'join-append [(str (or acc "")) (str sep) (str next)]))

(defn csv-append
  "Append one CSV cell (comma sep). Kotoba (required)."
  [acc next]
  (o 'csv-append [(str (or acc "")) (str next)]))

(defn csv-spaced-append
  "Append one CSV cell (comma+space sep). Kotoba (required)."
  [acc next]
  (o 'csv-spaced-append [(str (or acc "")) (str next)]))

(defn csv-join
  "Join items with report-csv-sep via csv-append fold."
  [items]
  (reduce (fn [acc x] (csv-append acc (str x)))
          ""
          (or items [])))

(defn csv-spaced-join
  "Join items with report-csv-spaced-sep via csv-spaced-append."
  [items]
  (reduce (fn [acc x] (csv-spaced-append acc (str x)))
          ""
          (or items [])))

;; ── dual-source public API → oracle-required ─────────────────────────

(defn nodes-header []
  (o 'nodes-header []))

(defn nodes-row
  "Format one `murakumo nodes` row (oracle nodes-row + host projection)."
  [node ssh-ok mesh]
  (o 'nodes-row
     [(str (:name node))
      (str (or (:ip node) "?"))
      (boolean (:online? node))
      (boolean ssh-ok)
      (str mesh)]))

(defn mesh-status
  "Render installed/running probe outputs into a compact mesh status."
  [binary-status launch-status]
  (o 'mesh-status [(str binary-status) (str launch-status)]))

(defn status-header []
  (o 'status-header []))

(defn status-down-row [node]
  (o 'status-down-row [(str (:name node))]))

(defn status-row
  "Format one `murakumo status` row.
   Health presence via Product Value ABI optional i64 when oracle ready."
  [node health-json links p2p-port]
  (let [subsystems (:subsystems health-json)
        health? (when health-json 1)
        wasm (or (:wasm_executor subsystems) "?")
        links-str (if health-json (str links) "-")]
    (o 'status-row
       [(str (:name node))
        (oracle/option-i64 health?)
        (str wasm)
        (str links-str)
        (oracle/as-i64 p2p-port)])))

(defn status-row* [{:keys [node health-json links p2p-port]}]
  (status-row node health-json links p2p-port))

(defn deploy-observed-row [where publish-node]
  (if (seq where)
    (o 'deploy-observed-placed-line
       [(csv-spaced-join where) (str (:name publish-node))])
    (o 'deploy-observed-empty-line [])))

(defn node-prefix [node]
  (o 'node-prefix [(str (:name node))]))

(def unreachable-skipped-line
  (o 'unreachable-skipped-line []))

(defn provision-result-line [peered?]
  (o 'provision-result-line [(boolean peered?)]))

(defn launch-result-line
  "Format one launchctl up/down result row."
  [node result]
  (o 'launch-result-line [(str (:name node)) (str (:exit result))]))

(defn missing-pinned-binaries-lines [build-manifest]
  [(o 'missing-pinned-binaries-line1
      [(str (:version build-manifest)) (str (:git-sha build-manifest))])
   (o 'missing-pinned-binaries-line2 [])])

(defn rollout-line [build-manifest]
  (o 'rollout-line
     [(str (:version build-manifest))
      (str (:git-sha build-manifest))
      (str (:features build-manifest))]))

(defn collected-peers-line [count peers-file]
  (o 'collected-peers-line [(oracle/as-i64 count) (str peers-file)]))

(def mesh-pass1-line
  (o 'mesh-pass1-line []))

(def mesh-wait-peerid-line
  (o 'mesh-wait-peerid-line []))

(def mesh-pass2-line
  (o 'mesh-pass2-line []))

(def mesh-forming-line
  (o 'mesh-forming-line []))

(defn artifact-node-status [node result]
  (o 'artifact-node-status
     [(str (:name node)) (zero? (:exit result))]))

(defn deploy-start-line [manifest cid]
  (o 'deploy-start-line [(str manifest) (str cid)]))

(defn deploy-command-output [out err]
  (o 'deploy-command-output [(str out) (str err)]))

(defn pin-success-line [src sha version]
  (o 'pin-success-line [(str src) (str sha) (str version)]))

(defn missing-binary-line [path]
  (o 'missing-binary-line [(str path)]))

(def deploy-wait-placement-line
  (o 'deploy-wait-placement-line []))

(defn alert-line [alert]
  (o 'alert-line
     [(str (:level alert)) (str (:node alert)) (str (:msg alert))]))

(defn snapshot-error-line [message]
  (o 'snapshot-error-line [(str message)]))

(defn reconcile-persist-error-line [message]
  (o 'reconcile-persist-error-line [(str message)]))

(defn dashboard-start-line [port interval]
  (o 'dashboard-start-line [(oracle/as-i64 port) (oracle/as-i64 interval)]))

(defn apply-target-line [app target]
  (o 'apply-target-line [(str (:app app)) (str target)]))

(defn watch-start-line [seconds]
  (o 'watch-start-line [(oracle/as-i64 seconds)]))

(def operator-seed-required-line
  (o 'operator-seed-required-line []))

(def operator-seed-hex-required-line
  (o 'operator-seed-hex-required-line []))

(def deploy-usage-line
  (o 'deploy-usage-line []))

(def reconcile-usage-line
  (o 'reconcile-usage-line []))

(defn command-error-line
  "Render a validation error keyword for a command."
  [command error]
  (o 'command-error-line [(name command) (name error)]))

(def dashboard-no-persistence-line
  (o 'dashboard-no-persistence-line []))

(def reconcile-no-persistence-line
  (o 'reconcile-no-persistence-line []))

(def reconcile-converged-line
  (o 'reconcile-converged-line []))

(def reconcile-dry-run-line
  (o 'reconcile-dry-run-line []))

;; ── reconcile pure builders + host mapcat ────────────────────────────

(defn- pad-field
  "Left-align field via oracle pad-right; host supplies remaining pad from
  Clojure string count (not UTF-8 byte-length) so multi-byte glyphs like
  em-dash CID placeholder keep layout parity."
  [s width]
  (let [s (str s)
        pad (max 0 (- width (count s)))]
    (o 'pad-right [s (oracle/as-i64 pad)])))

(defn- fmt-cid [cid]
  (if cid
    (o 'cid-display [(subs cid 0 (min cid-display-max-len (count cid)))
                     true])
    (o 'cid-display ["" false])))

(defn reconcile-lines
  "Render a reconcile plan as operator table lines.
   Pure title/col/row/detail/reach/drift from oracle (required); host mapcats
   apps and CSV-joins targets/running/reach/misplaced via csv-append fold steps."
  [plan]
  (let [title (o 'reconcile-title
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
               running-empty (empty? (:running app))
               reason (str (or (:reason app) ""))
               detail (o 'action-detail
                         [action targets-csv running-csv
                          running-empty reason])
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
       (:apps plan))))))

(defn command-help []
  (o 'command-help []))
