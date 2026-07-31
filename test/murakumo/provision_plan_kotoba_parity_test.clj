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
       "launchd-daemon-path tee-plist-prefix label-kv plist-heredoc-footer "
       "peer-at-sep peer-join-sep peer-entry did-key-prefix "
       "join-append bootstrap-append labels-append roles-append plist-replace "
       "alnum-char? find-prefix-at take-alnum peer-id-from-log write-plist-shell "
       "launchctl-print-prefix launchctl-bootout-prefix launchctl-bootstrap-sys "
       "launchctl-bootstrap-prefix launchctl-kickstart-prefix launchctl-status-suffix "
       "launchctl-plist-quiet-semi launchctl-quiet-true-sleep "
       "launchctl-plist-quiet-true-semi launchd-daemons-dir plist-ext"))
(def fleet
  {:fleet/port 8077
   :fleet/p2p-port 4001
   :nodes [{:name "asher" :ip "100.0.0.1"}
           {:name "judah" :ip "100.0.0.2" :p2p-port 5001}]})

(def ^:private multiaddr-ty
  "[:record :provision/multiaddr [[:ip :string] [:port :i64]]]")
(def ^:private bin-path-ty
  "[:record :provision/bin-path [[:local-bin :string] [:bin :string]]]")
(def ^:private remote-dest-ty
  "[:record :provision/remote-dest [[:host :string] [:bin :string]]]")
(def ^:private label-kv-ty
  "[:record :provision/label-kv [[:k :string] [:v :string]]]")
(def ^:private peer-entry-ty
  "[:record :provision/peer-entry [[:peer-id :string] [:multiaddr :string]]]")
(def ^:private join-ty
  "[:record :provision/join [[:acc :string] [:sep :string] [:next :string]]]")
(def ^:private bootstrap-ty
  "[:record :provision/bootstrap [[:acc :string] [:entry :string]]]")
(def ^:private labels-ty
  "[:record :provision/labels [[:acc :string] [:pair :string]]]")
(def ^:private roles-ty
  "[:record :provision/roles [[:acc :string] [:role :string]]]")
(def ^:private plist-replace-ty
  "[:record :provision/plist-replace [[:tmpl :string] [:ph :string] [:val :string]]]")
