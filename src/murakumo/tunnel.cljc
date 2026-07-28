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
;; W6 product-shell authority (ADR-260728-w6-tunnel-config-oracle-authority):
;; On the JVM, pure string/i64 helpers DELEGATE to precompiled
;; kotoba/tunnel_core.kotoba → resources/murakumo/oracle/tunnel_core.kir.edn.
;; Host remains: line-split of parse-rc, argv vector assembly, SSH subprocess.

(ns murakumo.tunnel
  (:require [clojure.string :as str]
            #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :tunnel)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(def default-connect-timeout-s
  #?(:clj (long (o 'default-connect-timeout-s []))
     :cljs 8))

(def default-control-persist-s
  #?(:clj (long (o 'default-control-persist-s []))
     :cljs 30))

(def rc-marker
  #?(:clj (o 'rc-marker [])
     :cljs "__murakumo_rc="))

(def ssh-opts
  #?(:clj ["-o" (o 'batch-mode-opt [])
           "-o" (o 'connect-timeout-opt [(long default-connect-timeout-s)])
           "-o" (o 'strict-host-key-opt [])]
     :cljs ["-o" "BatchMode=yes"
            "-o" (str "ConnectTimeout=" default-connect-timeout-s)
            "-o" "StrictHostKeyChecking=accept-new"]))

(defn conn-opts
  "SSH -o flags for a connection. `:control-path` turns on multiplexing: the
   first connection to a host becomes the master, later ones reuse its socket."
  [{:keys [connect-timeout-s control-path control-persist-s]}]
  #?(:clj
     (vec (concat ["-o" (o 'batch-mode-opt [])
                   "-o" (o 'connect-timeout-opt
                           [(long (or connect-timeout-s default-connect-timeout-s))])
                   "-o" (o 'strict-host-key-opt [])]
                  (when control-path
                    ["-o" (o 'control-master-opt [])
                     "-o" (o 'control-path-opt [(str control-path)])
                     "-o" (o 'control-persist-opt
                             [(long (or control-persist-s default-control-persist-s))])])))
     :cljs
     (vec (concat ["-o" "BatchMode=yes"
                   "-o" (str "ConnectTimeout=" (or connect-timeout-s default-connect-timeout-s))
                   "-o" "StrictHostKeyChecking=accept-new"]
                  (when control-path
                    ["-o" "ControlMaster=auto"
                     "-o" (str "ControlPath=" control-path)
                     "-o" (str "ControlPersist=" (or control-persist-s default-control-persist-s) "s")])))))

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
   `parse-rc` extracts and strips."
  [cmd]
  #?(:clj (o 'wrap-cmd [(str cmd)])
     :cljs (str "( " cmd "\n); __mrc=$?; echo \"" rc-marker "$__mrc\"")))

(defn parse-rc
  "Split captured stdout into [clean-stdout rc-or-nil]. rc is nil when the
   sentinel never arrived (connection failure, kill, non-shell remote), in which
   case the caller falls back to ssh's own exit code — which IS trustworthy for
   ssh-level failures (255) even where the remote status is not."
  [out]
  (let [lines (str/split-lines (str out))
        marker? (fn [l]
                  #?(:clj (= 1 (o 'marker-prefix? [(str/trim (str l))]))
                     :cljs (str/starts-with? (str/trim l) rc-marker)))
        rc-line (last (filter marker? lines))
        digits (when rc-line
                 #?(:clj (o 'strip-marker-digits [(str/trim (str rc-line))])
                    :cljs (str/replace (str/trim rc-line) rc-marker "")))
        rc #?(:clj (when digits
                     (let [n (long (o 'parse-digits [(str digits)]))]
                       (when-not (neg? n) n)))
              :cljs (when digits
                      (let [n (js/parseInt digits 10)]
                        (when-not (js/isNaN n) n))))]
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
                [local #?(:clj (o 'scp-dest [(str host) (str dest)])
                          :cljs (str host ":" dest))]))))

(defn close-master-argv
  "argv that shuts down a multiplexed connection's master socket. Run this when
   a batch finishes instead of leaving masters to expire on ControlPersist."
  [host control-path]
  ["ssh" "-o" #?(:clj (o 'close-master-control-opt [(str control-path)])
                 :cljs (str "ControlPath=" control-path))
   "-O" "exit" host])

(defn ensure-forward-command
  "Shell command that starts an SSH local forward only when an equivalent one is absent."
  [local-port remote-port host]
  #?(:clj (o 'ensure-forward-command
             [(long local-port) (long remote-port) (str host)])
     :cljs (str "pgrep -f '" local-port ":localhost:" remote-port " " host "' >/dev/null 2>&1"
                " || ssh -o BatchMode=yes -fN -L " local-port ":localhost:" remote-port " " host)))

(defn replace-forward-command
  "Shell command that kills any forward on local-port, then starts a fresh one."
  [local-port remote-port host]
  #?(:clj (o 'replace-forward-command
             [(long local-port) (long remote-port) (str host)])
     :cljs (str "pkill -f '" local-port ":localhost' 2>/dev/null; sleep 0.3; "
                "ssh -o BatchMode=yes -fN -L " local-port ":localhost:" remote-port " " host)))

(defn remote-curl-command
  "Remote shell command for a bounded curl call from a node."
  [url]
  #?(:clj (o 'remote-curl-command [(str url)])
     :cljs (str "curl -s -m 5 " url " 2>/dev/null")))

;; --- result shapes ----------------------------------------------------------

(defn sh-result
  "Normalise process output from an SSH command.

   `:exit` is the REMOTE command's status (from the in-band sentinel) whenever
   it is available, falling back to ssh's own code; `:ssh-exit` keeps what the
   ssh client reported, so a caller can still tell a transport failure from a
   remote non-zero exit."
  [{:keys [exit out err]}]
  (let [[clean rc] (parse-rc out)]
    {:exit (if (some? rc) rc exit)
     :ssh-exit exit
     :out clean
     :err (str/trim (str err))}))

(defn scp-result
  "Normalise process output from an SCP command."
  [{:keys [exit err]}]
  {:exit exit
   :err (str/trim (str err))})
