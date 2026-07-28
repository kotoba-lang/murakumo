;; murakumo.tunnel — THE transport contract for every fleet operation.
;;
;; Portable (.cljc): the bb/JVM control plane (murakumo.ssh) and the nbb task
;; plane (murakumo.task.exec) must speak the SAME ssh dialect, or a defect fixed
;; in one keeps biting in the other. Three things live here:
;;
;;   1. connection options — non-interactive, fail-fast, host-key accept-new
;;   2. IN-BAND EXIT STATUS — see `wrap-cmd`; the one defect that silently
;;      corrupts every fleet decision if it is not fixed at this layer
;;   3. connection multiplexing — optional ControlMaster reuse, which removes
;;      the TCP+auth handshake from every command after the first
;;
;; W6 product-shell authority (ADR-260728-w6-tunnel-config-oracle-authority +
;; ADR-260728-w6-tunnel-result-pure-oracle):
;; pure string/i64 helpers DELEGATE to precompiled
;; kotoba/tunnel_core.kotoba → resources/murakumo/oracle/tunnel_core.kir.edn
;; when oracle is loadable (JVM classpath or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
;; Host remains: line-split of parse-rc, argv vector assembly, SSH subprocess.
;; sh-result exit pick + err trim dual-source. cljs mirrors remain fallback.

(ns murakumo.tunnel
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :tunnel)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "Run oracle body; on failure (e.g. cljs KIR substring bounds) use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

;; ── host-mirror pure helpers ───────────────────────────────────────────

(def ^:private mirror-default-connect-timeout-s 8)
(def ^:private mirror-default-control-persist-s 30)
(def ^:private mirror-rc-marker "__murakumo_rc=")

(defn- mirror-batch-mode-opt [] "BatchMode=yes")
(defn- mirror-strict-host-key-opt [] "StrictHostKeyChecking=accept-new")
(defn- mirror-control-master-opt [] "ControlMaster=auto")

(defn- mirror-connect-timeout-opt [seconds]
  (str "ConnectTimeout=" seconds))

(defn- mirror-control-path-opt [path]
  (str "ControlPath=" path))

(defn- mirror-control-persist-opt [seconds]
  (str "ControlPersist=" seconds "s"))

(defn- mirror-wrap-cmd [cmd]
  (str "( " cmd "\n); __mrc=$?; echo \"" mirror-rc-marker "$__mrc\""))

(defn- mirror-marker-prefix? [line]
  (str/starts-with? (str/trim line) mirror-rc-marker))

(defn- mirror-strip-marker-digits [line]
  (str/replace (str/trim line) mirror-rc-marker ""))

(defn- mirror-parse-digits [digits]
  (try
    #?(:clj (Long/parseLong (str digits))
       :cljs (let [n (js/parseInt digits 10)]
               (when-not (js/isNaN n) n)))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- mirror-scp-dest [host dest]
  (str host ":" dest))

(defn- mirror-close-master-control-opt [control-path]
  (str "ControlPath=" control-path))

(defn- mirror-ensure-forward-command [local-port remote-port host]
  (str "pgrep -f '" local-port ":localhost:" remote-port " " host "' >/dev/null 2>&1"
       " || ssh -o BatchMode=yes -fN -L " local-port ":localhost:" remote-port " " host))

(defn- mirror-replace-forward-command [local-port remote-port host]
  (str "pkill -f '" local-port ":localhost' 2>/dev/null; sleep 0.3; "
       "ssh -o BatchMode=yes -fN -L " local-port ":localhost:" remote-port " " host))

(defn- mirror-remote-curl-command [url]
  (str "curl -s -m 5 " url " 2>/dev/null"))

;; ── dual-source defaults ───────────────────────────────────────────────

(def default-connect-timeout-s
  (try-oracle
   #(oracle/i64->host (o 'default-connect-timeout-s []))
   (fn [] mirror-default-connect-timeout-s)))

(def default-control-persist-s
  (try-oracle
   #(oracle/i64->host (o 'default-control-persist-s []))
   (fn [] mirror-default-control-persist-s)))

(def rc-marker
  (try-oracle
   #(o 'rc-marker [])
   (fn [] mirror-rc-marker)))

(defn- batch-mode-opt []
  (try-oracle #(o 'batch-mode-opt []) mirror-batch-mode-opt))

(defn- strict-host-key-opt []
  (try-oracle #(o 'strict-host-key-opt []) mirror-strict-host-key-opt))

(defn- control-master-opt []
  (try-oracle #(o 'control-master-opt []) mirror-control-master-opt))

(defn- connect-timeout-opt [seconds]
  (try-oracle
   #(o 'connect-timeout-opt [(oracle/as-i64 seconds)])
   #(mirror-connect-timeout-opt seconds)))

(defn- control-path-opt [path]
  (try-oracle
   #(o 'control-path-opt [(str path)])
   #(mirror-control-path-opt path)))

(defn- control-persist-opt [seconds]
  (try-oracle
   #(o 'control-persist-opt [(oracle/as-i64 seconds)])
   #(mirror-control-persist-opt seconds)))

(def ssh-opts
  ["-o" (batch-mode-opt)
   "-o" (connect-timeout-opt default-connect-timeout-s)
   "-o" (strict-host-key-opt)])

(defn conn-opts
  "SSH -o flags for a connection. `:control-path` turns on multiplexing: the
   first connection to a host becomes the master, later ones reuse its socket.
   Opt fragments via kotoba when oracle ready."
  [{:keys [connect-timeout-s control-path control-persist-s]}]
  (vec (concat ["-o" (batch-mode-opt)
                "-o" (connect-timeout-opt
                      (or connect-timeout-s default-connect-timeout-s))
                "-o" (strict-host-key-opt)]
               (when control-path
                 ["-o" (control-master-opt)
                  "-o" (control-path-opt control-path)
                  "-o" (control-persist-opt
                        (or control-persist-s default-control-persist-s))]))))

;; --- in-band exit status ----------------------------------------------------

(defn wrap-cmd
  "Wrap a remote command so it reports its OWN exit status in-band.

   MEASURED 2026-07-25 (ADR-2607256000): Tailscale SSH on the macOS fleet nodes
   does NOT propagate the remote exit status — `ssh asher 'exit 7'` and
   `ssh asher false` both return 0, while the Linux node (gad) correctly returns
   7 and 1. Every fleet decision made on `(:exit (ssh/sh …))` — provision
   success, launchctl load, `rm -rf` in the model GC, engine probes — therefore
   reads as SUCCESS on 10 of the 11 reachable nodes, whatever actually happened.

   The command runs in a subshell (so a bare `exit N` inside it does not kill
   the reporting shell) and the true status is echoed as a sentinel line that
   `parse-rc` extracts and strips.
   Kotoba `wrap-cmd` when oracle ready."
  [cmd]
  (try-oracle
   #(o 'wrap-cmd [(str cmd)])
   #(mirror-wrap-cmd cmd)))

(defn parse-rc
  "Split captured stdout into [clean-stdout rc-or-nil]. rc is nil when the
   sentinel never arrived (connection failure, kill, non-shell remote), in which
   case the caller falls back to ssh's own exit code — which IS trustworthy for
   ssh-level failures (255) even where the remote status is not.
   Marker/digits classification via kotoba when oracle ready."
  [out]
  (let [lines (str/split-lines (str out))
        marker? (fn [l]
                  (let [t (str/trim (str l))]
                    (try-oracle
                     #(= 1 (oracle/i64->host (o 'marker-prefix? [t])))
                     #(mirror-marker-prefix? t))))
        rc-line (last (filter marker? lines))
        digits (when rc-line
                 (let [t (str/trim (str rc-line))]
                   (try-oracle
                    #(o 'strip-marker-digits [t])
                    #(mirror-strip-marker-digits t))))
        rc (when digits
             (let [n (try-oracle
                      #(let [v (oracle/i64->host (o 'parse-digits [(str digits)]))]
                         (when-not (neg? v) v))
                      #(mirror-parse-digits digits))]
               n))]
    [(str/trim (str/join "\n" (remove marker? lines))) rc]))

;; --- argv shapes ------------------------------------------------------------

(defn ssh-argv
  "argv for running a remote shell command over SSH.

   The command is wrapped for in-band exit reporting unless `:wrap? false` is
   passed (use that only for argv that is not a remote shell command)."
  ([host cmd] (ssh-argv host cmd nil))
  ([host cmd opts]
   (vec (concat ["ssh"] (conn-opts opts)
                [host (if (false? (:wrap? opts)) cmd (wrap-cmd cmd))]))))

(defn scp-argv
  "argv for copying a local file to host:dest. scp runs no remote shell, so it
   is NOT wrapped — scp's own exit status is the client's, which this fleet does
   propagate correctly."
  ([host local dest] (scp-argv host local dest nil))
  ([host local dest opts]
   (vec (concat ["scp"] (conn-opts opts)
                [local (try-oracle
                        #(o 'scp-dest [(str host) (str dest)])
                        #(mirror-scp-dest host dest))]))))

(defn close-master-argv
  "argv that shuts down a multiplexed connection's master socket. Run this when
   a batch finishes instead of leaving masters to expire on ControlPersist."
  [host control-path]
  ["ssh" "-o" (try-oracle
               #(o 'close-master-control-opt [(str control-path)])
               #(mirror-close-master-control-opt control-path))
   "-O" "exit" host])

(defn ensure-forward-command
  "Shell command that starts an SSH local forward only when an equivalent one is absent.
   Kotoba `ensure-forward-command` when oracle ready."
  [local-port remote-port host]
  (try-oracle
   #(o 'ensure-forward-command
       [(oracle/as-i64 local-port) (oracle/as-i64 remote-port) (str host)])
   #(mirror-ensure-forward-command local-port remote-port host)))

(defn replace-forward-command
  "Shell command that kills any forward on local-port, then starts a fresh one.
   Kotoba `replace-forward-command` when oracle ready."
  [local-port remote-port host]
  (try-oracle
   #(o 'replace-forward-command
       [(oracle/as-i64 local-port) (oracle/as-i64 remote-port) (str host)])
   #(mirror-replace-forward-command local-port remote-port host)))

(defn remote-curl-command
  "Remote shell command for a bounded curl call from a node.
   Kotoba `remote-curl-command` when oracle ready."
  [url]
  (try-oracle
   #(o 'remote-curl-command [(str url)])
   #(mirror-remote-curl-command url)))

;; --- result shapes ----------------------------------------------------------

(defn- pick-exit
  "Prefer in-band rc when present. Kotoba `pick-exit` when ready."
  [rc ssh-exit]
  (try-oracle
   #(oracle/i64->host
     (o 'pick-exit
        [(oracle/as-i64 (if (some? rc) 1 0))
         (oracle/as-i64 (or rc 0))
         (oracle/as-i64 (or ssh-exit 0))]))
   #(if (some? rc) rc ssh-exit)))

(defn- trim-err
  "Trim stderr. Kotoba `trim-err` when ready."
  [err]
  (try-oracle
   #(o 'trim-err [(str (or err ""))])
   #(str/trim (str err))))

(defn sh-result
  "Normalise process output from an SSH command.

   `:exit` is the REMOTE command's status (from the in-band sentinel) whenever
   it is available, falling back to ssh's own code; `:ssh-exit` keeps what the
   ssh client reported, so a caller can still tell a transport failure from a
   remote non-zero exit. Exit pick + err trim via kotoba when ready."
  [{:keys [exit out err]}]
  (let [[clean rc] (parse-rc out)]
    {:exit (pick-exit rc exit)
     :ssh-exit exit
     :out clean
     :err (trim-err err)}))

(defn scp-result
  "Normalise process output from an SCP command.
   Err trim via kotoba when ready."
  [{:keys [exit err]}]
  {:exit exit
   :err (trim-err err)})
