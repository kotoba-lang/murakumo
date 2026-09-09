(ns murakumo.infer.edge
  "Resident launchd plans for the Murakumo edge replica and queue worker.

  ## These are LaunchDaemons, and that is the whole point

  They were LaunchAgents until 2026-09-09. Measured that day on all six
  reachable minis: `who` is empty and /dev/console is owned by root, so no
  interactive session exists, so `~/Library/LaunchAgents` is never loaded and
  `launchctl` from ssh answers `125: Domain does not support specified action`.
  The workers that were running had been started by hand -- 10d21h old on hosts
  that had booted 24 days earlier, a two-week gap in which those nodes served
  nothing and nobody could see it, because a node that is enrolled and dark and
  a node that is enrolled and busy both answer the registry the same way.

  That mattered more than a missing convenience, because the cure for the three
  dark nodes is a reboot: their ephemeral port range is full of TIME_WAIT the
  kernel does not reclaim (benjamin 48,298 of 49,152; the count was unchanged
  45 s after its producer was killed). So the fleet was in a loop -- the repair
  is a reboot, and a reboot is what the supervision could not survive.

  A LaunchDaemon in the system domain loads at boot with no login, which is
  what `murakumo.infer.clj` already does for the RPC worker. `UserName` keeps
  the process running as the node user, because the model, the nbb runtime and
  `join.env` all live under that user's home.

  Ranked first, unanimously, by `murakumo.cluster.cosci`: every admissible
  design in that tournament is `:launch-daemon-system`, and it is the one
  dimension whose answer does not depend on any unmeasured input."
  (:require [kotoba.lang.text :as str]
            [kotodama.inference.edge :as inference-edge]))

(def model-id "murakumo-edge")
(def server-label "com.murakumo.edge-server")
(def join-label "com.murakumo.edge-join")
(def port 8092)

(def daemon-dir "/Library/LaunchDaemons")

(def legacy-agent-dir
  "Where these two jobs lived until 2026-09-09. `install-script` removes the
  file as well as unloading it: leaving it behind means the next machine that
  does get a login session starts a second copy of a job that is already
  running in the system domain, and two edge-join workers on one node claim
  each other's jobs."
  "Library/LaunchAgents")

(defn- xml [value]
  (-> (str value) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- plist
  "A system-domain LaunchDaemon that runs as `user`.

  `UserName` is not decoration: without it launchd runs the job as root, the
  process writes root-owned files into the node user's ~/.murakumo, and the
  next non-root run fails on its own logs."
  [label user argv stdout stderr]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\"><dict>\n"
       "<key>Label</key><string>" (xml label) "</string>\n"
       "<key>UserName</key><string>" (xml user) "</string>\n"
       "<key>ProgramArguments</key><array>"
       (apply str (map #(str "<string>" (xml %) "</string>") argv))
       "</array>\n<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>\n"
       "<key>ThrottleInterval</key><integer>5</integer>\n"
       "<key>StandardOutPath</key><string>" (xml stdout) "</string>\n"
       "<key>StandardErrorPath</key><string>" (xml stderr) "</string>\n"
       "</dict></plist>\n"))

(defn server-plan
  [{:keys [home llama-server memory-bytes]}]
  (let [user (last (str/split home #"/"))
        plan (inference-edge/replica-plan
              {:home home :llama-server llama-server :port port
               :memory-bytes memory-bytes})]
    (when-not (:admitted? plan)
      (throw (ex-info "murakumo-edge does not fit this node" plan)))
    (assoc plan :label server-label
           :home home
           :orphan-pattern "murakumo/models/murakumo-edge"
           :plist (plist server-label user (:argv plan)
                         (str home "/.murakumo/edge/server.log")
                         (str home "/.murakumo/edge/server.err.log")))))

(defn install-script
  "Shell to install one rendered plan as a system LaunchDaemon on a node.

  Same idiom as `murakumo.infer/install-rpc-worker-service!`, plus the removal
  of the legacy LaunchAgent. `bootout` before `bootstrap` so a re-run replaces
  the definition rather than failing on it -- and note that launchd caches the
  definition it loaded, so writing the file is not loading it (the 2026-09-09
  root ADR records a fleet cutover where 57 jobs were rewritten, all reported
  as reloaded, and exactly one was still running its old definition)."
  [{:keys [label plist home orphan-pattern]}]
  (str "sudo -n /usr/bin/tee " daemon-dir "/" label ".plist >/dev/null <<'MURAKUMO_EDGE_PLIST'\n"
       plist
       "MURAKUMO_EDGE_PLIST\n"
       "sudo -n /usr/sbin/chown root:wheel " daemon-dir "/" label ".plist; "
       "sudo -n /bin/chmod 644 " daemon-dir "/" label ".plist; "
       ;; Retire the LaunchAgent that could never load, and any hand-started
       ;; orphan of it, before the daemon claims the port and the queue slot.
       "/bin/launchctl bootout gui/$(id -u)/" label " >/dev/null 2>&1 || true; "
       "rm -f " home "/" legacy-agent-dir "/" label ".plist; "
       ;; The processes actually running on these nodes are NOT launchd jobs --
       ;; they are orphans of a session that ended, so `bootout` does not touch
       ;; them. Without this line the bootstrap below starts a second worker
       ;; beside the first, and two edge-join workers on one node race each
       ;; other for the same queue jobs ("already claimed, trying next" is what
       ;; that looks like in the log, from both of them).
       "pkill -f '" orphan-pattern "' >/dev/null 2>&1 || true; "
       "sudo -n /bin/launchctl bootout system/" label " >/dev/null 2>&1 || true; sleep 1; "
       "sudo -n /bin/launchctl bootstrap system " daemon-dir "/" label ".plist; "
       "sudo -n /bin/launchctl kickstart -k system/" label "; "
       ;; Assert the loaded definition, not the file. `launchctl list` cannot
       ;; tell a stale definition from a fresh one; `print` shows the path the
       ;; running job will actually write to.
       "sudo -n /bin/launchctl print system/" label " | grep -F '" home "' >/dev/null "
       "|| { echo 'MURAKUMO_EDGE_INSTALL_UNVERIFIED " label "' >&2; exit 3; }"))

(defn join-plan
  [{:keys [home nbb node-name]}]
  (let [user (last (str/split home #"/"))
        root (str home "/.murakumo/edge/murakumo")
        command (str "set -a; source " home "/.murakumo/edge/join.env; set +a; exec "
                     nbb " --classpath " root "/src " root
                     "/scripts/infer-join.cljs --model " model-id
                     " --base https://api.murakumo.cloud --name " node-name
                     ;; These are fleet.edn hardware operated by AWAI Network,
                     ;; which is exactly what docs/adr-secure-community-cloud.md
                     ;; calls :awai-secure. The join worker defaults to
                     ;; community on purpose (an unauthenticated provider must
                     ;; never become Secure by omission), so the operator of the
                     ;; hardware has to say so here. Without it the ten resident
                     ;; Ornith minis enrolled Community, workloads asked for
                     ;; Secure, placement requires an exact tier match with no
                     ;; fallback, and murakumo-edge answered 503 with every node
                     ;; live, ready and idle.
                     " --trust-tier awai-secure"
                     " --local-url http://127.0.0.1:" port "/v1 --slots 1 --poll-ms 1000")]
    {:label join-label
     :home home
     :orphan-pattern "murakumo/scripts/infer-join.cljs"
     :plist (plist join-label user ["/bin/zsh" "-lc" command]
                   (str home "/.murakumo/edge/join.log")
                   (str home "/.murakumo/edge/join.err.log"))}))
