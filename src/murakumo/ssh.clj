;; murakumo.ssh — thin Tailscale-SSH transport for fleet operations.
;;
;; Every remote action funnels through here so the control plane has ONE place
;; for connection options (BatchMode = non-interactive, short ConnectTimeout so a
;; dead node fails fast instead of hanging the whole fleet sweep).

(ns murakumo.ssh
  (:require [babashka.process :as p]
            [murakumo.tunnel :as tunnel]))

(defn sh
  "Run `cmd` (a shell string) on `host` over SSH. Returns {:exit :out :err}.
   Never throws on a non-zero remote exit — the caller inspects :exit."
  [host cmd]
  (tunnel/sh-result
   (apply p/sh (tunnel/ssh-argv host cmd))))

(defn reachable?
  "True if `host` answers SSH within the connect timeout."
  [host]
  (zero? (:exit (sh host "true"))))

(defn scp
  "Copy a local file to `host:dest`. Returns {:exit :err}."
  [host local dest]
  (tunnel/scp-result
   (apply p/sh (tunnel/scp-argv host local dest))))

(defn curl-local
  "Run a curl on the node against its OWN loopback (so we read node-local state
   without exposing ports). Returns the body string (empty on failure)."
  [host url]
  (:out (sh host (tunnel/remote-curl-command url))))