(def ^:private write-plist-ty
  "[:record :provision/write-plist [[:label :string] [:body :string]]]")

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
            "ma" (str "(multiaddr (record-new " multiaddr-ty " "
                      (kotoba-literal "100.0.0.1") " 4001))")
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
            "m0" (str "(if (operator-seed-missing? " (kotoba-literal "") ") 1 0)")
            "m1" (str "(if (operator-seed-missing? " (kotoba-literal "seed") ") 1 0)")
            "p0" (str "(resolve-p2p-port (record-new "
                      "[:record :provision/p2p-ports [[:node-port [:option :i64]] [:fleet-port [:option :i64]]]] "
                      "(option-none-of [:option :i64]) (option-some-of [:option :i64] 4001)))")
            "p1" (str "(resolve-p2p-port (record-new "
                      "[:record :provision/p2p-ports [[:node-port [:option :i64]] [:fleet-port [:option :i64]]]] "
                      "(option-some-of [:option :i64] 5001) (option-some-of [:option :i64] 4001)))")
            "p2" (str "(resolve-p2p-port (record-new "
                      "[:record :provision/p2p-ports [[:node-port [:option :i64]] [:fleet-port [:option :i64]]]] "
                      "(option-none-of [:option :i64]) (option-none-of [:option :i64])))")
            "wp" "(webrtc-port 4001)"})]
    (is (= plan/plist-label (get s "pl")))
    (is (= plan/remote-bin (get s "rb")))
    (is (= plan/remote-store (get s "rs")))
    (is (= plan/ssh-rsync-options (get s "so")))
    (is (= (plan/mesh-binary-status-command) (get s "mb")))
    (is (= (plan/remote-store-command) (get s "rc")))
    (is (= (plan/multiaddr "100.0.0.1" 4001) (get s "ma")))
    (is (= (plan/launch-status-command) (get s "ls")))
    (is (= (str plan/launchctl-print-prefix plan/plist-label plan/launchctl-status-suffix)
           (get s "ls")))
    (is (= (plan/peer-id-log-command) (get s "pi")))
    (is (= (plan/live-link-count-command) (get s "ll")))
    (is (= (plan/live-link-count-output " 3\n") (get s "lo")))
    (is (= (plan/launch-command :up) (get s "up")))
    (is (= (str plan/launchctl-bootstrap-prefix plan/plist-label
                plan/launchctl-plist-quiet-semi plan/launchctl-kickstart-prefix
                plan/plist-label)
           (get s "up")))
    (is (= (plan/launch-command :down) (get s "dn")))
    (is (= (str plan/launchctl-bootout-prefix plan/plist-label) (get s "dn")))
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
            "lp" (str "(local-bin-path (record-new " bin-path-ty " "
                      (kotoba-literal "/local/bin") " "
                      (kotoba-literal "kotoba") "))")
            "rd" (str "(remote-bin-dest (record-new " remote-dest-ty " "
                      (kotoba-literal "asher") " "
                      (kotoba-literal "kotoba") "))")
            "ld" (str "(launchd-daemon-path " (kotoba-literal "com.murakumo.kotoba-mesh") ")")
            "tp" (str "(tee-plist-prefix " (kotoba-literal "com.murakumo.kotoba-mesh") ")")
            "kv" (str "(label-kv (record-new " label-kv-ty " "
                      (kotoba-literal "zone") " " (kotoba-literal "jp") "))")
            "ft" "(plist-heredoc-footer)"
            "dd" "(launchd-daemons-dir)"
            "pe" "(plist-ext)"
            "pp" "(launchctl-print-prefix)"
            "bp" "(launchctl-bootout-prefix)"
            "bs" "(launchctl-bootstrap-sys)"
            "bf" "(launchctl-bootstrap-prefix)"
            "kp" "(launchctl-kickstart-prefix)"
            "ss" "(launchctl-status-suffix)"})]
    (is (= plan/rsync-bin (get s "rb")))
    (is (= plan/rsync-az-flag (get s "az")))
    (is (= plan/rsync-e-flag (get s "ef")))
    (is (= (plan/local-bin-path "/local/bin" "kotoba") (get s "lp")))
    (is (= (plan/remote-bin-dest "asher" "kotoba") (get s "rd")))
    (is (= (plan/launchd-daemon-path "com.murakumo.kotoba-mesh") (get s "ld")))
    (is (= (str plan/launchd-daemons-dir "com.murakumo.kotoba-mesh" plan/plist-ext)
           (get s "ld")))
    (is (= plan/launchd-daemons-dir (get s "dd")))
    (is (= "/Library/LaunchDaemons/" (get s "dd")))
    (is (= plan/plist-ext (get s "pe")))
    (is (= ".plist" (get s "pe")))
    (is (= plan/launchctl-print-prefix (get s "pp")))
    (is (= plan/launchctl-bootout-prefix (get s "bp")))
    (is (= plan/launchctl-bootstrap-sys (get s "bs")))
    (is (= plan/launchctl-bootstrap-prefix (get s "bf")))
    (is (= (str plan/launchctl-bootstrap-sys plan/launchd-daemons-dir) (get s "bf")))
    (is (= plan/launchctl-kickstart-prefix (get s "kp")))
    (is (= plan/launchctl-status-suffix (get s "ss")))
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

(deftest peer-entry-and-bootstrap-fragments-match
  (let [s (compile-string-cases
           {"at" "(peer-at-sep)"
            "js" "(peer-join-sep)"
            "dk" "(did-key-prefix)"
            "ip4" "(multiaddr-ip4-prefix)"
            "udp" "(multiaddr-udp-mid)"
            "quic" "(multiaddr-quic-suffix)"
            "pe" (str "(peer-entry (record-new " peer-entry-ty " "
                      (kotoba-literal "12D3peer") " "
                      (kotoba-literal "/ip4/1.2.3.4/udp/4001/quic-v1") "))")
            "ma" (str "(multiaddr (record-new " multiaddr-ty " "
                      (kotoba-literal "1.2.3.4") " 4001))")})]
    (is (= plan/peer-at-sep (get s "at")))
    (is (= plan/peer-join-sep (get s "js")))
    (is (= plan/did-key-prefix (get s "dk")))
    (is (= plan/multiaddr-ip4-prefix (get s "ip4")))
    (is (= "/ip4/" (get s "ip4")))
    (is (= plan/multiaddr-udp-mid (get s "udp")))
    (is (= "/udp/" (get s "udp")))
    (is (= plan/multiaddr-quic-suffix (get s "quic")))
    (is (= "/quic-v1" (get s "quic")))
    (is (= (plan/peer-entry "12D3peer" "/ip4/1.2.3.4/udp/4001/quic-v1")
           (get s "pe")))
    (is (= "12D3peer@/ip4/1.2.3.4/udp/4001/quic-v1" (get s "pe")))
    (is (= (plan/multiaddr "1.2.3.4" 4001) (get s "ma")))
    (is (= (str plan/multiaddr-ip4-prefix "1.2.3.4"
                plan/multiaddr-udp-mid "4001" plan/multiaddr-quic-suffix)
           (get s "ma")))
    (is (= "12D3peer@/ip4/1.2.3.4/udp/4001/quic-v1"
           (plan/peer-entry "12D3peer" (plan/multiaddr "1.2.3.4" 4001))))
    (let [peers {"judah" "12D3j"}
          boot (plan/bootstrap-str fleet peers (first (:nodes fleet)))]
      (is (str/includes? boot "12D3j@"))
      (is (str/includes? boot "/ip4/100.0.0.2/udp/5001/quic-v1"))
      (is (not (str/includes? boot "asher"))))))

