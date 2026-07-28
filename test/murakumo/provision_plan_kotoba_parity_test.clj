(ns murakumo.provision-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.provision.plan :as plan]))

(def port-source (slurp "kotoba/provision_plan_core.kotoba"))
(def export-prefix
  (str "plist-label remote-bin remote-store ssh-rsync-options peer-advertise-wait-ms "
       "default-p2p-port digit-char nat-str i64-str operator-seed-missing? resolve-p2p-port "
       "multiaddr webrtc-port mesh-binary-status-command remote-store-command "
       "launch-status-command peer-id-log-command live-link-count-command "
       "live-link-count-output launch-up-command launch-down-command "
       "reprovision-command watchdog-label watchdog-reprovision-command "
       "rsync-bin rsync-az-flag rsync-e-flag local-bin-path remote-bin-dest "
       "launchd-daemon-path tee-plist-prefix label-kv plist-heredoc-footer"))
(def fleet
  {:fleet/port 8077
   :fleet/p2p-port 4001
   :nodes [{:name "asher" :ip "100.0.0.1"}
           {:name "judah" :ip "100.0.0.2" :p2p-port 5001}]})

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest constants-and-commands-match
  (let [s (compile-string-cases
           {"pl" "(plist-label)" "rb" "(remote-bin)" "rs" "(remote-store)"
            "so" "(ssh-rsync-options)"
            "mb" "(mesh-binary-status-command)"
            "rc" "(remote-store-command)"
            "ma" (str "(multiaddr " (kotoba-literal "100.0.0.1") " 4001)")
            "ls" "(launch-status-command)"
            "pi" "(peer-id-log-command)"
            "ll" "(live-link-count-command)"
            "lo" (str "(live-link-count-output " (kotoba-literal " 3\n") ")")
            "up" "(launch-up-command)"
            "dn" "(launch-down-command)"
            "rp" "(reprovision-command)"
            "wl" "(watchdog-label)"
            "wr" "(watchdog-reprovision-command)"})
        n (compile-i64-cases
           {"w" "(peer-advertise-wait-ms)" "d" "(default-p2p-port)"
            "m0" (str "(operator-seed-missing? " (kotoba-literal "") ")")
            "m1" (str "(operator-seed-missing? " (kotoba-literal "seed") ")")
            "p0" "(resolve-p2p-port (option-none-of [:option :i64]) (option-some-of [:option :i64] 4001))"
            "p1" "(resolve-p2p-port (option-some-of [:option :i64] 5001) (option-some-of [:option :i64] 4001))"
            "p2" "(resolve-p2p-port (option-none-of [:option :i64]) (option-none-of [:option :i64]))"
            "wp" "(webrtc-port 4001)"})]
    (is (= plan/plist-label (get s "pl")))
    (is (= plan/remote-bin (get s "rb")))
    (is (= plan/remote-store (get s "rs")))
    (is (= plan/ssh-rsync-options (get s "so")))
    (is (= (plan/mesh-binary-status-command) (get s "mb")))
    (is (= (plan/remote-store-command) (get s "rc")))
    (is (= (plan/multiaddr "100.0.0.1" 4001) (get s "ma")))
    (is (= (plan/launch-status-command) (get s "ls")))
    (is (= (plan/peer-id-log-command) (get s "pi")))
    (is (= (plan/live-link-count-command) (get s "ll")))
    (is (= (plan/live-link-count-output " 3\n") (get s "lo")))
    (is (= (plan/launch-command :up) (get s "up")))
    (is (= (plan/launch-command :down) (get s "dn")))
    (is (= (plan/reprovision-command) (get s "rp")))
    (is (= plan/watchdog-label (get s "wl")))
    (is (= (plan/watchdog-reprovision-command) (get s "wr")))
    (is (= plan/peer-advertise-wait-ms (get n "w")))
    (is (= 4001 (get n "d")))
    (is (= (if (plan/operator-seed-missing? "") 1 0) (get n "m0")))
    (is (= (if (plan/operator-seed-missing? "seed") 1 0) (get n "m1")))
    (is (= (plan/node-p2p-port fleet (first (:nodes fleet))) (get n "p0")))
    (is (= (plan/node-p2p-port fleet (second (:nodes fleet))) (get n "p1")))
    (is (= (plan/node-p2p-port {} {}) (get n "p2")))
    (is (= 4101 (get n "wp")))))

(deftest rsync-and-plist-path-fragments-match
  (let [s (compile-string-cases
           {"rb" "(rsync-bin)"
            "az" "(rsync-az-flag)"
            "ef" "(rsync-e-flag)"
            "lp" (str "(local-bin-path " (kotoba-literal "/local/bin") " "
                      (kotoba-literal "kotoba") ")")
            "rd" (str "(remote-bin-dest " (kotoba-literal "asher") " "
                      (kotoba-literal "kotoba") ")")
            "ld" (str "(launchd-daemon-path " (kotoba-literal "com.murakumo.kotoba-mesh") ")")
            "tp" (str "(tee-plist-prefix " (kotoba-literal "com.murakumo.kotoba-mesh") ")")
            "kv" (str "(label-kv " (kotoba-literal "zone") " " (kotoba-literal "jp") ")")
            "ft" "(plist-heredoc-footer)"})]
    (is (= plan/rsync-bin (get s "rb")))
    (is (= plan/rsync-az-flag (get s "az")))
    (is (= plan/rsync-e-flag (get s "ef")))
    (is (= (plan/local-bin-path "/local/bin" "kotoba") (get s "lp")))
    (is (= (plan/remote-bin-dest "asher" "kotoba") (get s "rd")))
    (is (= (plan/launchd-daemon-path "com.murakumo.kotoba-mesh") (get s "ld")))
    (is (= (plan/tee-plist-prefix "com.murakumo.kotoba-mesh") (get s "tp")))
    (is (= (plan/label-kv "zone" "jp") (get s "kv")))
    (is (= plan/plist-heredoc-footer (get s "ft")))
    (is (= ["rsync" "-az" "-e" plan/ssh-rsync-options
            "/local/bin/kotoba" "asher:.murakumo/bin/kotoba"]
           (plan/rsync-binary-argv "/local/bin" "asher" "kotoba")))
    (is (= "sudo tee /Library/LaunchDaemons/com.murakumo.kotoba-mesh.plist >/dev/null <<'PLIST'\n<body>\nPLIST"
           (plan/write-plist-command "<body>")))
    (is (= "zone=jp,role=compute"
           (plan/labels-env {:zone "jp" :role "compute"})))))
