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
            [murakumo.connect :as connect]
            [murakumo.fleet :as fleet]
            [murakumo.reconcile :as reconcile]
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

(defn- node-webrtc-port
  "The /webrtc-direct UDP port for a node whose connect.edn class speaks :webrtc on
   the Live plane, or nil (→ empty KOTOBA_WEBRTC = off). Offset +100 from the p2p
   port so it never clashes with the QUIC port. Requires a binary built with the
   `webrtc` feature for the env to actually bind (see connect.edn / bin/BUILD.edn)."
  [fleet n]
  (let [conn (connect/load-connect)]
    (when (and conn (some #{:webrtc}
                          (set (connect/class-transports conn (connect/node-class conn n) :live))))
      (+ 100 (node-p2p-port fleet n)))))
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
                      (str/replace "{{BOOTSTRAP}}" (bootstrap-str fleet peers n))
                      (str/replace "{{WEBRTC}}" (str (node-webrtc-port fleet n))))]
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
                 ;; bootstrap if `down` (bootout) unloaded it, else just (re)kickstart.
                 :up   (format "sudo launchctl bootstrap system /Library/LaunchDaemons/%s.plist 2>/dev/null; sudo launchctl kickstart -k system/%s" plist-label plist-label)
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

(defn- pinned-wit []
  (let [b (str (System/getProperty "user.dir") "/bin/wit")]
    (if (.exists (java.io.File. b)) b (str kotoba-dir "/crates/kotoba-runtime/wit"))))

(defn- fwd [host port lport]
  (p/sh "bash" "-c" (format "pkill -f '%d:localhost' 2>/dev/null; sleep 0.3; ssh -o BatchMode=yes -fN -L %d:localhost:%d %s" lport lport port host))
  (Thread/sleep 1300))

(defn- distribute-artifact
  "Block-put the WASM artifact to EVERY reachable node so the lattice can place
   the component anywhere and the winner always has the bytes (no IPFS/bitswap
   dependency). Operator-authed."
  [fleet token wasm nodes]
  (print "  distributing artifact →") (flush)
  (doseq [n nodes]
    (when (ssh/reachable? (:host n))
      (fwd (:host n) (node-port fleet n) 18900)
      (let [r (p/sh (str local-bin "/kotoba") "--url" "http://localhost:18900"
                    "--token" token "block" "put" "--file" wasm)]
        (print (format " %s%s" (:name n) (if (zero? (:exit r)) "✓" "✗"))) (flush))))
  (p/sh "bash" "-c" "pkill -f '18900:localhost' 2>/dev/null")
  (println))

(defn- placed-on
  "Which nodes have executed the component CID (from their logs) — i.e. where the
   lattice actually placed + ran it."
  [fleet cid nodes]
  (keep (fn [n]
          (let [c (:out (ssh/sh (:host n)
                                (format "grep -c 'trigger: executed.*%s' ~/.murakumo/mesh.log 2>/dev/null" cid)))]
            (when (pos? (Integer/parseInt (str/trim (str c)))) (:name n))))
        nodes))