(deftest fold-steps-match
  (let [s (compile-string-cases
           {"ja" (str "(join-append (record-new " join-ty " "
                      (kotoba-literal "") " " (kotoba-literal ",") " "
                      (kotoba-literal "a") "))")
            "jb" (str "(join-append (record-new " join-ty " "
                      (kotoba-literal "a") " " (kotoba-literal ",") " "
                      (kotoba-literal "b") "))")
            "ba0" (str "(bootstrap-append (record-new " bootstrap-ty " "
                       (kotoba-literal "") " " (kotoba-literal "p@/m") "))")
            "ba1" (str "(bootstrap-append (record-new " bootstrap-ty " "
                       (kotoba-literal "p@/m") " " (kotoba-literal "q@/n") "))")
            "la0" (str "(labels-append (record-new " labels-ty " "
                       (kotoba-literal "") " " (kotoba-literal "zone=jp") "))")
            "la1" (str "(labels-append (record-new " labels-ty " "
                       (kotoba-literal "zone=jp") " "
                       (kotoba-literal "role=compute") "))")
            "ra0" (str "(roles-append (record-new " roles-ty " "
                       (kotoba-literal "") " " (kotoba-literal "compute") "))")
            "ra1" (str "(roles-append (record-new " roles-ty " "
                       (kotoba-literal "compute") " " (kotoba-literal "pin") "))")
            "pr" (str "(plist-replace (record-new " plist-replace-ty " "
                      (kotoba-literal "x{{U}}y") " "
                      (kotoba-literal "{{U}}") " " (kotoba-literal "ops") "))")})]
    (is (= (plan/join-append "" "," "a") (get s "ja")))
    (is (= "a" (get s "ja")))
    (is (= (plan/join-append "a" "," "b") (get s "jb")))
    (is (= "a,b" (get s "jb")))
    (is (= (plan/bootstrap-append "" "p@/m") (get s "ba0")))
    (is (= "p@/m" (get s "ba0")))
    (is (= (plan/bootstrap-append "p@/m" "q@/n") (get s "ba1")))
    (is (= "p@/m,q@/n" (get s "ba1")))
    (is (= (plan/labels-append "" "zone=jp") (get s "la0")))
    (is (= (plan/labels-append "zone=jp" "role=compute") (get s "la1")))
    (is (= "zone=jp,role=compute" (get s "la1")))
    (is (= (plan/roles-append "" "compute") (get s "ra0")))
    (is (= (plan/roles-append "compute" "pin") (get s "ra1")))
    (is (= "compute,pin" (get s "ra1")))
    (is (= (plan/plist-replace "x{{U}}y" "{{U}}" "ops") (get s "pr")))
    (is (= "xopsy" (get s "pr")))
    (is (= "zone=jp,role=compute"
           (plan/labels-env {:zone "jp" :role "compute"})))
    (let [peers {"judah" "12D3j" "asher" "12D3a"}
          boot (plan/bootstrap-str fleet peers (first (:nodes fleet)))]
      (is (= "12D3j@/ip4/100.0.0.2/udp/5001/quic-v1" boot)))))

