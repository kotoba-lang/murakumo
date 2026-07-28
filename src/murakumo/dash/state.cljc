;; murakumo.dash.state — portable dashboard snapshot transforms.
;;
;; Collection, persistence, JSON encoding, and HTTP serving stay in murakumo.dash.
;; This namespace owns deterministic snapshot -> record/alert/display data.
;;
;; W6 product-shell authority (ADR-260728-w6-dash-hosted-fold-pure-oracle +
;; ADR-260728-w6-dash-defaults-pure-oracle + probe-command + cljs load):
;; pure display + probe/parse + dashboard defaults + hosted-join-sep +
;; hosted-append fold DELEGATE to precompiled kotoba/dash_state_core.kotoba
;; KIR when oracle is loadable (JVM classpath or cljs/nbb —
;; ADR-260728-w6-cljs-oracle-load).
;; Map/vector folds, HTML join, probe-lines fold, parse-hosted split, and
;; query-string stay host/cljc. cljs mirrors remain fallback when not ready.

(ns murakumo.dash.state
  "Dashboard pure helpers use kotoba/dash_state_core.kotoba when oracle ready."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :dash-state)

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

(defn- parse-int [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

;; ── host-mirror pure helpers (cljs fallback + semantic documentation) ──

(def ^:private mirror-short-hosted-cid-max-len 18)
(def ^:private mirror-short-cid-max-len 14)
(def ^:private mirror-hosted-join-sep " ")
(def ^:private mirror-default-dashboard-port 8899)
(def ^:private mirror-default-dashboard-interval 15)
(def ^:private mirror-default-dashboard-port-str "8899")
(def ^:private mirror-default-dashboard-interval-str "15")

(defn- mirror-short-hosted-cid [cid]
  (subs cid 0 (min mirror-short-hosted-cid-max-len (count cid))))

(defn- mirror-health-class [health]
  (if (= "ok" health) "ok" "down"))

(defn- mirror-interval-sleep-ms [seconds]
  (* 1000 seconds))

(defn- mirror-clamp-at [requested-at history-count]
  (min (max 0 (or requested-at 0)) (max 0 (dec history-count))))

(defn- mirror-take-last-start [len cap]
  (if (< cap 1)
    len
    (max 0 (- len cap))))

(defn- mirror-recent-take-n [n default-n]
  (if (neg? n) default-n n))

(defn- mirror-parse-links [s]
  (try (parse-int (str/trim (or s "0")))
       (catch #?(:clj Exception :cljs :default) _ 0)))
(defn- mirror-probe-line-key [line]
  (if (and (>= (count line) 2) (= (subs line 1 2) ":"))
    (subs line 0 1)
    ""))

(defn- mirror-probe-line-value [line]
  (if (seq (mirror-probe-line-key line))
    (subs line 2)
    ""))
(defn- mirror-health-from-present [present?]
  (if present? "ok" "down"))
(def ^:private mirror-content-type-json "application/json")
(def ^:private mirror-content-type-html "text/html; charset=utf-8")
(def ^:private mirror-http-ok-status 200)

;; ── dual-source dashboard defaults + join seps ───────────────────────

(def short-hosted-cid-max-len
  "Max chars for short-hosted-cid. Kotoba when ready."
  (oracle-i64-const 'short-hosted-cid-max-len mirror-short-hosted-cid-max-len))

(def short-cid-max-len
  "Max chars for alert/table short-cid. Kotoba when ready."
  (oracle-i64-const 'short-cid-max-len mirror-short-cid-max-len))

(def hosted-join-sep
  "Separator between hosted CIDs in hosted-summary. Kotoba when ready."
  (oracle-str-const 'hosted-join-sep mirror-hosted-join-sep))

(defn- mirror-join-append [acc sep next]
  (if (str/blank? (str acc))
    (str next)
    (str acc sep next)))

(defn- mirror-hosted-append [acc next]
  (mirror-join-append acc mirror-hosted-join-sep next))

(defn join-append
  "Generic empty-first join fold step. Kotoba when ready."
  [acc sep next]
  (try-oracle
   #(o 'join-append [(str (or acc "")) (str sep) (str next)])
   #(mirror-join-append acc sep next)))

(defn hosted-append
  "Append one short-hosted-cid to hosted-summary acc. Kotoba when ready."
  [acc next]
  (try-oracle
   #(o 'hosted-append [(str (or acc "")) (str next)])
   #(mirror-hosted-append acc next)))

(def default-dashboard-port
  "Default dashboard HTTP port. Kotoba when ready."
  (oracle-i64-const 'default-dashboard-port mirror-default-dashboard-port))

(def default-dashboard-interval
  "Default snapshot interval seconds. Kotoba when ready."
  (oracle-i64-const 'default-dashboard-interval mirror-default-dashboard-interval))

(def default-dashboard-port-str
  "CLI default port string when arg absent. Kotoba when ready."
  (oracle-str-const 'default-dashboard-port-str mirror-default-dashboard-port-str))

(def default-dashboard-interval-str
  "CLI default interval string when arg absent. Kotoba when ready."
  (oracle-str-const 'default-dashboard-interval-str
                    mirror-default-dashboard-interval-str))

;; ── pure display helpers: kotoba dash_state_core SSoT when oracle ready ─

(defn short-hosted-cid
  "CID abbreviation used in the dashboard hosted-components table.
   Kotoba `short-hosted-cid` when oracle ready (falls back if KIR
   string-substring bounds fail on a runtime — e.g. some cljs kir builds)."
  [cid]
  (let [s (str cid)]
    (if (oracle-ready?)
      (try
        (o 'short-hosted-cid [s])
        (catch #?(:clj Exception :cljs :default) _
          (mirror-short-hosted-cid s)))
      (mirror-short-hosted-cid s))))

(defn- short-cid [cid]
  (subs cid 0 (min short-cid-max-len (count cid))))

(defn hosted-summary
  "Dashboard table text for hosted component CIDs, or nil when none are hosted.
   Join step dual-sourced via `hosted-append`; short-hosted-cid + walk stay host."
  [node]
  (when (seq (:hosted node))
    (reduce (fn [acc cid]
              (hosted-append acc (short-hosted-cid cid)))
            ""
            (:hosted node))))

(defn health-class
  "CSS class for a node health value.
   Kotoba `health-class-of` on `:health` when oracle ready."
  [node]
  (let [h (str (or (:health node) ""))]
    (if (oracle-ready?)
      (o 'health-class-of [h])
      (mirror-health-class h))))

(defn query-at
  "Parse dashboard `at=N` query parameter. Returns nil if absent. The regex is
  anchored to a key boundary (start-of-string or `?`/`&`, terminated by `&` or
  end-of-string) so it matches the exact key `at`, not any longer key that
  happens to END in \"at=<digits>\" as a substring (e.g. \"format=5\",
  \"chat=5\", \"combat=12\" would otherwise all be misread as history offsets
  5/5/12 -- selected-snapshot would silently serve stale history instead of
  the live snapshot for any query string containing such a param)."
  [query-string]
  (some-> query-string (->> (re-find #"(?:^|[?&])at=(\d+)(?:&|$)")) second parse-int))

(defn dashboard-options
  "Parse dashboard CLI args into port/interval defaults.
   Default strings dual-sourced via `default-dashboard-*-str`."
  [args]
  {:port (parse-int (or (first args) default-dashboard-port-str))
   :interval (parse-int (or (second args) default-dashboard-interval-str))})

(defn interval-sleep-ms
  "Milliseconds to sleep between dashboard snapshots.
   Kotoba `interval-sleep-ms` when oracle ready."
  [seconds]
  (if (oracle-ready?)
    (oracle/i64->host (o 'interval-sleep-ms [(oracle/as-i64 seconds)]))
    (mirror-interval-sleep-ms seconds)))

(defn clamp-at
  "Clamp a requested history offset into the available history range.
   Kotoba `clamp-at` when oracle ready (nil requested-at → 0)."
  [requested-at history-count]
  (if (oracle-ready?)
    (oracle/i64->host
     (o 'clamp-at [(oracle/as-i64 (or requested-at 0))
                   (oracle/as-i64 history-count)]))
    (mirror-clamp-at requested-at history-count)))

(defn selected-snapshot
  "Select dashboard snapshot for a history offset.

   at=0 is latest; history is stored oldest->newest. Falls back to cache when
   history is empty."
  [history cache requested-at]
  (let [history-count (count history)
        at (clamp-at requested-at history-count)]
    {:at at
     :total history-count
     :live? (zero? at)
     :snapshot (if (pos? history-count)
                 (nth history (- history-count 1 at))
                 cache)}))

(defn recent-alerts
  "Newest dashboard alerts first, capped for display.
   Cap `n` via kotoba `recent-take-n` when oracle ready (negative → default 6)."
  ([alerts] (recent-alerts alerts 6))
  ([alerts n]
   (let [take-n (if (oracle-ready?)
                  (oracle/i64->host
                   (o 'recent-take-n [(oracle/as-i64 n) (oracle/as-i64 6)]))
                  (mirror-recent-take-n n 6))]
     (take take-n (reverse alerts)))))

(defn append-capped
  "Append one item to a vector-like history, keeping only the newest cap items.
   Start index via kotoba `take-last-start` when oracle ready; vector slice stays host."
  [items cap item]
  (let [v (conj (vec items) item)
        len (count v)
        start (if (oracle-ready?)
                (oracle/i64->host
                 (o 'take-last-start [(oracle/as-i64 len) (oracle/as-i64 cap)]))
                (mirror-take-last-start len cap))]
    (subvec v start)))

(defn concat-capped
  "Append a collection to a vector-like history, keeping only the newest cap items."
  [items cap more]
  (vec (take-last cap (concat items more))))

;; ── map/vector host folds (stay cljc) ────────────────────────────────

(defn placements
  "Flatten snapshot hosted CIDs into [{:node name :cid cid} ...]."
  [snapshot]
  (vec (mapcat (fn [node]
                 (map #(hash-map :node (:name node) :cid %) (:hosted node)))
               (:nodes snapshot))))

(defn links-total
  "Total mesh link count across snapshot nodes."
  [snapshot]
  (reduce + (map #(or (:links %) 0) (:nodes snapshot))))

(defn snapshot-record
  "Build the atproto record payload for a fleet snapshot.

   `snapshot-json` is supplied by the host shell so this namespace stays free of
   any JSON dependency."
  [snapshot snapshot-json]
  {:$type "com.murakumo.fleet.snapshot"
   :ts (:ts snapshot)
   :fleet (:fleet snapshot)
   :nodes (count (:nodes snapshot))
   :links_total (links-total snapshot)
   :placements (placements snapshot)
   :snapshot snapshot-json})

(defn snapshot
  "Build the live dashboard snapshot map."
  [fleet ts nodes]
  {:ts ts
   :fleet (:fleet/name fleet)
   :nodes (vec nodes)})

(defn collect-node-plans
  "Plan whether each node should be probed or recorded as down."
  [nodes reachable-node?]
  (mapv (fn [node]
          {:action (if (and (:online? node) (reachable-node? node)) :probe :down)
           :node node})
        nodes))

(defn render-html
  "Render the dashboard HTML for a selected snapshot.

   The host shell passes persistence counters and alerts in explicitly so rendering
   remains deterministic and testable."
  [snapshot at total live? persisted-count alerts]
  (let [{:keys [ts nodes fleet]} snapshot]
    (str "<!doctype html><html><head><meta charset=utf-8><title>murakumo</title>"
         (when live? "<meta http-equiv=refresh content=10>")
         "<style>body{font:14px ui-monospace,Menlo,monospace;background:#0b0e14;color:#cdd6f4;margin:24px}"
         "h1{font-size:18px}a{color:#89b4fa}table{border-collapse:collapse;margin-top:12px}td,th{padding:6px 14px;text-align:left;border-bottom:1px solid #313244}"
         "th{color:#89b4fa}.ok{color:#a6e3a1}.down{color:#f38ba8}.muted{color:#6c7086}.cid{color:#fab387;font-size:12px}"
         ".nav{margin:10px 0}.al{margin:10px 0;padding:8px 12px;border-radius:6px}.error{background:#2a1620;color:#f38ba8}.warn{background:#2a2416;color:#f9e2af}.info{background:#16242a;color:#94e2d5}</style></head><body>"
         "<h1>叢雲 murakumo — " (or fleet "kotoba wasm mesh") (when-not live? " · time-travel") "</h1>"
         "<div class=muted>snapshot " ts " · persisted " persisted-count " snapshots to the Datom log (graph murakumo-fleet)</div>"
         "<div class=nav>history " (inc at) "/" (max 1 total) " &nbsp; "
         (if (< (inc at) total) (str "<a href='/?at=" (inc at) "'>◀ older</a>") "<span class=muted>◀ older</span>")
         " &nbsp; <a href='/'>latest ▶▶</a>"
         (when (pos? at) (str " &nbsp; <a href='/?at=" (dec at) "'>newer ▶</a>")) "</div>"
         (let [display-alerts (recent-alerts alerts)]
           (if (seq display-alerts)
             (apply str
                    (for [alert display-alerts]
                      (str "<div class='al " (:level alert) "'>⚠ " (:node alert) " — " (:msg alert)
                           " <span class=muted>" (:ts alert) "</span></div>")))
             "<div class='al info'>✓ no liveness alerts</div>"))
         "<table><tr><th>NODE</th><th>HEALTH</th><th>WASM</th><th>LINKS</th><th>P2P</th><th>HOSTED (placed components)</th></tr>"
         (apply str
                (for [node nodes]
                  (str "<tr><td>" (:name node) "</td>"
                       "<td class=" (health-class node) ">" (:health node) "</td>"
                       "<td>" (or (:wasm node) "—") "</td>"
                       "<td>" (:links node) "</td>"
                       "<td class=muted>" (:p2p node) "</td>"
                       "<td class=cid>" (or (hosted-summary node) "<span class=muted>—</span>") "</td></tr>")))
         "</table></body></html>")))

(def content-type-json
  (oracle-str-const 'content-type-json mirror-content-type-json))

(def content-type-html
  (oracle-str-const 'content-type-html mirror-content-type-html))

(def http-ok-status
  (oracle-i64-const 'http-ok-status mirror-http-ok-status))

(defn json-response
  "HTTP response map for JSON API bodies.
   status + content-type via kotoba when ready."
  [body]
  {:status http-ok-status
   :headers {"content-type" content-type-json}
   :body body})

(defn html-response
  "HTTP response map for dashboard HTML bodies.
   status + content-type via kotoba when ready."
  [body]
  {:status http-ok-status
   :headers {"content-type" content-type-html}
   :body body})

(defn- probe-line-key
  "First character of an H:/L:/P: probe line. Kotoba when ready."
  [line]
  (try-oracle
   #(o 'probe-line-key [(str line)])
   #(mirror-probe-line-key line)))

(defn- probe-line-value
  "Payload after the key colon. Kotoba when ready."
  [line]
  (try-oracle
   #(o 'probe-line-value [(str line)])
   #(mirror-probe-line-value line)))

(defn probe-lines
  "Parse the H:/L:/P: probe stdout into a map of string key -> value.
   Per-line key/value pure via kotoba; line fold stays host."
  [out]
  (into {}
        (for [line (str/split-lines (str out))
              :let [k (probe-line-key line)]
              :when (seq k)]
          [k (probe-line-value line)])))

(defn- mirror-health-url [port]
  (str "http://localhost:" port "/health"))

(defn- mirror-mesh-log-path []
  "~/.murakumo/mesh.log")

(defn- mirror-probe-command [port]
  (str "echo \"H:$(curl -s -m4 http://localhost:" port "/health 2>/dev/null)\"; echo \"L:$(grep 'peer connected' ~/.murakumo/mesh.log 2>/dev/null | grep -o '12D3[A-Za-z0-9]*' | sort -u | wc -l | tr -d ' ')\"; echo \"P:$(grep 'trigger: executed' ~/.murakumo/mesh.log 2>/dev/null | grep -oE 'bafy[a-z0-9]{40,}' | sort -u | tr '\\n' ',')\""))

(defn health-url
  "Local health URL for a control port. Kotoba `health-url` when ready."
  [port]
  (try-oracle
   #(o 'health-url [(oracle/as-i64 port)])
   #(mirror-health-url port)))

(defn mesh-log-path
  "Mesh log path used by probe L:/P: clauses. Kotoba when ready."
  []
  (try-oracle
   #(o 'mesh-log-path [])
   mirror-mesh-log-path))

(defn probe-command
  "Remote shell command for one dashboard probe round-trip.
   Kotoba `probe-command` when ready (pure shell string compose).
   SSH execution of this string stays host-forever."
  [port]
  (try-oracle
   #(o 'probe-command [(oracle/as-i64 port)])
   #(mirror-probe-command port)))
(defn parse-health
  "Decode health JSON with a host-supplied decoder, returning nil on failure."
  [decode-fn text]
  (try
    (decode-fn text)
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn status-row-input
  "Shape inputs needed by the portable report/status formatter."
  [node health-json links p2p-port]
  {:node node
   :health-json health-json
   :links links
   :p2p-port p2p-port})

(defn parse-links
  "Parse the L: value from probe output.
   Kotoba `parse-links` (trim + digits → i64, 0 on bad) when ready."
  [s]
  (try-oracle
   #(oracle/i64->host (o 'parse-links [(str (or s ""))]))
   #(mirror-parse-links s)))

(defn parse-hosted
  "Parse comma-separated hosted component CIDs from the P: value."
  [s]
  (->> (str/split (or s "") #",")
       (remove str/blank?)
       vec))

(defn health-from-present
  "ok/down label from health-json presence. Kotoba when ready."
  [present?]
  (try-oracle
   #(o 'health-from-present [(oracle/as-i64 (if present? 1 0))])
   #(mirror-health-from-present present?)))

(defn probe-node
  "Build a snapshot node from static node data and parsed probe values.

   `health-json` is already decoded by the host shell or nil on failure."
  [node health-json lines p2p-port]
  {:name (:name node)
   :host (:host node)
   :ip (:ip node)
   :online (boolean (:online? node))
   :health (health-from-present (some? health-json))
   :wasm (get-in health-json [:subsystems :wasm_executor] "?")
   :links (parse-links (get lines "L"))
   :p2p p2p-port
   :hosted (parse-hosted (get lines "P"))})

(defn down-node
  "Snapshot node for a node that could not be reached."
  [node]
  {:name (:name node)
   :online false
   :health (health-from-present false)
   :links 0
   :hosted []})
(defn diff-alerts
  "Compare two snapshots and surface liveness changes.

   Alerts cover node down/recovery, complete link loss, link degradation, and
   hosted component eviction. nil previous snapshot yields no alerts."
  [prev curr]
  (when prev
    (let [prev-by-name (into {} (map (juxt :name identity)) (:nodes prev))
          ts (:ts curr)]
      (vec
       (mapcat
        (fn [node]
          (let [prev-node (get prev-by-name (:name node))
                node-name (:name node)]
            (when prev-node
              (let [prev-hosted (set (:hosted prev-node))
                    hosted (set (:hosted node))
                    evicted (set/difference prev-hosted hosted)]
                (cond-> []
                  (and (= "ok" (:health prev-node)) (not= "ok" (:health node)))
                  (conj {:level "error" :node node-name :msg "node went DOWN" :ts ts})

                  (and (not= "ok" (:health prev-node)) (= "ok" (:health node)))
                  (conj {:level "info" :node node-name :msg "node recovered" :ts ts})

                  (and (number? (:links prev-node)) (number? (:links node))
                       (pos? (:links prev-node)) (zero? (:links node)))
                  (conj {:level "error" :node node-name
                         :msg (str "lost all mesh links (" (:links prev-node) "→0)")
                         :ts ts})

                  (and (number? (:links prev-node)) (number? (:links node))
                       (pos? (:links node)) (< (:links node) (:links prev-node)))
                  (conj {:level "warn" :node node-name
                         :msg (str "links degraded " (:links prev-node) "→" (:links node))
                         :ts ts})

                  (seq evicted)
                  (conj {:level "warn" :node node-name
                         :msg (str "component evicted: "
                                   (str/join "," (map short-cid evicted)))
                         :ts ts}))))))
        (:nodes curr))))))
