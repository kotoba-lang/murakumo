;; murakumo.report-test — offline tests for CLI report formatting.

(ns murakumo.report-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.report :as report]))

(deftest node-and-status-rows-are-stable
  (is (= "NODE       TAILSCALE-IP     ONLINE   SSH       MESH"
         (report/nodes-header)))
  (is (= "asher      100.0.0.1        yes      ok        installed/running"
         (report/nodes-row {:name "asher" :ip "100.0.0.1" :online? true}
                           true
                           "installed/running")))
  (is (= "judah      ?                no       no        -"
         (report/nodes-row {:name "judah"} false "-")))
  (is (= "installed/running"
         (report/mesh-status "installed" "running")))
  (is (= "NODE       HEALTH   WASM-EXEC    LINKS  P2P-PORT"
         (report/status-header)))
  (is (= "judah      down    "
         (report/status-down-row {:name "judah"})))
  (is (= "asher      ok       ready        3      4001"
         (report/status-row {:name "asher"}
                            {:subsystems {:wasm_executor "ready"}}
                            "3"
                            4001)))
  (is (= "asher      ok       ready        3      4001"
         (report/status-row* {:node {:name "asher"}
                              :health-json {:subsystems {:wasm_executor "ready"}}
                              :links "3"
                              :p2p-port 4001})))
  (is (= "asher      no-resp  ?            -      4001"
         (report/status-row {:name "asher"} nil "3" 4001))))

(deftest deploy-and-help-reports-are-stable
  (is (= "  ✓ placed + running on: asher, judah  (deployed from asher)"
         (report/deploy-observed-row ["asher" "judah"] {:name "asher"})))
  (is (= "  ⚠ not yet observed running on any node (check `murakumo status` / node logs)"
         (report/deploy-observed-row [] {:name "asher"})))
  (is (re-find #"murakumo — kotoba WASM mesh control plane" (report/command-help)))
  (is (re-find #"reconcile <murakumo.app.edn>" (report/command-help)))
  (is (re-find #"cloud\s+\[plan\|records\|routes\|dial\|connect <node>\|relay <name>\|bootstrap\]" (report/command-help))))

(deftest operator-progress-lines-are-stable
  (let [bm {:version "kotoba 0.1.0" :git-sha "abc123" :features "p2p"}]
    (is (= "[asher] " (report/node-prefix {:name "asher"})))
    (is (= "unreachable — skipped"
           report/unreachable-skipped-line))
    (is (= "provisioned + loaded" (report/provision-result-line nil)))
    (is (= "provisioned + loaded (peered)" (report/provision-result-line true)))
    (is (= "asher 0"
           (report/launch-result-line {:name "asher"} {:exit 0})))
    (is (= ["fleet pins kotoba kotoba 0.1.0 (sha abc123) but ./bin has no binaries."
            "Build that version and `murakumo pin <its release dir>` before provisioning."]
           (report/missing-pinned-binaries-lines bm)))
    (is (= "rolling out kotoba kotoba 0.1.0 (sha abc123, p2p)"
           (report/rollout-line bm)))
    (is (= "── collected 2 PeerIds → .murakumo-peers.edn ──"
           (report/collected-peers-line 2 ".murakumo-peers.edn")))
    (is (= "── pass 1: provision with fixed P2P port + stable PeerId ──"
           report/mesh-pass1-line))
    (is (= "── waiting for nodes to advertise their PeerId ──"
           report/mesh-wait-peerid-line))
    (is (= "── pass 2: re-provision with KOTOBA_BOOTSTRAP_PEERS = the others ──"
           report/mesh-pass2-line))
    (is (= "── lattice forming; check `murakumo status` (PEERS should climb) ──"
           report/mesh-forming-line))
    (is (= " asher✓" (report/artifact-node-status {:name "asher"} {:exit 0})))
    (is (= " judah✗" (report/artifact-node-status {:name "judah"} {:exit 1})))
    (is (= "deploy app.edn  (component bafyCID)"
           (report/deploy-start-line "app.edn" "bafyCID")))
    (is (= "okwarn"
           (report/deploy-command-output "ok\n" "warn")))
    (is (= "pinned kotoba + kotoba-server → bin/  (src release @ abc123, kotoba 0.1.0)"
           (report/pin-success-line "release" "abc123" "kotoba 0.1.0")))
    (is (= "missing binary: /missing/kotoba"
           (report/missing-binary-line "/missing/kotoba")))
    (is (= "  waiting for the lattice to place + run it…"
           report/deploy-wait-placement-line))
    (is (= "[alert/warn] asher — degraded"
           (report/alert-line {:level "warn" :node "asher" :msg "degraded"})))
    (is (= "snapshot error: boom"
           (report/snapshot-error-line "boom")))
    (is (= "reconcile persist error: boom"
           (report/reconcile-persist-error-line "boom")))
    (is (= "murakumo dashboard → http://localhost:8899  (snapshot every 15s → Datom log)"
           (report/dashboard-start-line 8899 15)))
    (is (= "  applying heartbeat → publish (auction will place on eligible: asher,judah)"
           (report/apply-app-line {:app "heartbeat" :targets ["asher" "judah"]})))
    (is (= "── reconcile --watch (every 30s) ; Ctrl-C to stop ──"
           (report/watch-start-line 30)))
    (is (= "set MURAKUMO_OPERATOR_SEED first"
           report/operator-seed-required-line))
    (is (= "set MURAKUMO_OPERATOR_SEED (32-byte hex) first"
           report/operator-seed-hex-required-line))
    (is (= "usage: deploy <app.edn> [publish-node]"
           report/deploy-usage-line))
    (is (= "usage: reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]]"
           report/reconcile-usage-line))
    (is (= "set MURAKUMO_OPERATOR_SEED (32-byte hex) first"
           (report/command-error-line :provision :missing-operator-seed-hex)))
    (is (= "set MURAKUMO_OPERATOR_SEED first"
           (report/command-error-line :mesh :missing-operator-seed)))
    (is (= "usage: deploy <app.edn> [publish-node]"
           (report/command-error-line :deploy :missing-manifest)))
    (is (= "set MURAKUMO_OPERATOR_SEED first"
           (report/command-error-line :deploy :missing-operator-seed)))
    (is (= "usage: reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]]"
           (report/command-error-line :reconcile :missing-manifest)))
    (is (= "(no MURAKUMO_OPERATOR_SEED → dashboard live-only, no Datom persistence)"
           report/dashboard-no-persistence-line))
    (is (= "(no MURAKUMO_OPERATOR_SEED → watch without Datom persistence)"
           report/reconcile-no-persistence-line))
    (is (= "  ✓ converged"
           report/reconcile-converged-line))
    (is (= "\n(dry-run; re-run with --apply to converge, or --watch to keep it converged)"
           report/reconcile-dry-run-line))))

(deftest reconcile-report-lines-are-stable
  (let [plan {:fleet "test" :ts "t"
              :apps [{:app "heartbeat" :cid "bafyHEART1234567890"
                      :desired 2 :running ["a"] :action :place :targets ["b"]
                      :reach [:browser/live] :eligible ["a" "b"] :misplaced ["z"]}
                     {:app "ok" :cid nil :desired 1 :running [] :action :satisfied}]}]
    (is (= ["reconcile test  @ t"
            "  APP            CID        DESIRED RUNNING ACTION    DETAIL"
            "  heartbeat      bafyHEART1234567 2       1       place     → b"
            "                            reach: :browser/live → eligible(by transport)=a,b"
            "                            drift: running on non-eligible node(s): z"
            "  ok             —          1       0       satisfied "]
           (report/reconcile-lines plan)))))
