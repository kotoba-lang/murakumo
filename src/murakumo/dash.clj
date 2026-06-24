;; murakumo.dash — fleet state persistence + a web dashboard.
;;
;; A background snapshotter polls the fleet every `interval` seconds (each node's
;; /health + live libp2p link count + the component CIDs it HOSTS) and:
;;   1. PERSISTS the snapshot to the kotoba Datom log (append-only as-of history of
;;      the fleet's heartbeat + placement state) via a node's atproto.repo.write;
;;   2. caches it for the dashboard.
;; The HTTP server (babashka's built-in http-kit) renders `murakumo status` as an
;; auto-refreshing web page, plus a JSON API at /api.

(ns murakumo.dash
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [murakumo.fleet :as fleet]
            [murakumo.ssh :as ssh]
            [org.httpkit.server :as http])
  (:import (java.security MessageDigest)
           (java.util Base64)))

;; ── identity / graph (kept self-contained — mirrors core's operator token) ────

(defn- operator-seed [fleet]
  (or (System/getenv (:fleet/operator-seed-env fleet)) (System/getenv "MURAKUMO_OPERATOR_SEED")))

(defn- bin [] (let [b (str (System/getProperty "user.dir") "/bin/kotoba")]
                (if (.exists (java.io.File. b)) b "kotoba")))

(defn- did-for [seed] (str/trim (str (:out (p/sh (bin) "did-derive" seed)))))

(defn- b64url [^bytes b] (-> (.encodeToString (Base64/getUrlEncoder) b) (str/replace "=" "")))

(defn- op-token [did]
  (str (b64url (.getBytes "{\"alg\":\"HS256\",\"typ\":\"JWT\"}" "UTF-8")) "."
       (b64url (.getBytes (format "{\"sub\":\"%s\",\"exp\":9999999999}" did) "UTF-8")) "."
       "kotoba-cli-media"))

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

(defn graph-cid
  "KotobaCid::from_bytes(name) — CIDv1 dag-cbor sha2-256, base32lower, 'b' prefix."
  [name]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str name) "UTF-8"))
        raw (byte-array (concat [0x01 0x71 0x12 0x20] (seq d)))
        bits (mapcat (fn [byte] (map #(bit-and (bit-shift-right (bit-and (int byte) 0xff) %) 1) [7 6 5 4 3 2 1 0])) (seq raw))]
    (str "b" (->> (partition 5 5 (repeat 0) bits)
                  (map (fn [c] (.charAt b32 (reduce #(+ (* %1 2) %2) 0 c))))
                  (apply str)))))

;; ── collect ──────────────────────────────────────────────────────────────────

(defn- node-port [fleet n] (or (:port n) (:fleet/port fleet) 8077))
(defn- p2p-port [fleet n] (or (:p2p-port n) (:fleet/p2p-port fleet) 4001))

(defn- probe
  "One round-trip per node: health JSON + distinct connected peers + the distinct
   component CIDs this node has executed (= what the lattice placed here)."
  [fleet n]
  (let [port (node-port fleet n)
        ;; the log colourises with ANSI codes (cid<ESC>=<ESC>bafy…), so extract the
        ;; bafy… CID value directly from executed-trigger lines, not a `cid=` literal.
        out (:out (ssh/sh (:host n)
                          (format "echo \"H:$(curl -s -m4 http://localhost:%d/health 2>/dev/null)\"; echo \"L:$(grep 'peer connected' ~/.murakumo/mesh.log 2>/dev/null | grep -o '12D3[A-Za-z0-9]*' | sort -u | wc -l | tr -d ' ')\"; echo \"P:$(grep 'trigger: executed' ~/.murakumo/mesh.log 2>/dev/null | grep -oE 'bafy[a-z0-9]{40,}' | sort -u | tr '\\n' ',')\"" port)))
        lines (into {} (for [l (str/split-lines (str out))
                             :let [[k v] [(subs l 0 1) (subs l 2)]]] [k v]))
        h (try (json/parse-string (get lines "H" "") true) (catch Exception _ nil))]
    {:name (:name n) :host (:host n) :ip (:ip n)
     :online (boolean (:online? n))
     :health (if h "ok" "down")
     :wasm (get-in h [:subsystems :wasm_executor] "?")
     :links (try (Integer/parseInt (str/trim (get lines "L" "0"))) (catch Exception _ 0))
     :p2p (p2p-port fleet n)
     :hosted (->> (str/split (get lines "P" "") #",") (remove str/blank?) vec)}))

(defn collect [fleet]
  (let [fleet (fleet/enrich fleet)
        ts (.toString (java.time.Instant/now))]
    {:ts ts
     :fleet (:fleet/name fleet)
     :nodes (mapv (fn [n] (if (and (:online? n) (ssh/reachable? (:host n)))
                            (probe fleet n)
                            {:name (:name n) :online false :health "down" :links 0 :hosted []}))
                  (:nodes fleet))}))

;; ── persist (append-only as-of history on the kotoba Datom log) ───────────────

(def ^:private persist-port 18099)
(def ^:private snap-seq (atom 0))

(defn- ensure-forward! [host port]
  (p/sh "bash" "-c" (format "pgrep -f '%d:localhost:%d %s' >/dev/null 2>&1 || ssh -o BatchMode=yes -fN -L %d:localhost:%d %s"
                            persist-port port host persist-port port host)))

(defn persist!
  "Write the snapshot as an atproto record into graph 'murakumo-fleet' on a node's
   Datom log — one tx per snapshot ⇒ tamper-evident, queryable as-of history."
  [fleet token target snap]
  (let [gcid (graph-cid "murakumo-fleet")
        seq (swap! snap-seq inc)
        rkey (str "snap-" (System/currentTimeMillis) "-" seq)
        uri (format "at://did:web:etzhayyim.com:murakumo/com.murakumo.fleet.snapshot/%s" rkey)]
    (ensure-forward! (:host target) (node-port fleet target))
    (Thread/sleep 400)
    (let [body (json/generate-string
                {:graph gcid :uri uri :operation "create"
                 :cid (graph-cid rkey)
                 :record {:$type "com.murakumo.fleet.snapshot"
                          :ts (:ts snap) :fleet (:fleet snap)
                          :nodes (count (:nodes snap))
                          :links_total (reduce + (map :links (:nodes snap)))
                          :placements (vec (mapcat (fn [n] (map #(hash-map :node (:name n) :cid %) (:hosted n))) (:nodes snap)))
                          :snapshot (json/generate-string snap)}})
          r (p/sh "curl" "-s" "-m" "6" "-X" "POST"
                  (format "http://localhost:%d/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write" persist-port)
                  "-H" (str "Authorization: Bearer " token) "-H" "content-type: application/json"
                  "-d" body)]
      (some? (re-find #"\"status\":\"ok\"" (str (:out r)))))))

;; ── web ──────────────────────────────────────────────────────────────────────

(def ^:private cache (atom {:ts "—" :nodes []}))
(def ^:private persisted (atom 0))

(defn- html [{:keys [ts nodes fleet]}]
  (str "<!doctype html><html><head><meta charset=utf-8><title>murakumo</title>"
       "<meta http-equiv=refresh content=10>"
       "<style>body{font:14px ui-monospace,Menlo,monospace;background:#0b0e14;color:#cdd6f4;margin:24px}"
       "h1{font-size:18px}table{border-collapse:collapse;margin-top:12px}td,th{padding:6px 14px;text-align:left;border-bottom:1px solid #313244}"
       "th{color:#89b4fa}.ok{color:#a6e3a1}.down{color:#f38ba8}.muted{color:#6c7086}.cid{color:#fab387;font-size:12px}</style></head><body>"
       "<h1>叢雲 murakumo — " (or fleet "kotoba wasm mesh") "</h1>"
       "<div class=muted>snapshot " ts " · persisted " @persisted " snapshots to the Datom log (graph murakumo-fleet)</div>"
       "<table><tr><th>NODE</th><th>HEALTH</th><th>WASM</th><th>LINKS</th><th>P2P</th><th>HOSTED (placed components)</th></tr>"
       (apply str (for [n nodes]
                    (str "<tr><td>" (:name n) "</td>"
                         "<td class=" (if (= "ok" (:health n)) "ok" "down") ">" (:health n) "</td>"
                         "<td>" (or (:wasm n) "—") "</td>"
                         "<td>" (:links n) "</td>"
                         "<td class=muted>" (:p2p n) "</td>"
                         "<td class=cid>" (if (seq (:hosted n)) (str/join " " (map #(subs % 0 (min 18 (count %))) (:hosted n))) "<span class=muted>—</span>") "</td></tr>")))
       "</table></body></html>"))

(defn- handler [req]
  (case (:uri req)
    "/api" {:status 200 :headers {"content-type" "application/json"} :body (json/generate-string @cache)}
    {:status 200 :headers {"content-type" "text/html; charset=utf-8"} :body (html @cache)}))

(defn- snapshotter [fleet token target interval]
  (future
    (loop []
      (try
        (let [snap (collect fleet)]
          (reset! cache snap)
          (when (and token target (persist! fleet token target snap)) (swap! persisted inc)))
        (catch Exception e (binding [*out* *err*] (println "snapshot error:" (.getMessage e)))))
      (Thread/sleep (* 1000 interval))
      (recur))))

(defn -main [& args]
  (let [fleet (fleet/load-fleet)
        port (Integer/parseInt (or (first args) "8899"))
        interval (Integer/parseInt (or (second args) "15"))
        seed (operator-seed fleet)
        token (when seed (op-token (did-for seed)))
        target (first (filter #(= "asher" (:name %)) (:nodes fleet)))]
    (when-not token (println "(no MURAKUMO_OPERATOR_SEED → dashboard live-only, no Datom persistence)"))
    (snapshotter fleet token target interval)
    (http/run-server handler {:port port})
    (println (format "murakumo dashboard → http://localhost:%d  (snapshot every %ds → Datom log)" port interval))
    @(promise)))
