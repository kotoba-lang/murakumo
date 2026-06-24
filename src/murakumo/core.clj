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
            [clojure.string :as str]
            [cheshire.core :as json]
            [murakumo.fleet :as fleet]
            [murakumo.ssh :as ssh]))

(def ^:private remote-bin "$HOME/.murakumo/bin")  ; where node binaries live
(def ^:private plist-label "com.murakumo.kotoba-mesh")
(def ^:private kotoba-dir
  (or (System/getenv "MURAKUMO_KOTOBA_DIR")
      (str (System/getenv "HOME")
           "/github/com-junkawasaki/orgs/com-junkawasaki/kotoba")))

;; Binary resolution (raced-checkout safety): murakumo deploys a PINNED binary
;; set it owns (./bin/, gitignored) so a concurrent rebuild of the shared kotoba
;; checkout can't swap the CLI/server protocol out from under a live fleet.
;; Fallbacks: $MURAKUMO_BIN, then the kotoba checkout's target/.
(def ^:private local-bin
  (let [pinned (str (System/getProperty "user.dir") "/bin")]
    (cond
      (.exists (java.io.File. pinned "kotoba-server")) pinned
      (System/getenv "MURAKUMO_BIN") (System/getenv "MURAKUMO_BIN")
      :else (str kotoba-dir "/target/aarch64-apple-darwin/release"))))

;; ── identity ──────────────────────────────────────────────────────────────
;; Derive a deterministic per-node Ed25519 seed from the shared operator seed +
;; node name, so node identities are stable + reproducible without storing a
;; secret per node. The operator seed itself comes from the env (never committed).

(defn- operator-seed [fleet]
  (or (System/getenv (:fleet/operator-seed-env fleet))
      (System/getenv "MURAKUMO_OPERATOR_SEED")))

