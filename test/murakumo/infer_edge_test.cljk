(ns murakumo.infer-edge-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.edge :as edge]))

(deftest renders-admitted-resident-plists
  (let [server (edge/server-plan
                {:home "/Users/asher" :llama-server "/opt/llama-server"
                 :memory-bytes (* 16 1073741824)})
        join (edge/join-plan
              {:home "/Users/asher" :nbb "/opt/homebrew/bin/nbb"
               :node-name "asher"})]
    (is (:admitted? server))
    (is (re-find #"<key>UserName</key><string>asher</string>" (:plist server)))
    (is (re-find #"<key>UserName</key><string>asher</string>" (:plist join)))
    (is (re-find #"--ctx-size</string><string>65536" (:plist server)))
    (is (not (re-find #"--spec-type" (:plist server))))
    (is (re-find #"murakumo-edge" (:plist server)))
    (testing "a crash loop is throttled to a minute, not launchd's five seconds"
      ;; The worker exits when its startup enrolment fetch fails. At a
      ;; five-second throttle that is ~720 restarts an hour, each opening a
      ;; socket; simeon spent a 53,536-port range that way on 2026-09-10.
      (is (re-find #"<key>ThrottleInterval</key><integer>60</integer>" (:plist server)))
      (is (not (re-find #"<integer>5</integer>" (:plist server)))))
    (is (re-find #"source /Users/asher/.murakumo/edge/join.env" (:plist join)))
    (is (not (re-find #"MURAKUMO_SERVICE_TOKEN=" (:plist join))))))

(deftest the-resident-jobs-install-as-system-daemons-not-user-agents
  ;; Measured 2026-09-09: every mini runs with no login session, so a
  ;; LaunchAgent is never loaded and the jobs that were running were
  ;; hand-started orphans -- 10d21h old on hosts that booted 24 days earlier.
  ;; The cure for the exhausted nodes is a reboot, so supervision that does not
  ;; survive one is the thing blocking the repair.
  (let [join (edge/join-plan {:home "/Users/asher" :nbb "/opt/homebrew/bin/nbb"
                              :node-name "asher"})
        script (edge/install-script join)]
    (is (re-find #"/Library/LaunchDaemons/com\.murakumo\.edge-join\.plist" script)
        "the job must land in the system domain")
    (is (re-find #"launchctl bootstrap system " script))
    (is (not (re-find #"bootstrap gui/" script))
        "nothing may bootstrap into a gui domain that does not exist on these hosts")
    (testing "the LaunchAgent that could never load is removed, not just unloaded"
      (is (re-find #"bootout gui/\$\(id -u\)/com\.murakumo\.edge-join" script))
      (is (re-find #"rm -f /Users/asher/Library/LaunchAgents/com\.murakumo\.edge-join\.plist" script)))
    (testing "the daemon still runs as the node user, because the model and join.env are in that home"
      (is (re-find #"<key>UserName</key><string>asher</string>" (:plist join))))
    (testing "the hand-started orphan is reaped, or bootstrap makes a second worker"
      ;; bootout only touches launchd jobs. The workers on these nodes are not
      ;; launchd jobs; installing without this would leave two edge-join
      ;; processes claiming each other's queue jobs.
      (is (re-find #"pkill -f 'murakumo/scripts/infer-join\.cljs'" script))
      (is (< (.indexOf script "pkill") (.indexOf script "bootstrap system"))
          "the orphan must die before the daemon starts, not after"))
    (testing "install verifies the LOADED definition, not the file it wrote"
      ;; launchd caches the definition it loaded; a written plist is not a
      ;; loaded one, and `launchctl list` cannot tell the two apart.
      (is (re-find #"launchctl print system/com\.murakumo\.edge-join" script))
      (is (re-find #"MURAKUMO_EDGE_INSTALL_UNVERIFIED" script)
          "an unverifiable install must fail loudly rather than report success"))))

(deftest resident-fleet-joins-the-secure-pool
  ;; The ten fleet.edn minis are AWAI-operated hardware, which
  ;; docs/adr-secure-community-cloud.md defines as :awai-secure. The join
  ;; worker defaults to community on purpose, so the tier has to be spelled
  ;; here by whoever operates the machines.
  ;;
  ;; Measured 2026-08-29 with this line absent: /infer/nodes showed all ten
  ;; minis model=murakumo-edge, live? true, ready? true, slots-free 1, and
  ;; admission "pending" -- placement needs an exact tier match and there is no
  ;; Secure-to-Community fallback, so murakumo-edge had zero admitted backends
  ;; and every Bot turn failed provider/http-error 503 against a healthy fleet.
  (let [join (edge/join-plan
              {:home "/Users/asher" :nbb "/opt/homebrew/bin/nbb"
               :node-name "asher"})]
    (is (re-find #"--trust-tier awai-secure" (:plist join))
        "the resident daemon must declare the tier; omission enrolls Community")
    ;; And it must not be smuggled in as a bare word that happens to appear:
    ;; the flag has to precede the value the join worker validates.
    (is (re-find #"--trust-tier awai-secure --local-url" (:plist join)))))

;; ── a node dedicated to one model ──────────────────────────────────────────

(deftest a-dedicated-model-gets-its-own-labels-port-and-batch
  (let [server (edge/model-server-plan
                "murakumo-27b"
                {:home "/Users/issachar" :llama-server "/opt/llama-server"
                 :memory-bytes 17179869184})
        join (edge/model-join-plan
              "murakumo-27b"
              {:home "/Users/issachar" :nbb "/opt/nbb" :node-name "issachar"
               :local-port 8093})]
    (is (:admitted? server))
    (is (= "com.murakumo.model-server" (:label server)))
    (is (= "com.murakumo.model-join" (:label join)))
    (is (re-find #"--port</string><string>8093" (:plist server)))
    (is (re-find #"--batch-size</string><string>128" (:plist server)))
    ;; The worker must enrol for THIS model and execute against THIS port.
    ;; Split, they are the failure that looks like success: a worker enrolled
    ;; for murakumo-27b pointed at 8092 claims 27B jobs and answers with the 9B.
    (is (re-find #"--model murakumo-27b " (:plist join)))
    (is (re-find #"--local-url http://127\.0\.0\.1:8093/v1" (:plist join)))))

(deftest murakumo-edge-keeps-the-labels-ports-and-logs-it-has
  ;; The registry must not rename the jobs five running nodes are supervised
  ;; by: a renamed label is a second copy, not a replacement.
  (let [server (edge/server-plan {:home "/Users/dan" :llama-server "/opt/llama-server"
                                  :memory-bytes 17179869184})
        join (edge/join-plan {:home "/Users/dan" :nbb "/opt/nbb" :node-name "dan"})]
    (is (= "com.murakumo.edge-server" (:label server)))
    (is (= "com.murakumo.edge-join" (:label join)))
    (is (re-find #"--port</string><string>8092" (:plist server)))
    (is (re-find #"/Users/dan/\.murakumo/edge/server\.log" (:plist server)))
    (is (re-find #"/Users/dan/\.murakumo/edge/join\.log" (:plist join)))
    (is (not-any? #{"--batch-size"} (:argv server)))))

(deftest the-wired-limit-daemon-verifies-the-sysctl-not-launchctl
  (let [script (edge/wired-limit-script 15360)]
    (is (re-find #"iogpu\.wired_limit_mb=15360" script))
    (is (re-find #"RunAtLoad" (edge/wired-limit-plist 15360)))
    ;; Bootstrapping a job that runs `sysctl -w` succeeds whether or not the
    ;; write did. Without the read-back, a node reporting a clean install can
    ;; be sitting at the 13,312 default that puts a 15,222 MiB model on the CPU.
    (is (re-find #"got=\$\(sysctl -n iogpu\.wired_limit_mb\)" script))
    (is (re-find #"MURAKUMO_WIRED_LIMIT_UNVERIFIED" script))
    (is (re-find #"exit 3" script)))
  (is (thrown? Exception (edge/wired-limit-plist 0)))
  (is (thrown? Exception (edge/wired-limit-plist nil))))

(deftest eviction-parks-the-plist-because-bootout-does-not-survive-a-boot
  (let [script (edge/evict-script ["com.murakumo.comfyui" "com.murakumo.ollama"])]
    (is (re-find #"bootout system/com\.murakumo\.comfyui" script))
    ;; The part that makes it an eviction rather than a restart: launchd
    ;; rescans /Library/LaunchDaemons at boot, so a booted-out job whose plist
    ;; is still there comes back, and comes back when nobody is watching.
    (is (re-find #"mv /Library/LaunchDaemons/com\.murakumo\.ollama\.plist /Library/LaunchDaemons/evicted-by-murakumo/"
                 script))
    (is (re-find #"MURAKUMO_STILL_LOADED" script))
    ;; `bootout` returns before launchd finishes unloading. Measured the same
    ;; day, counting immediately reported 1 and 2 still-loaded jobs that were
    ;; both 0 seconds later, with every plist parked.
    (is (re-find #"sleep 5; echo MURAKUMO_STILL_LOADED" script)
        "the count must settle, or the guard cries wolf and gets ignored")))
