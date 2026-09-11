(ns murakumo.access-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.access :as access]))

(deftest a-check-that-could-not-run-never-shares-an-answer-with-one-that-passed
  ;; The failure this fleet keeps meeting, applied to its own reachability.
  (is (= :unmeasured (access/verdict {:tailnet true :fallback :unmeasured})))
  (is (= :unmeasured (access/verdict {:tailnet :unmeasured :fallback true})))
  (is (not= (access/verdict {:tailnet true :fallback :unmeasured})
            (access/verdict {:tailnet true :fallback true}))))

(deftest one-way-in-is-reported-as-one-way-in
  (testing "the finding: reachable today, one daemon from stranded"
    (is (= :tailnet-only (access/verdict {:tailnet true :fallback false})))
    (is (true? (access/stranded-risk? :tailnet-only))))
  (testing "the mirror case counts too -- a single path is a single path"
    (is (= :fallback-only (access/verdict {:tailnet false :fallback true})))
    (is (true? (access/stranded-risk? :fallback-only))))
  (testing "two paths, and none"
    (is (= :both-paths (access/verdict {:tailnet true :fallback true})))
    (is (false? (access/stranded-risk? :both-paths)))
    (is (= :stranded (access/verdict {:tailnet false :fallback false})))))

(deftest a-key-sshd-will-ignore-is-not-a-key
  ;; sshd refuses authorized_keys when the home dir or .ssh is group- or
  ;; other-WRITABLE, and refuses silently: present-and-ignored and absent
  ;; produce the same client error. So this predicate is checked on write bits
  ;; only -- 755 is readable by the world and perfectly acceptable to sshd, and
  ;; a check that rejected it would send operators chasing a non-problem.
  (is (true? (access/perms-ok? {:home-mode "700" :ssh-mode "700"})))
  (is (true? (access/perms-ok? {:home-mode "755" :ssh-mode "700"})))
  (is (false? (access/perms-ok? {:home-mode "775" :ssh-mode "700"}))
      "group-writable home makes sshd ignore the file")
  (is (false? (access/perms-ok? {:home-mode "707" :ssh-mode "700"}))
      "other-writable home does too")
  (is (false? (access/perms-ok? {:home-mode "700" :ssh-mode "770"})))
  (testing "unmeasured is not ok"
    (is (false? (access/perms-ok? {:home-mode nil :ssh-mode "700"})))
    (is (false? (access/perms-ok? {})))))

(deftest repair-fixes-the-permissions-before-it-adds-the-key
  ;; Appending a key to a file sshd will not read produces a node that reports
  ;; repaired and is not.
  (let [s (access/repair-script "ssh-ed25519 AAAAKEY test@host")]
    (is (< (.indexOf s "chmod go-w") (.indexOf s "authorized_keys")))
    (is (re-find #"chmod 700 ~/\.ssh" s))
    (is (re-find #"chmod 600 ~/\.ssh/authorized_keys" s))
    (testing "and it is idempotent"
      (is (re-find #"grep -qF .* \|\| printf" s)))
    (testing "and it ends by counting, so the caller can tell it landed"
      (is (re-find #"grep -cF .* ~/\.ssh/authorized_keys$" s)))))

(deftest the-probe-reports-modes-because-the-failure-is-silent
  (let [p (access/authorized-keys-probe "ssh-ed25519 AAAAKEY test@host")]
    (is (re-find #"fleet-key=" p))
    (is (re-find #"home-mode=" p))
    (is (re-find #"ssh-mode=" p))
    (testing "an unreadable .ssh reports none rather than nothing"
      (is (re-find #"ssh-mode=none" p)))))

(deftest a-node-may-not-jump-through-itself
  ;; The question is whether the node is reachable when its own daemon is gone,
  ;; so routing the test through that same node answers a different question.
  (is (= :unmeasured (access/fallback-exercise! {:name "levi" :rpc-ip "192.168.1.26"}
                                                nil "/dev/null"))
      "no jump host means unmeasured, not failed -- that would blame the node for the vantage point")
  (is (= :unmeasured (access/fallback-exercise! {:name "levi"} "dan" "/dev/null"))
      "a node with no LAN address in fleet.edn cannot be tested this way"))

;; ── the constrained recovery key ───────────────────────────────────────────

(deftest the-recovery-key-can-restore-the-way-in
  ;; Until 2026-09-09 the one key that exists for recovering a node could not
  ;; recover the one failure that makes a node unrecoverable: its forced
  ;; command kickstarted only the RPC worker, and what dies is tailscaled.
  (require 'murakumo.infer)
  (let [entry ((resolve 'murakumo.infer/rpc-ha-authorized-key) "ssh-ed25519 AAAAKEY head@gad")
        legacy ((resolve 'murakumo.infer/rpc-ha-legacy-authorized-key) "ssh-ed25519 AAAAKEY head@gad")]
    (is (re-find #"^restrict,command=" entry) "no shell, no port forwarding")
    (is (re-find #"homebrew\.mxcl\.tailscale" entry)
        "the label measured on the nodes, not com.tailscale.tailscaled which does not exist there")
    (is (re-find #"com\.murakumo\.rpc-worker" entry) "and it still does what it used to")
    (testing "tailscaled goes first, because it is what restores the way in"
      (is (< (.indexOf entry "homebrew.mxcl.tailscale")
             (.indexOf entry "com.murakumo.rpc-worker"))))
    (testing "the caller still chooses nothing"
      (is (not (re-find #"SSH_ORIGINAL_COMMAND" entry)))
      (is (= 1 (count (re-seq #"command=" entry)))))
    (testing "the pre-2026-09-09 entry is reproducible, so a re-provision can remove it"
      (is (not= entry legacy))
      (is (not (re-find #"tailscale" legacy)))
      (is (re-find #"com\.murakumo\.rpc-worker" legacy)))))