(defn- sha256-hex [^String s]
  (->> (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))
       (map #(format "%02x" (bit-and (int %) 0xff))) (apply str)))

(defn- node-seed [fleet node]
  ;; 32-byte hex, deterministic: sha256(operator-seed | node-name).
  (sha256-hex (str (operator-seed fleet) ":" (:name node))))

(defn- did-for [node-seed-hex]
  ;; ask the local kotoba CLI to derive the did:key for this seed.
  (str/trim (str (:out (p/sh (str local-bin "/kotoba") "did-derive" node-seed-hex)))))

;; ── commands ────────────────────────────────────────────────────────────────

(defn cmd-nodes
  "List the fleet with Tailscale reachability + whether the mesh binary/agent is
   present. Read-only — the at-a-glance fleet map."
  [fleet _]
  (let [fleet (fleet/enrich fleet)]
    (println (format "%-10s %-16s %-8s %-9s %s" "NODE" "TAILSCALE-IP" "ONLINE" "SSH" "MESH"))
    (doseq [n (:nodes fleet)]
      (let [ssh-ok (and (:online? n) (ssh/reachable? (:host n)))
            mesh (if ssh-ok
                   (let [b (:out (ssh/sh (:host n) (str "test -x " remote-bin "/kotoba-server && echo installed || echo absent")))
                         up (:out (ssh/sh (:host n) (str "sudo launchctl print system/" plist-label " >/dev/null 2>&1 && echo running || echo stopped")))]
                     (str b "/" up))
                   "-")]
        (println (format "%-10s %-16s %-8s %-9s %s"
                         (:name n) (or (:ip n) "?")
                         (if (:online? n) "yes" "no")
                         (if ssh-ok "ok" "no") mesh))))))

(def ^:private peers-file ".murakumo-peers.edn")  ; node-name → libp2p PeerId (control-plane state)

(defn- node-port [fleet n] (or (:port n) (:fleet/port fleet) 8077))
(defn- node-p2p-port [fleet n] (or (:p2p-port n) (:fleet/p2p-port fleet) 4001))
(defn- node-p2p-seed [fleet n] (sha256-hex (str (operator-seed fleet) ":" (:name n) ":p2p")))
(defn- multiaddr [ip port] (format "/ip4/%s/udp/%d/quic-v1" ip port))

(defn- bootstrap-str
  "Comma-list of `peerid@multiaddr` for every OTHER node we know a PeerId for."
  [fleet peers self]
  (->> (:nodes fleet)
       (remove #(= (:name %) (:name self)))
       (keep (fn [n] (when-let [pid (get peers (:name n))]
                       (str pid "@" (multiaddr (:ip n) (node-p2p-port fleet n))))))
       (str/join ",")))

(defn- provision-node
  "rsync the binaries + render & load the LaunchDaemon for one (enriched) node.
   `peers` is the name→PeerId map (for KOTOBA_BOOTSTRAP_PEERS; may be empty)."
  [fleet tmpl peers n]
  (let [host (:host n)]
    (print (format "[%s] " (:name n))) (flush)
    (if-not (ssh/reachable? host)
      (println "unreachable — skipped")
      (let [op-seed (operator-seed fleet)
            user (:out (ssh/sh host "whoami"))
            home (:out (ssh/sh host "echo $HOME"))
            plist (-> tmpl
                      (str/replace "{{USER}}" user)
                      (str/replace "{{BIN}}" (str home "/.murakumo/bin"))
                      (str/replace "{{PORT}}" (str (node-port fleet n)))
                      (str/replace "{{ROLES}}" (str/join "," (:roles n)))
                      (str/replace "{{LABELS}}" (->> (:labels n) (map (fn [[k v]] (str (name k) "=" v))) (str/join ",")))
                      (str/replace "{{HOME}}" home)
                      (str/replace "{{ED25519}}" op-seed)
                      (str/replace "{{X25519}}" (sha256-hex (str op-seed ":x25519")))
                      (str/replace "{{DID}}" (did-for op-seed))
                      (str/replace "{{P2PPORT}}" (str (node-p2p-port fleet n)))
                      (str/replace "{{P2PSEED}}" (node-p2p-seed fleet n))
                      (str/replace "{{EXTADDR}}" (if (:ip n) (multiaddr (:ip n) (node-p2p-port fleet n)) ""))
                      (str/replace "{{BOOTSTRAP}}" (bootstrap-str fleet peers n)))]
        (ssh/sh host "mkdir -p ~/.murakumo/bin ~/.murakumo/store")
        (print "rsync… ") (flush)
        (doseq [bin ["kotoba" "kotoba-server"]]
          (apply p/sh (concat ["rsync" "-az" "-e" "ssh -o BatchMode=yes -o ConnectTimeout=8"]
                              [(str local-bin "/" bin) (str host ":.murakumo/bin/" bin)])))
        (let [pp (str "/Library/LaunchDaemons/" plist-label ".plist")]
          (ssh/sh host (format "sudo tee %s >/dev/null <<'PLIST'\n%s\nPLIST" pp plist))
          (ssh/sh host (format "sudo launchctl bootout system/%s 2>/dev/null; sudo launchctl bootstrap system %s" plist-label pp)))
        (println (str "provisioned + loaded" (when (seq (bootstrap-str fleet peers n)) " (peered)")))))))

(defn- load-peers [] (try (clojure.edn/read-string (slurp peers-file)) (catch Exception _ {})))

(defn- build-manifest []
  (try (clojure.edn/read-string (slurp (str (System/getProperty "user.dir") "/bin/BUILD.edn")))
       (catch Exception _ nil)))

(defn- ensure-pinned!
  "BUILD.edn (tracked in git) pins the fleet's expected kotoba version; the binary
   itself is distributed out-of-band (built + `murakumo pin`). Refuse to roll out
   if a version is pinned but ./bin is empty — so the fleet always gets the exact
   version the repo declares. Returns the manifest (or nil if none)."
  []
  (let [bm (build-manifest)]
    (when (and bm (not (.exists (java.io.File. (str local-bin "/kotoba-server")))))
      (binding [*out* *err*]
        (println (format "fleet pins kotoba %s (sha %s) but ./bin has no binaries."
                         (:version bm) (:git-sha bm)))
        (println "Build that version and `murakumo pin <its release dir>` before provisioning."))
      (System/exit 2))
    bm))

(defn cmd-provision
  "Push binaries + a resident LaunchDaemon to selected node(s). Idempotent.
   Uses any known PeerIds (.murakumo-peers.edn) for bootstrap. Requires
   MURAKUMO_OPERATOR_SEED."
  [fleet [sel]]
  (when-not (operator-seed fleet)
    (binding [*out* *err*] (println "set MURAKUMO_OPERATOR_SEED (32-byte hex) first")) (System/exit 2))
  (when-let [bm (ensure-pinned!)]
    (println (format "rolling out kotoba %s (sha %s, %s)" (:version bm) (:git-sha bm) (:features bm))))
  (let [tmpl (slurp "deploy/com.murakumo.kotoba-mesh.plist.tmpl")
        fleet (fleet/enrich fleet)
        peers (load-peers)]
    (doseq [n (fleet/select fleet sel)] (provision-node fleet tmpl peers n))))

(defn- node-peer-id
  "Read a node's stable libp2p PeerId from its mesh.log (net_actor logs it as
   `node_did=did:key:<peerid>` on lattice startup)."
  [host]
  (let [out (:out (ssh/sh host "grep -ho 'did:key:12D3[A-Za-z0-9]*' ~/.murakumo/mesh.log 2>/dev/null | tail -1"))]
    (when (str/starts-with? (str out) "did:key:12D3") (subs out 8))))

(defn cmd-mesh
  "Form ONE gossipsub lattice across the fleet (2-pass): provision every node
   with a fixed P2P port + stable PeerId, collect the PeerIds, then re-provision
   with each node's KOTOBA_BOOTSTRAP_PEERS = all the others. After this, nodes
   dial each other over Tailscale and a component placed anywhere can run anywhere."
  [fleet [sel]]
  (when-not (operator-seed fleet)
    (binding [*out* *err*] (println "set MURAKUMO_OPERATOR_SEED first")) (System/exit 2))
  (ensure-pinned!)
  (let [tmpl (slurp "deploy/com.murakumo.kotoba-mesh.plist.tmpl")
        fleet (fleet/enrich fleet)
        nodes (fleet/select fleet sel)]
    (println "── pass 1: provision with fixed P2P port + stable PeerId ──")
    (doseq [n nodes] (provision-node fleet tmpl {} n))
    (println "── waiting for nodes to advertise their PeerId ──")
    (Thread/sleep 8000)
    (let [peers (into {} (for [n nodes
                               :when (and (:ip n) (ssh/reachable? (:host n)))
                               :let [pid (node-peer-id (:host n))]
                               :when pid]
                           [(:name n) pid]))]
      (spit peers-file (pr-str peers))
      (println (format "── collected %d PeerIds → %s ──" (count peers) peers-file))
      (println "── pass 2: re-provision with KOTOBA_BOOTSTRAP_PEERS = the others ──")
      (doseq [n nodes] (provision-node fleet tmpl peers n))
      (println "── lattice forming; check `murakumo status` (PEERS should climb) ──"))))

(defn- launch [host action]
  (ssh/sh host (case action
                 :up   (format "sudo launchctl kickstart -k system/%s" plist-label)
                 :down (format "sudo launchctl bootout system/%s" plist-label))))

(defn cmd-up   [fleet [sel]] (doseq [n (fleet/select fleet sel)] (println (:name n) (:exit (launch (:host n) :up)))))
(defn cmd-down [fleet [sel]] (doseq [n (fleet/select fleet sel)] (println (:name n) (:exit (launch (:host n) :down)))))

(defn- node-links
  "Distinct libp2p peers this node has connected to (from its mesh.log — the real
   lattice connectivity signal; the /health peer_count is the KDHT neighborhood,
   not the live link set)."
  [host]
  (let [out (:out (ssh/sh host "grep 'kotoba-net: peer connected' ~/.murakumo/mesh.log 2>/dev/null | grep -o '12D3[A-Za-z0-9]*' | sort -u | wc -l"))]
    (str/trim (str out))))

(defn cmd-status
  "Fold every node's /health into one view — the management read. LINKS is the
   number of distinct mesh peers the node has connected to (>0 once `mesh` ran)."
  [fleet [sel]]
  (println (format "%-10s %-8s %-12s %-6s %s" "NODE" "HEALTH" "WASM-EXEC" "LINKS" "P2P-PORT"))
  (doseq [n (fleet/select (fleet/enrich fleet) sel)]
    (if-not (and (:online? n) (ssh/reachable? (:host n)))
      (println (format "%-10s %-8s" (:name n) "down"))
      (let [h (ssh/curl-local (:host n) (format "http://localhost:%d/health" (node-port fleet n)))
            j (try (json/parse-string h true) (catch Exception _ nil))
            subs (:subsystems j)]
        (println (format "%-10s %-8s %-12s %-6s %d"
                         (:name n)
                         (if j "ok" "no-resp")
                         (or (:wasm_executor subs) "?")
                         (if j (node-links (:host n)) "-")
                         (node-p2p-port fleet n)))))))

(defn- b64url [^bytes b]
  (-> (.encodeToString (java.util.Base64/getUrlEncoder) b) (str/replace "=" "")))

(defn- op-token
  "Craft the operator Bearer JWT kotoba checks (sub == operator DID; signature is
   not re-verified — the edge is the trust boundary, see kotoba graph_auth)."
  [did]
  (str (b64url (.getBytes "{\"alg\":\"HS256\",\"typ\":\"JWT\"}" "UTF-8")) "."
       (b64url (.getBytes (format "{\"sub\":\"%s\",\"exp\":9999999999}" did) "UTF-8")) "."
       "kotoba-cli-media"))

