(ns murakumo.access
  "Whether a fleet node can still be reached when tailscaled is not running.

  ## The incident this exists because of

  2026-09-09, levi. A 27B model was loaded on a 16 GiB mini to measure whether
  it fits; a 32,768-token context pushed the node into swap death. It recovered
  enough that the kernel answers ping and macOS sshd accepts TCP on 22, and it
  has been unreachable ever since, because:

    the only working way in was Tailscale SSH, and tailscaled was the process
    that died.

  That is not a fact about levi. Every node in this fleet is reached the same
  way, and `ssh -vv` names it: a tailnet connection answers with
  `remote software version Tailscale`, a LAN connection to the same host
  answers `OpenSSH_10.0`. Two different servers, one of which nobody had ever
  used.

  ## What this namespace checks, and why a file is not enough

  There IS a second path — macOS OpenSSH on the LAN, authenticated by the fleet
  key, reached through another node as a jump host. Measured working on judah
  the same day. It had simply never been exercised, so nobody knew whether it
  worked, and `grep` on `authorized_keys` would not have told them: a key can
  be present and still ignored, because sshd refuses `authorized_keys` when the
  home directory or `.ssh` is group-writable, and it refuses silently.

  So `check-node!` OPENS THE FALLBACK CONNECTION. The file check and the
  permission check are reported too, because when the connection fails they say
  which of the three reasons it was — but they are diagnosis, not the verdict.

  ## The verdict this reports

  `:tailnet-only` is the finding. It does not mean a node is down; it means the
  node is one process away from being levi, and nothing about its behaviour
  will say so until that process dies."
  (:require [kotoba.lang.text :as str]
            #?@(:clj [[babashka.process :as p]
                      [murakumo.ssh :as ssh]])))

(def default-fleet-key
  "The operator key this fleet's ssh config already names for every node
  (`IdentityFile ~/.ssh/murakumo`). Over the tailnet it is decoration —
  Tailscale SSH authenticates by tailnet identity and never looks at it — which
  is exactly why nobody noticed whether the nodes' authorized_keys honoured it."
  (str (System/getProperty "user.home") "/.ssh/murakumo"))

;; ── pure ───────────────────────────────────────────────────────────────────

(defn authorized-keys-probe
  "Remote shell that reports the three things sshd consults, one per line.

  Permissions are reported as octal because the failure they cause is silent:
  a group-writable home makes sshd ignore `authorized_keys` entirely, with no
  log line the caller sees and no difference in the client's error message. A
  key that is present and ignored and a key that is absent produce the same
  `Permission denied`."
  [pubkey]
  (str "test -f ~/.ssh/authorized_keys && echo keys-file=yes || echo keys-file=no; "
       "grep -qF '" pubkey "' ~/.ssh/authorized_keys 2>/dev/null "
       "&& echo fleet-key=yes || echo fleet-key=no; "
       "stat -f '%Lp' ~ | sed 's/^/home-mode=/'; "
       "stat -f '%Lp' ~/.ssh 2>/dev/null | sed 's/^/ssh-mode=/' || echo ssh-mode=none"))