(defn cmd-deploy
  "Deploy a kotoba app manifest to the FLEET: resolve its component CID, distribute
   the artifact to every node, publish its triggers/routes to one node's lattice,
   and report which node the lattice PLACED it on (honouring the manifest's
   :placement constraints over node labels). Usage: deploy <app.edn> [publish-node]."
  [fleet [manifest pubsel]]
  (when-not manifest (binding [*out* *err*] (println "usage: deploy <app.edn> [publish-node]")) (System/exit 2))
  (when-not (operator-seed fleet) (binding [*out* *err*] (println "set MURAKUMO_OPERATOR_SEED first")) (System/exit 2))
  (let [fleet (fleet/enrich fleet)
        kotoba (str local-bin "/kotoba")
        wit (pinned-wit)
        token (op-token (did-for (operator-seed fleet)))
        man (slurp manifest)
        srcdir (str/replace manifest #"/[^/]+$" "")
        src (some-> (re-find #":src\s+\"([^\"]+)\"" man) second)
        explicit-cid (some-> (re-find #":cid\s+\"([^\"]+)\"" man) second)
        wasm "/tmp/murakumo-deploy.wasm"
        ;; compile :src → wasm+CID, or use an explicit :cid (artifact must already be on nodes).
        cid (if src
              ;; `component build` writes the wasm AND prints its CID (last line).
              (let [o (:out (p/sh kotoba "component" "build" (str srcdir "/" src) "--wit-dir" wit "-o" wasm))]
                (last (str/split-lines (str/trim (str o)))))
              explicit-cid)
        pub (first (fleet/select fleet (or pubsel "asher")))]
    (println (format "deploy %s  (component %s)" manifest cid))
    (when src (distribute-artifact fleet token wasm (:nodes fleet)))
    ;; publish PutApp+PutRoutes from one node → gossiped fleet-wide → lattice auctions + places.
    (fwd (:host pub) (node-port fleet pub) 18077)
    (let [{:keys [out err]} (p/sh kotoba "app" "deploy" manifest "--wit-dir" wit "--publish" "--url" "http://localhost:18077")]
      (println (str (str/trim (str out)) (str err))))
    (p/sh "bash" "-c" "pkill -f '18077:localhost' 2>/dev/null")
    ;; let the auction close + first trigger fire, then report placement.
    (println "  waiting for the lattice to place + run it…")
    (Thread/sleep 75000)
    (let [where (placed-on fleet cid (:nodes fleet))]
      (if (seq where)
        (println (format "  ✓ placed + running on: %s  (deployed from %s)" (str/join ", " where) (:name pub)))
        (println "  ⚠ not yet observed running on any node (check `murakumo status` / node logs)")))))

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
    ;; pin the WIT alongside the binary (a release dir is target/<triple>/release,
    ;; so its WIT is ../../../crates/kotoba-runtime/wit) — deploy compiles against
    ;; the SAME WIT version the binary expects (avoids world-name skew).
    (let [wit (str src "/../../../crates/kotoba-runtime/wit")]
      (when (.exists (java.io.File. wit))
        (p/sh "rm" "-rf" (str dest "/wit"))
        (p/sh "cp" "-R" wit (str dest "/wit"))))
    (let [sha (str/trim (str (:out (p/sh "git" "-C" src "rev-parse" "--short" "HEAD"))))
          ver (str/trim (str (:out (p/sh (str dest "/kotoba") "--version"))))]
      (spit (str dest "/BUILD.edn")
            ;; webrtc: the fleet's browser-Live transport (connect.edn :native :live ⊇
            ;; :webrtc). Build kotoba with `--features p2p,realtime-wasm,webrtc` so the
            ;; KOTOBA_WEBRTC /webrtc-direct listen actually binds.
            (pr-str {:source src :git-sha sha :version ver :features "p2p,realtime-wasm,webrtc"}))
      (println (format "pinned kotoba + kotoba-server → bin/  (src %s @ %s, %s)" src sha ver)))))

(defn cmd-dash
  "Web dashboard + Datom-log snapshotter. Args: [port=8899] [interval-s=15]."
  [_ args]
  (require 'murakumo.dash)
  (apply (resolve 'murakumo.dash/-main) args))

(defn cmd-reconcile
  "Declarative fleet reconcile (murakumo's wadm). Folds a desired-state manifest
   (murakumo.app.edn) against live placement and reports/converges the drift.
   Placement is delegated back to `deploy` (publish → auction places on eligible
   nodes). See murakumo.reconcile."
  [fleet args]
  (let [deploy-fn (fn [a manifest-dir]
                    (cmd-deploy fleet [(str manifest-dir "/" (:manifest a)) nil]))]
    (reconcile/cmd-reconcile fleet (cons "reconcile" args) deploy-fn)))

(def ^:private commands
  {"nodes" cmd-nodes "provision" cmd-provision "up" cmd-up "down" cmd-down
   "status" cmd-status "deploy" cmd-deploy "mesh" cmd-mesh "pin" cmd-pin
   "dash" cmd-dash "reconcile" cmd-reconcile})

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
          (println "  deploy    <app.edn> [node]  compile clj→WASM + distribute + publish to the lattice")
          (println "  reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]]  declarative desired-state (wadm)")
          (println "  dash      [port] [interval]  web dashboard + persist heartbeat/placement to the Datom log")
          (println "\nenv: MURAKUMO_OPERATOR_SEED (32-byte hex), MURAKUMO_KOTOBA_DIR")))))
