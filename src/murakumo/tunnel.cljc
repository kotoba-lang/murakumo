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
;; W6 product-shell + T6.4: pure string/i64 helpers + ssh/scp bin/flag fragments
;; require the shipped `:tunnel` KIR on **every** platform. Host pure mirrors
;; are gone — cljs/nbb must preload shipped KIR (resources/ via nbb cwd,
;; register-kir!, or set-resource-loader!) before requiring this ns
;; (ADR-260731-w6-t64-tunnel-mirror-delete).
;; Host remains: line-split of parse-rc, argv vector assembly, SSH subprocess.

(ns murakumo.tunnel
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :tunnel)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

;; ── constants (oracle SSoT) ────────────────────────────────────────────

(def default-connect-timeout-s
  (oracle/i64->host (o 'default-connect-timeout-s [])))

(def default-control-persist-s
  (oracle/i64->host (o 'default-control-persist-s [])))

(def rc-marker
  (o 'rc-marker []))

(def ssh-bin
  "ssh binary name. Kotoba SSoT (requires oracle)."
  (o 'ssh-bin []))

(def scp-bin
  "scp binary name. Kotoba SSoT (requires oracle)."
  (o 'scp-bin []))

(def o-flag
  "ssh/scp -o flag. Kotoba SSoT (requires oracle)."
  (o 'o-flag []))

(def O-flag
  "ssh -O control flag. Kotoba SSoT (requires oracle)."
  (o 'O-flag []))

(def exit-ctl
  "ssh -O exit control verb. Kotoba SSoT (requires oracle)."
  (o 'exit-ctl []))

(def pgrep-bin
  (o 'pgrep-bin []))
(def pkill-bin
  (o 'pkill-bin []))
(def f-flag
  (o 'f-flag []))
(def fN-flag
  (o 'fN-flag []))
(def L-flag
  (o 'L-flag []))
(def null-redirect
  (o 'null-redirect []))
(def or-sep
  (o 'or-sep []))
(def settle-sleep
  (o 'settle-sleep []))
(def pkill-suffix
  (o 'pkill-suffix []))
(def localhost-colon
  (o 'localhost-colon []))
(def curl-prefix
  (o 'curl-prefix []))
(def curl-stderr-redirect
  (o 'curl-stderr-redirect []))

(defn- batch-mode-opt []
  (o 'batch-mode-opt []))

(defn- strict-host-key-opt []
  (o 'strict-host-key-opt []))

(defn- control-master-opt []
  (o 'control-master-opt []))

(defn- connect-timeout-opt [seconds]
  (o 'connect-timeout-opt [(oracle/as-i64 seconds)]))

(defn- control-path-opt [path]
  (o 'control-path-opt [(str path)]))

(defn- control-persist-opt [seconds]
  (o 'control-persist-opt [(oracle/as-i64 seconds)]))

(def ssh-opts
  [o-flag (batch-mode-opt)
   o-flag (connect-timeout-opt default-connect-timeout-s)
   o-flag (strict-host-key-opt)])

(defn conn-opts
  "SSH -o flags for a connection. `:control-path` turns on multiplexing: the
   first connection to a host becomes the master, later ones reuse its socket.
   Opt fragments + o-flag via kotoba (required)."
  [{:keys [connect-timeout-s control-path control-persist-s]}]
  (vec (concat [o-flag (batch-mode-opt)
                o-flag (connect-timeout-opt
                        (or connect-timeout-s default-connect-timeout-s))
                o-flag (strict-host-key-opt)]
               (when control-path
                 [o-flag (control-master-opt)
                  o-flag (control-path-opt control-path)
                  o-flag (control-persist-opt
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
   Kotoba `wrap-cmd` (required)."
  [cmd]
  (o 'wrap-cmd [(str cmd)]))

(defn parse-rc
  "Split captured stdout into [clean-stdout rc-or-nil]. rc is nil when the
   sentinel never arrived (connection failure, kill, non-shell remote), in which
   case the caller falls back to ssh's own exit code — which IS trustworthy for
   ssh-level failures (255) even where the remote status is not.
   Marker/digits classification via kotoba (required); line-split stays host."
  [out]
  (let [lines (str/split-lines (str out))
        marker? (fn [l]
                  (let [t (str/trim (str l))]
                    (oracle/bool->host (o 'marker-prefix? [t]))))
        rc-line (last (filter marker? lines))
        digits (when rc-line
                 (let [t (str/trim (str rc-line))]
                   (o 'strip-marker-digits [t])))
        rc (when digits
             (let [v (oracle/i64->host (o 'parse-digits [(str digits)]))]
               (when-not (neg? v) v)))]
    [(str/trim (str/join "\n" (remove marker? lines))) rc]))

;; --- argv shapes ------------------------------------------------------------

(defn ssh-argv
  "argv for running a remote shell command over SSH.

   The command is wrapped for in-band exit reporting unless `:wrap? false` is
   passed (use that only for argv that is not a remote shell command).
   Bin name via `ssh-bin`."
  ([host cmd] (ssh-argv host cmd nil))
  ([host cmd opts]
   (vec (concat [ssh-bin] (conn-opts opts)
                [host (if (false? (:wrap? opts)) cmd (wrap-cmd cmd))]))))

(defn scp-argv
  "argv for copying a local file to host:dest. scp runs no remote shell, so it
   is NOT wrapped — scp's own exit status is the client's, which this fleet does
   propagate correctly. Bin via `scp-bin`."
  ([host local dest] (scp-argv host local dest nil))
  ([host local dest opts]
   (vec (concat [scp-bin] (conn-opts opts)
                [local (o 'scp-dest [(str host) (str dest)])]))))

(defn close-master-argv
  "argv that shuts down a multiplexed connection's master socket. Run this when
   a batch finishes instead of leaving masters to expire on ControlPersist.
   Bin/flags via ssh-bin / o-flag / O-flag / exit-ctl."
  [host control-path]
  [ssh-bin o-flag (o 'close-master-control-opt [(str control-path)])
   O-flag exit-ctl host])

(defn ensure-forward-command
  "Shell command that starts an SSH local forward only when an equivalent one is absent.
   Kotoba `ensure-forward-command` (required)."
  [local-port remote-port host]
  (o 'ensure-forward-command
     [(oracle/as-i64 local-port) (oracle/as-i64 remote-port) (str host)]))

(defn replace-forward-command
  "Shell command that kills any forward on local-port, then starts a fresh one.
   Kotoba `replace-forward-command` (required)."
  [local-port remote-port host]
  (o 'replace-forward-command
     [(oracle/as-i64 local-port) (oracle/as-i64 remote-port) (str host)]))

(defn remote-curl-command
  "Remote shell command for a bounded curl call from a node.
   Kotoba `remote-curl-command` (required)."
  [url]
  (o 'remote-curl-command [(str url)]))

;; --- result shapes ----------------------------------------------------------

(defn- pick-exit
  "Prefer in-band rc when present. Kotoba `pick-exit` (required).
   has-rc remains 0/1 i64 projection (numeric presence for pick-exit ABI)."
  [rc ssh-exit]
  (oracle/i64->host
   (o 'pick-exit
      [(oracle/as-i64 (if (some? rc) 1 0))
       (oracle/as-i64 (or rc 0))
       (oracle/as-i64 (or ssh-exit 0))])))

(defn- trim-err
  "Trim stderr. Kotoba `trim-err` (required)."
  [err]
  (o 'trim-err [(str (or err ""))]))

(defn sh-result
  "Normalise process output from an SSH command.

   `:exit` is the REMOTE command's status (from the in-band sentinel) whenever
   it is available, falling back to ssh's own code; `:ssh-exit` keeps what the
   ssh client reported, so a caller can still tell a transport failure from a
   remote non-zero exit. Exit pick + err trim via kotoba (required)."
  [{:keys [exit out err]}]
  (let [[clean rc] (parse-rc out)]
    {:exit (pick-exit rc exit)
     :ssh-exit exit
     :out clean
     :err (trim-err err)}))

(defn scp-result
  "Normalise process output from an SCP command.
   Err trim via kotoba (required)."
  [{:keys [exit err]}]
  {:exit exit
   :err (trim-err err)})