(defn- parse-kv [out]
  (into {} (keep (fn [line]
                   (let [[k v] (str/split (str/trim line) #"=" 2)]
                     (when (and k v) [(keyword k) v])))
                 (str/split (str out) #"\n"))))

(defn perms-ok?
  "sshd ignores authorized_keys when the home directory or .ssh is writable by
  group or other. Modes arrive as octal strings; nil means it was not measured
  and must not be read as ok."
  [{:keys [home-mode ssh-mode]}]
  (letfn [(strict? [m]
            (and (string? m)
                 (re-matches #"\d{3,4}" m)
                 (let [n (Integer/parseInt m 8)]
                   (zero? (bit-and n 0022)))))]
    (and (strict? home-mode) (strict? ssh-mode))))

(defn verdict
  "One word for how many independent ways in this node has.

  The order of these clauses is the whole point. `:unmeasured` comes first
  because a check that could not run must never share an answer with a check
  that ran and found nothing wrong — the failure this fleet keeps meeting."
  [{:keys [tailnet fallback]}]
  (cond
    (or (= :unmeasured tailnet) (= :unmeasured fallback)) :unmeasured
    (and tailnet fallback) :both-paths
    ;; Reachable today, and one daemon away from stranded. This is levi on the
    ;; morning of 2026-09-09, when everything looked fine.
    (and tailnet (not fallback)) :tailnet-only
    (and (not tailnet) fallback) :fallback-only
    :else :stranded))

(defn stranded-risk?
  "Does this node have exactly one way in? True for :tailnet-only and
  :fallback-only alike — a single path is a single path regardless of which."
  [v]
  (contains? #{:tailnet-only :fallback-only} v))

(defn repair-script
  "Install the fleet key and correct the permissions sshd needs.

  chmod before the append: adding a key to a file sshd will not read produces a
  node that reports repaired and is not."
  [pubkey]
  (str "chmod go-w ~ 2>/dev/null; mkdir -p ~/.ssh; chmod 700 ~/.ssh; "
       "touch ~/.ssh/authorized_keys; chmod 600 ~/.ssh/authorized_keys; "
       "grep -qF '" pubkey "' ~/.ssh/authorized_keys "
       "|| printf '%s\\n' '" pubkey "' >> ~/.ssh/authorized_keys; "
       "grep -cF '" pubkey "' ~/.ssh/authorized_keys"))

;; ── I/O ────────────────────────────────────────────────────────────────────
;;
;; The whole I/O half sits in ONE reader conditional rather than a conditional
;; per function. A file where some definitions are guarded and others are not
;; invites exactly one mistake -- adding a JVM-only call to an unguarded
;; function -- and that mistake compiles fine until something loads this
;; namespace from ClojureScript.

#?(:clj
   (do


   (defn- lan-address [node]
     (when-let [ip (or (:rpc-ip node) (:ip node))]
       (str (or (:user node)
                (let [h (str (:host node))]
                  (if (str/includes? h "@") (first (str/split h #"@" 2)) (:name node))))
            "@" ip)))

   (defn fallback-exercise!
     "Actually open the fallback, from where the operator sits.

     ProxyJump, NOT `ssh` executed on the jump host. The distinction is the whole
     measurement and it is easy to get backwards -- I did, on the first run of
     this function. Running `ssh` on the jump host asks whether some
     other node can reach this one, which the nodes answer with their own keys and which says nothing
     about whether a person can get in when tailscaled is gone. `-J` makes the
     jump host a TCP tunnel and nothing else: the authentication is the operator's
     fleet key against the target's own sshd, which is the path that has to work
     on the day it is needed.

     Measured 2026-09-09: the exec-on-jump version reported four of five macOS
     nodes reachable; the ProxyJump version reports one. The one is correct --
     `ssh -i ~/.ssh/murakumo -J dan judah@192.168.1.21` is the connection that was
     made by hand that day, and the only one that was.

     Returns true/false, or :unmeasured when there was no jump host or no LAN
     address to try. A missing vantage point is not the node's failure."
     [node jump key-path]
     (let [addr (lan-address node)]
       (cond
         (nil? addr) :unmeasured
         (nil? jump) :unmeasured
         :else
         (let [{:keys [exit]}
               (p/sh {:out :string :err :string}
                     "ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=12"
                     "-o" "StrictHostKeyChecking=no" "-o" "IdentitiesOnly=yes"
                     "-i" key-path "-J" jump addr "true")]
           (zero? (long (or exit 1)))))))

   (defn check-node!
     "Probe one node's two paths. `jump` is a node name already known reachable."
     [node {:keys [jump key-path pubkey]}]
     (let [name (:name node)
           host (:host node)
           tailnet-probe (ssh/sh host "true")
           tailnet (zero? (long (or (:exit tailnet-probe) 1)))
           keys (when tailnet (parse-kv (:out (ssh/sh host (authorized-keys-probe pubkey)))))
           fallback (fallback-exercise! node jump key-path)
           v (verdict {:tailnet tailnet :fallback fallback})]
       (cond-> {:node name :tailnet tailnet :fallback fallback :verdict v}
         keys (assoc :fleet-key (= "yes" (:fleet-key keys))
                     :perms-ok (perms-ok? keys)
                     :modes (select-keys keys [:home-mode :ssh-mode])))))

   (defn- first-reachable [nodes]
     (some (fn [n] (when (zero? (long (or (:exit (ssh/sh (:host n) "true")) 1))) (:name n)))
           nodes))

   (defn check!
     "Check every selected node, using another reachable node as the jump host."
     [{:keys [nodes]} selector {:keys [key-path] :or {key-path default-fleet-key}}]
     (let [pubkey (str/trim (slurp (str key-path ".pub")))
           targets (if (or (nil? selector) (= "all" selector))
                     nodes
                     (filter #(= selector (:name %)) nodes))]
       (when (empty? targets)
         (throw (ex-info "no fleet node matched" {:selector selector})))
       (mapv (fn [n]
               ;; A node may not jump through itself: the whole question is whether
               ;; it is reachable when its own daemon is gone.
               (let [jump (first-reachable (remove #(= (:name %) (:name n)) nodes))]
                 (check-node! n {:jump jump :key-path key-path :pubkey pubkey})))
             targets)))

   (defn repair!
     "Give every selected reachable node the second path it is missing."
     [{:keys [nodes]} selector {:keys [key-path] :or {key-path default-fleet-key}}]
     (let [pubkey (str/trim (slurp (str key-path ".pub")))
           targets (if (or (nil? selector) (= "all" selector))
                     nodes
                     (filter #(= selector (:name %)) nodes))]
       (mapv (fn [{:keys [name host]}]
               (let [{:keys [exit out err]} (ssh/sh host (repair-script pubkey))]
                 {:node name :ok? (zero? exit)
                  :count (str/trim (str out))
                  :err (str/trim (str err))}))
             targets)))

   (defn report [results]
     (str/join
      "\n"
      (map (fn [{:keys [node verdict tailnet fallback fleet-key perms-ok modes]}]
             (format "[%-10s] %-14s tailnet=%s fallback=%s%s%s"
                     node (name verdict) (str tailnet) (str fallback)
                     (if (nil? fleet-key) "" (str " fleet-key=" fleet-key))
                     (cond
                       (nil? perms-ok) ""
                       perms-ok ""
                       :else (str " PERMS-IGNORED-BY-SSHD " (pr-str modes)))))
           results)))
))