(defn cmd-deploy
  "Compile a kotoba app manifest (clj→WASM), seed the artifact into the node's
   blockstore, and publish its triggers/routes to that node's lattice — so the
   component is PLACED + its cron fires. Usage: deploy <app.edn> [node]."
  [fleet [manifest sel]]
  (when-not manifest (binding [*out* *err*] (println "usage: deploy <app.edn> [node]")) (System/exit 2))
  (let [node (first (fleet/select fleet (or sel "asher")))
        port (node-port fleet node)
        wit (str kotoba-dir "/crates/kotoba-runtime/wit")
        kotoba (str local-bin "/kotoba")
        token (op-token (did-for (operator-seed fleet)))
        url "http://localhost:18077"
        ;; resolve the manifest's first component source → build the wasm artifact.
        src (->> (slurp manifest) (re-find #":src\s+\"([^\"]+)\"") second)
        srcdir (-> manifest (str/replace #"/[^/]+$" ""))
        wasm "/tmp/murakumo-deploy.wasm"]
    (println (format "deploying %s → %s" manifest (:name node)))
    ;; port-forward the node's kotoba port to localhost:18077.
    (p/sh "bash" "-c" (format "ssh -o BatchMode=yes -fN -L 18077:localhost:%d %s" port (:host node)))
    (Thread/sleep 1500)
    ;; 1) compile clj → WASM (CID matches the deploy plan).
    (p/sh kotoba "component" "build" (str srcdir "/" src) "--wit-dir" wit "-o" wasm)
    ;; 2) seed the artifact into the node's blockstore (operator-authed).
    (let [bp (p/sh kotoba "--url" url "--token" token "block" "put" "--file" wasm)]
      (println "  block put →" (str/trim (str (:out bp)))))
    ;; 3) publish triggers/routes → the lattice places it + fires cron.
    (let [{:keys [out err exit]}
          (p/sh kotoba "app" "deploy" manifest "--wit-dir" wit "--publish" "--url" url)]
      (println (str out err))
      (System/exit (or exit 0)))))

(defn cmd-pin
  "Copy a consistent kotoba binary set into murakumo's own ./bin (gitignored) and
   record its provenance, so the fleet always gets the SAME cli+server the control
   plane drives — independent of the shared kotoba checkout. Usage: pin <src-dir>
   (defaults to the kotoba checkout's release target)."
  [_ [src]]
  (let [src (or src (str kotoba-dir "/target/aarch64-apple-darwin/release"))
        dest (str (System/getProperty "user.dir") "/bin")]
    (.mkdirs (java.io.File. dest))
    (doseq [bin ["kotoba" "kotoba-server"]]
      (let [s (str src "/" bin)]
        (when-not (.exists (java.io.File. s))
          (binding [*out* *err*] (println "missing binary:" s)) (System/exit 2))
        (p/sh "cp" s (str dest "/" bin))))
    (let [sha (str/trim (str (:out (p/sh "git" "-C" src "rev-parse" "--short" "HEAD"))))
          ver (str/trim (str (:out (p/sh (str dest "/kotoba") "--version"))))]
      (spit (str dest "/BUILD.edn")
            (pr-str {:source src :git-sha sha :version ver :features "p2p,realtime-wasm"}))
      (println (format "pinned kotoba + kotoba-server → bin/  (src %s @ %s, %s)" src sha ver)))))

(def ^:private commands
  {"nodes" cmd-nodes "provision" cmd-provision "up" cmd-up "down" cmd-down
   "status" cmd-status "deploy" cmd-deploy "mesh" cmd-mesh "pin" cmd-pin})

(defn -main [& args]
  (let [[cmd & rest] args
        fleet (fleet/load-fleet)]
    (if-let [f (get commands cmd)]
      (f fleet rest)
      (do (println "murakumo — kotoba WASM mesh control plane\n")
          (println "commands:")
          (println "  nodes                       fleet reachability + mesh presence")
          (println "  pin       [src-dir]         copy a consistent kotoba cli+server into ./bin (own it)")
          (println "  provision [node|all]        rsync binaries + install resident LaunchDaemon")
          (println "  mesh      [node|all]        form ONE gossipsub lattice (2-pass: peer-id + bootstrap)")
          (println "  up/down   [node|all]        start/stop the resident mesh node")
          (println "  status    [node|all]        fold /health across the fleet (PEERS = live links)")
          (println "  deploy    <app.edn> [node]  compile clj→WASM + publish to a node's lattice")
          (println "\nenv: MURAKUMO_OPERATOR_SEED (32-byte hex), MURAKUMO_KOTOBA_DIR")))))
