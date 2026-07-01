;; murakumo.tunnel — portable SSH local-forward command/result shapes.

(ns murakumo.tunnel
  (:require [clojure.string :as str]))

(def ssh-opts
  ["-o" "BatchMode=yes" "-o" "ConnectTimeout=8" "-o" "StrictHostKeyChecking=accept-new"])

(defn ssh-argv
  "argv for running a remote shell command over SSH."
  [host cmd]
  (vec (concat ["ssh"] ssh-opts [host cmd])))

(defn scp-argv
  "argv for copying a local file to host:dest."
  [host local dest]
  (vec (concat ["scp"] ssh-opts [local (str host ":" dest)])))

(defn ensure-forward-command
  "Shell command that starts an SSH local forward only when an equivalent one is absent."
  [local-port remote-port host]
  (format "pgrep -f '%d:localhost:%d %s' >/dev/null 2>&1 || ssh -o BatchMode=yes -fN -L %d:localhost:%d %s"
          local-port remote-port host local-port remote-port host))

(defn replace-forward-command
  "Shell command that kills any forward on local-port, then starts a fresh one."
  [local-port remote-port host]
  (format "pkill -f '%d:localhost' 2>/dev/null; sleep 0.3; ssh -o BatchMode=yes -fN -L %d:localhost:%d %s"
          local-port local-port remote-port host))

(defn remote-curl-command
  "Remote shell command for a bounded curl call from a node."
  [url]
  (format "curl -s -m 5 %s 2>/dev/null" url))

(defn sh-result
  "Normalise process output from an SSH command."
  [{:keys [exit out err]}]
  {:exit exit
   :out (str/trim (str out))
   :err (str/trim (str err))})

(defn scp-result
  "Normalise process output from an SCP command."
  [{:keys [exit err]}]
  {:exit exit
   :err (str/trim (str err))})