(deftest peer-id-patterns-and-log-commands-match
  (let [s (compile-string-cases
           {"bp" "(peer-id-body-prefix)"
            "bb" "(peer-id-body-pattern)"
            "dp" "(peer-id-did-pattern)"
            "di" (str "(did-peer-id " (kotoba-literal "12D3KooWPeer") ")")
            "pl" "(peer-id-log-command)"
            "ll" "(live-link-count-command)"
            "pi" (str "(peer-id-from-log "
                      (kotoba-literal "node_did=did:key:12D3KooWPeerId123\n") ")")
            "pn" (str "(peer-id-from-log " (kotoba-literal "did:key:zOther") ")")
            "pt" (str "(peer-id-from-log "
                      (kotoba-literal "noise\ndid:key:12D3KooWPeerId123 trailing\n") ")")
            "ws" (str "(write-plist-shell (record-new " write-plist-ty " "
                      (kotoba-literal "com.murakumo.kotoba-mesh") " "
                      (kotoba-literal "<plist/>") "))")})]
    (is (= plan/peer-id-body-prefix (get s "bp")))
    (is (= "12D3" (get s "bp")))
    (is (= plan/peer-id-body-pattern (get s "bb")))
    (is (= "12D3[A-Za-z0-9]*" (get s "bb")))
    (is (= plan/peer-id-did-pattern (get s "dp")))
    (is (= "did:key:12D3[A-Za-z0-9]*" (get s "dp")))
    (is (= (plan/did-peer-id "12D3KooWPeer") (get s "di")))
    (is (= "did:key:12D3KooWPeer" (get s "di")))
    (is (= (plan/peer-id-log-command) (get s "pl")))
    (is (str/includes? (get s "pl") plan/peer-id-did-pattern))
    (is (= (plan/live-link-count-command) (get s "ll")))
    (is (str/includes? (get s "ll") plan/peer-id-body-pattern))
    (is (= "12D3KooWPeerId123" (get s "pi")))
    (is (= (plan/peer-id-from-log "node_did=did:key:12D3KooWPeerId123\n")
           (get s "pi")))
    (is (= "" (get s "pn")))
    (is (nil? (plan/peer-id-from-log "did:key:zOther")))
    (is (= "12D3KooWPeerId123" (get s "pt")))
    (is (= (plan/peer-id-from-log "noise\ndid:key:12D3KooWPeerId123 trailing\n")
           (get s "pt")))
    (is (= (plan/write-plist-shell "com.murakumo.kotoba-mesh" "<plist/>")
           (get s "ws")))
    (is (= (plan/write-plist-command "<plist/>") (get s "ws")))
    (is (str/includes? (get s "ws") "<<'PLIST'"))
    (is (str/ends-with? (get s "ws") "\nPLIST"))))

(deftest home-bin-and-join-sep-fragments-match
  (let [s (compile-string-cases
           {"hs" "(home-bin-suffix)"
            "hp" (str "(home-bin-path " (kotoba-literal "/Users/mesh") ")")
            "lj" "(label-join-sep)"
            "rj" "(roles-join-sep)"})]
    (is (= plan/home-bin-suffix (get s "hs")))
    (is (= "/.murakumo/bin" (get s "hs")))
    (is (= (plan/home-bin-path "/Users/mesh") (get s "hp")))
    (is (= "/Users/mesh/.murakumo/bin" (get s "hp")))
    (is (= plan/label-join-sep (get s "lj")))
    (is (= plan/roles-join-sep (get s "rj")))
    (is (= "," plan/label-join-sep))
    (is (= "," plan/roles-join-sep))
    (is (= "zone=jp,role=compute"
           (plan/labels-env {:zone "jp" :role "compute"})))))

(deftest plist-placeholder-tokens-match
  (let [s (compile-string-cases
           {"u" "(plist-ph-user)"
            "b" "(plist-ph-bin)"
            "p" "(plist-ph-port)"
            "r" "(plist-ph-roles)"
            "l" "(plist-ph-labels)"
            "h" "(plist-ph-home)"
            "e" "(plist-ph-ed25519)"
            "x" "(plist-ph-x25519)"
            "d" "(plist-ph-did)"
            "pp" "(plist-ph-p2pport)"
            "ps" "(plist-ph-p2pseed)"
            "ex" "(plist-ph-extaddr)"
            "bo" "(plist-ph-bootstrap)"
            "w" "(plist-ph-webrtc)"})]
    (is (= plan/plist-ph-user (get s "u")))
    (is (= "{{USER}}" (get s "u")))
    (is (= plan/plist-ph-bin (get s "b")))
    (is (= "{{BIN}}" (get s "b")))
    (is (= plan/plist-ph-port (get s "p")))
    (is (= plan/plist-ph-roles (get s "r")))
    (is (= plan/plist-ph-labels (get s "l")))
    (is (= plan/plist-ph-home (get s "h")))
    (is (= plan/plist-ph-ed25519 (get s "e")))
    (is (= plan/plist-ph-x25519 (get s "x")))
    (is (= plan/plist-ph-did (get s "d")))
    (is (= plan/plist-ph-p2pport (get s "pp")))
    (is (= plan/plist-ph-p2pseed (get s "ps")))
    (is (= plan/plist-ph-extaddr (get s "ex")))
    (is (= plan/plist-ph-bootstrap (get s "bo")))
    (is (= plan/plist-ph-webrtc (get s "w")))
    (is (= "{{WEBRTC}}" (get s "w")))
    (let [out (plan/render-plist
               "{{USER}}|{{BIN}}|{{PORT}}|{{HOME}}"
               {:fleet/port 8077 :nodes []}
               nil {}
               {:name "n" :roles [] :labels {}}
               {:user "u" :home "/h" :operator-seed "" :x25519-seed ""
                :did "" :p2p-seed ""})]
      (is (= "u|/h/.murakumo/bin|8077|/h" out)))))
