;; murakumo.provision-plan-test — offline tests for portable provision planning.

(ns murakumo.provision-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [murakumo.provision.plan :as plan]))

(def fleet
  {:fleet/port 8077
   :fleet/p2p-port 4001
   :nodes [{:name "asher" :ip "100.0.0.1" :roles ["compute"] :labels {:zone "jp"}}
           {:name "judah" :ip "100.0.0.2" :p2p-port 5001 :roles ["pin"] :labels {:zone "jp" :tier "edge"}}
           {:name "levi" :ip "100.0.0.3" :roles ["compute"] :labels {:zone "us"}}]})

(def connect
  {:default-class :native
   :classes {:native {:read [:http] :live [:quic :webrtc]}
             :browser {:read [:http] :live [:webrtc]}}})

(deftest p2p-and-webrtc-port-defaults
  (is (true? (plan/operator-seed-missing? nil)))
  (is (true? (plan/operator-seed-missing? "")))
  (is (false? (plan/operator-seed-missing? "seed")))
  (is (= :missing-operator-seed-hex (plan/provision-command-error nil)))
  (is (= :missing-operator-seed (plan/mesh-command-error nil)))
  (is (= 4001 (plan/node-p2p-port fleet (first (:nodes fleet)))))
  (is (= 5001 (plan/node-p2p-port fleet (second (:nodes fleet)))))
  (is (= 4101 (plan/node-webrtc-port fleet connect (first (:nodes fleet)))))
  (is (nil? (plan/node-webrtc-port fleet nil (first (:nodes fleet))))))

(deftest bootstrap-string-excludes-self-and-sorts-by-fleet-order
  (let [peers {"asher" "peer-a" "judah" "peer-j" "levi" "peer-l"}]
    (is (= "peer-a@/ip4/100.0.0.1/udp/4001/quic-v1,peer-l@/ip4/100.0.0.3/udp/4001/quic-v1"
           (plan/bootstrap-str fleet peers (second (:nodes fleet)))))))

(deftest peer-id-log-parsing
  (is (= "12D3KooWPeerId123"
         (plan/peer-id-from-log "node_did=did:key:12D3KooWPeerId123\n")))
  (is (= "12D3KooWPeerId123"
         (plan/peer-id-from-log "noise\ndid:key:12D3KooWPeerId123 trailing\n")))
  (is (nil? (plan/peer-id-from-log "did:key:zOther")))
  (is (= {"asher" "peer-a" "levi" "peer-l"}
         (plan/collected-peers [[{:name "asher"} "peer-a"]
                                [{:name "judah"} nil]
                                [{:name "levi"} "peer-l"]]))))

(deftest peer-probe-target-selection
  (let [nodes [{:name "asher" :host "asher.local" :ip "100.0.0.1"}
               {:name "judah" :host "judah.local"}
               {:name "levi" :host "levi.local" :ip "100.0.0.3"}]]
    (is (= [(first nodes)]
           (plan/peer-probe-targets nodes #(= "asher.local" (:host %)))))
    (is (= [{:node (first nodes) :host "asher.local"}]
           (plan/peer-probe-plan nodes #(= "asher.local" (:host %)))))
    (is (= [[(first nodes) "peer-asher"]]
           (plan/peer-probe-results
            nodes
            #(= "asher.local" (:host %))
            #(str "peer-" (first (str/split % #"\."))))))
    (is (= {"asher" "peer-a"}
           (plan/collected-peers-from-results [[(first nodes) "peer-a"]
                                               [(second nodes) nil]])))))

(deftest remote-provision-commands-are-stable
  (is (= 8000 plan/peer-advertise-wait-ms))
  (is (= "test -x $HOME/.murakumo/bin/kotoba-server && echo installed || echo absent"
         (plan/mesh-binary-status-command)))
  (is (= "mkdir -p $HOME/.murakumo/bin $HOME/.murakumo/store"
         (plan/remote-store-command)))
  (is (= ["rsync" "-az" "-e" "ssh -o BatchMode=yes -o ConnectTimeout=8"
          "/local/bin/kotoba"
          "asher:.murakumo/bin/kotoba"]
         (plan/rsync-binary-argv "/local/bin" "asher" "kotoba")))
  (is (= "sudo launchctl print system/com.murakumo.kotoba-mesh >/dev/null 2>&1 && echo running || echo stopped"
         (plan/launch-status-command)))
  (is (= "grep -ho 'did:key:12D3[A-Za-z0-9]*' ~/.murakumo/mesh.log 2>/dev/null | tail -1"
         (plan/peer-id-log-command)))
  (is (= "grep 'kotoba-net: peer connected' ~/.murakumo/mesh.log 2>/dev/null | grep -o '12D3[A-Za-z0-9]*' | sort -u | wc -l"
         (plan/live-link-count-command)))
  (is (= "3" (plan/live-link-count-output " 3\n")))
  (is (= "sudo tee /Library/LaunchDaemons/com.murakumo.kotoba-mesh.plist >/dev/null <<'PLIST'\n<plist/>\nPLIST"
         (plan/write-plist-command "<plist/>"))))

(deftest render-plist-replaces-all-control-plane-placeholders
  (let [template "{{USER}}|{{BIN}}|{{PORT}}|{{ROLES}}|{{LABELS}}|{{HOME}}|{{ED25519}}|{{X25519}}|{{DID}}|{{P2PPORT}}|{{P2PSEED}}|{{EXTADDR}}|{{BOOTSTRAP}}|{{WEBRTC}}"
        out (plan/render-plist template fleet connect {"judah" "peer-j"}
                               (first (:nodes fleet))
                               {:user "ops"
                                :home "/Users/ops"
                                :operator-seed "ed"
                                :x25519-seed "x"
                                :did "did:key:z"
                                :p2p-seed "p2p"})]
    (is (= "ops|/Users/ops/.murakumo/bin|8077|compute|zone=jp|/Users/ops|ed|x|did:key:z|4001|p2p|/ip4/100.0.0.1/udp/4001/quic-v1|peer-j@/ip4/100.0.0.2/udp/5001/quic-v1|4101"
           out))))

(deftest launch-commands-are-stable
  (let [node {:name "asher" :host "asher.local"}]
    (is (= "sudo launchctl bootout system/com.murakumo.kotoba-mesh"
           (plan/launch-command :down)))
    (is (re-find #"kickstart -k system/com\.murakumo\.kotoba-mesh"
                 (plan/launch-command :up)))
    (is (= {:node node
            :host "asher.local"
            :command "sudo launchctl bootout system/com.murakumo.kotoba-mesh"}
           (plan/launch-plan node :down)))
    (is (= [(plan/launch-plan node :down)]
           (plan/launch-plans [node] :down)))
    (is (= [[node {:exit 0 :host "asher.local"}]]
           (plan/launch-results
            [node]
            :down
            (fn [host command]
              (when (= "sudo launchctl bootout system/com.murakumo.kotoba-mesh" command)
                {:exit 0 :host host})))))
    (is (re-find #"bootstrap system /Library/LaunchDaemons/com\.murakumo\.kotoba-mesh\.plist"
                 (plan/reprovision-command)))))
