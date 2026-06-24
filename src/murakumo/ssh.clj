;; murakumo.ssh — thin Tailscale-SSH transport for fleet operations.
;;
;; Every remote action funnels through here so the control plane has ONE place
;; for connection options (BatchMode = non-interactive, short ConnectTimeout so a
;; dead node fails fast instead of hanging the whole fleet sweep).

(ns murakumo.ssh
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private ssh-opts
  ["-o" "BatchMode=yes" "-o" "ConnectTimeout=8" "-o" "StrictHostKeyChecking=accept-new"])

(defn sh
  "Run `cmd` (a shell string) on `host` over SSH. Returns {:exit :out :err}.
   Never throws on a non-zero remote exit — the caller inspects :exit."
  [host cmd]
  (let [{:keys [exit out err]}
        (apply p/sh (concat ["ssh"] ssh-opts [host cmd]))]
    {:exit exit :out (str/trim (str out)) :err (str/trim (str err))}))

(defn reachable?
  "True if `host` answers SSH within the connect timeout."
  [host]
  (zero? (:exit (sh host "true"))))

(defn scp
  "Copy a local file to `host:dest`. Returns {:exit :err}."
  [host local dest]
  (let [{:keys [exit err]}
        (apply p/sh (concat ["scp"] ssh-opts [local (str host ":" dest)]))]
    {:exit exit :err (str/trim (str err))}))

(defn curl-local
  "Run a curl on the node against its OWN loopback (so we read node-local state
   without exposing ports). Returns the body string (empty on failure)."
  [host url]
  (:out (sh host (format "curl -s -m 5 %s 2>/dev/null" url))))
