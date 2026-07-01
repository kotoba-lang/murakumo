;; murakumo.dash.state — portable dashboard snapshot transforms.
;;
;; Collection, persistence, JSON encoding, and HTTP serving stay in murakumo.dash.
;; This namespace owns deterministic snapshot -> record/alert/display data.

(ns murakumo.dash.state
  (:require [clojure.set :as set]
            [clojure.string :as str]))

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

(defn- short-cid [cid]
  (subs cid 0 (min 14 (count cid))))

(defn short-hosted-cid [cid]
  "CID abbreviation used in the dashboard hosted-components table."
  (subs cid 0 (min 18 (count cid))))

(defn hosted-summary
  "Dashboard table text for hosted component CIDs, or nil when none are hosted."
  [node]
  (when (seq (:hosted node))
    (str/join " " (map short-hosted-cid (:hosted node)))))

(defn health-class
  "CSS class for a node health value."
  [node]
  (if (= "ok" (:health node)) "ok" "down"))

(defn- parse-int [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn query-at
  "Parse dashboard `at=N` query parameter. Returns nil if absent."
  [query-string]
  (some-> query-string (->> (re-find #"at=(\d+)")) second parse-int))

(defn dashboard-options
  "Parse dashboard CLI args into port/interval defaults."
  [args]
  {:port (parse-int (or (first args) "8899"))
   :interval (parse-int (or (second args) "15"))})

(defn interval-sleep-ms
  "Milliseconds to sleep between dashboard snapshots."
  [seconds]
  (* 1000 seconds))

(defn clamp-at
  "Clamp a requested history offset into the available history range."
  [requested-at history-count]
  (min (max 0 (or requested-at 0)) (max 0 (dec history-count))))

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
  "Newest dashboard alerts first, capped for display."
  ([alerts] (recent-alerts alerts 6))
  ([alerts n]
   (take n (reverse alerts))))

(defn append-capped
  "Append one item to a vector-like history, keeping only the newest cap items."
  [items cap item]
  (vec (take-last cap (conj (vec items) item))))

(defn concat-capped
  "Append a collection to a vector-like history, keeping only the newest cap items."
  [items cap more]
  (vec (take-last cap (concat items more))))

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

(defn json-response
  "HTTP response map for JSON API bodies."
  [body]
  {:status 200
   :headers {"content-type" "application/json"}
   :body body})

(defn html-response
  "HTTP response map for dashboard HTML bodies."
  [body]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body body})

(defn probe-lines
  "Parse the H:/L:/P: probe stdout into a map of string key -> value."
  [out]
  (into {}
        (for [line (str/split-lines (str out))
              :when (>= (count line) 2)]
	          [(subs line 0 1) (subs line 2)])))

(defn probe-command
  "Remote shell command for one dashboard probe round-trip."
  [port]
  (format "echo \"H:$(curl -s -m4 http://localhost:%d/health 2>/dev/null)\"; echo \"L:$(grep 'peer connected' ~/.murakumo/mesh.log 2>/dev/null | grep -o '12D3[A-Za-z0-9]*' | sort -u | wc -l | tr -d ' ')\"; echo \"P:$(grep 'trigger: executed' ~/.murakumo/mesh.log 2>/dev/null | grep -oE 'bafy[a-z0-9]{40,}' | sort -u | tr '\\n' ',')\""
          port))

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
  "Parse the L: value from probe output."
  [s]
  (try (parse-int (str/trim (or s "0")))
       (catch #?(:clj Exception :cljs :default) _ 0)))

(defn parse-hosted
  "Parse comma-separated hosted component CIDs from the P: value."
  [s]
  (->> (str/split (or s "") #",")
       (remove str/blank?)
       vec))

(defn probe-node
  "Build a snapshot node from static node data and parsed probe values.

   `health-json` is already decoded by the host shell or nil on failure."
  [node health-json lines p2p-port]
  {:name (:name node)
   :host (:host node)
   :ip (:ip node)
   :online (boolean (:online? node))
   :health (if health-json "ok" "down")
   :wasm (get-in health-json [:subsystems :wasm_executor] "?")
   :links (parse-links (get lines "L"))
   :p2p p2p-port
   :hosted (parse-hosted (get lines "P"))})

(defn down-node
  "Snapshot node for a node that could not be reached."
  [node]
  {:name (:name node) :online false :health "down" :links 0 :hosted []})

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
