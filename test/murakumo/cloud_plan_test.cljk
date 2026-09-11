;; murakumo.cloud-plan-test — offline tests for murakumo.cloud overlay planning.

(ns murakumo.cloud-plan-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.cloud.plan :as cloud]
            [murakumo.identity :as identity]))

(def fleet
  {:fleet/name "test-fleet"
   :nodes [{:name "asher" :roles ["compute"] :labels {:zone "jp"}}
           {:name "judah" :roles ["pin"] :labels {:zone "us"}}]})

(def spec
  {:cloud/name "murakumo.cloud"
   :cloud/domain "murakumo.cloud"
   :cloud/graph "murakumo-cloud"
   :overlay/id "test-overlay"
   :overlay/address-family :identity
   :overlay/direct [:quic :webrtc]
   :relays [{:name "jp-1" :region "jp" :url "relay://jp" :transports [:quic]}
            {:name "us-1" :region "us" :url "relay://us" :transports [:webrtc]}]
   :policy {:default :deny
            :allow [{:from :operator :to :fleet :capabilities [:ssh]}]}})

(deftest cloud-overlay-ids-are-stable
  (is (= (identity/graph-cid "test-overlay")
         (cloud/overlay-id spec)))
  (is (= (identity/graph-cid (str (cloud/overlay-id spec) ":asher"))
         (cloud/node-id spec {:name "asher"}))))

(deftest relay-choice-prefers-node-region
  (is (= "jp-1" (:name (cloud/choose-relay spec {:name "asher" :labels {:zone "jp"}}))))
  (is (= "us-1" (:name (cloud/choose-relay spec {:name "judah" :labels {:zone "us"}}))))
  (is (= "jp-1" (:name (cloud/choose-relay spec {:name "levi" :labels {:zone "eu"}})))))

(deftest cloud-plan-records-are-stable
  (let [plan (cloud/cloud-plan fleet spec)
        records (cloud/plan-records plan)
        asher (first (:nodes plan))]
    (is (= "murakumo.cloud" (:cloud plan)))
    (is (= :identity (:address_family plan)))
    (is (= 2 (count (:relays plan))))
    (is (= 2 (count (:nodes plan))))
    (is (= 2 (count (:routes plan))))
    (is (= 7 (count records)))
    (is (= {:$type "cloud.murakumo.node"
            :overlay (cloud/overlay-id spec)
            :node (cloud/node-id spec {:name "asher"})
            :name "asher"
            :fleet "test-fleet"
            :region "jp"
            :roles ["compute"]
            :labels {:zone "jp"}
            :direct [:quic :webrtc]
            :relay "jp-1"
            :relay_url "relay://jp"
            :capabilities [:ssh :http :gossip :deploy :reconcile]}
           asher))
    (is (= {:$type "cloud.murakumo.policy"
            :overlay (cloud/overlay-id spec)
            :default :deny
            :allow [{:from :operator :to :fleet :capabilities [:ssh]}]}
           (:policy plan)))))

(deftest cloud-route-records-are-stable
  (let [plan (cloud/cloud-plan fleet spec)
        asher-route (cloud/route-for plan "asher")]
    (is (= {:$type "cloud.murakumo.route"
            :overlay (cloud/overlay-id spec)
            :node (cloud/node-id spec {:name "asher"})
            :name "asher"
            :direct [{:transport :quic :endpoint "quic://asher:4001"}
                     {:transport :webrtc :endpoint "webrtc://asher:4101"}]
            :relay {:relay "jp-1"
                    :transport :quic
                    :endpoint (str "relay://jp/" (cloud/node-id spec {:name "asher"}))}}
           asher-route))
    (is (nil? (cloud/route-for plan "missing")))
    (is (= "jp-1" (:name (cloud/relay-for plan "jp-1"))))
    (is (nil? (cloud/relay-for plan "missing")))))

(deftest cloud-dial-policy-is-default-deny
  (let [plan (cloud/cloud-plan fleet spec)]
    (is (true? (cloud/policy-allows? (:policy plan) :operator :fleet :ssh)))
    (is (false? (cloud/policy-allows? (:policy plan) :browser :fleet :ssh)))
    (is (= {:from :operator :to :fleet :capability :ssh}
           (cloud/dial-request {})))
    (is (= :allowed
           (:reason (cloud/dial-plan plan "asher" {}))))
    (is (= :policy-denied
           (:reason (cloud/dial-plan plan "asher" {:from :browser :capability :ssh}))))
    (is (= :unknown-node
           (:reason (cloud/dial-plan plan "missing" {}))))))

(deftest cloud-connect-plan-produces-driver-argv
  (let [plan (cloud/cloud-plan fleet spec)
        connect (cloud/connect-plan plan "asher" {})
        denied (cloud/connect-plan plan "asher" {:from :browser :capability :ssh})]
    (is (= "murakumo-overlay" (:driver connect)))
    (is (= ["murakumo-overlay" "dial"
            "--overlay" (cloud/overlay-id spec)
            "--node" (cloud/node-id spec {:name "asher"})
            "--name" "asher"
            "--from" "operator"
            "--to" "fleet"
            "--capability" "ssh"
            "--direct" "quic://asher:4001"
            "--transport" "quic"
            "--relay" (str "relay://jp/" (cloud/node-id spec {:name "asher"}))
            "--relay-transport" "quic"]
           (:argv connect)))
    (is (nil? (:argv denied)))))

(deftest cloud-relay-plan-produces-driver-argv
  (let [plan (cloud/cloud-plan fleet spec)
        relay (cloud/relay-plan plan "jp-1" {})
        missing (cloud/relay-plan plan "missing" {})]
    (is (= ["murakumo-overlay" "relay"
            "--overlay" (cloud/overlay-id spec)
            "--name" "jp-1"
            "--region" "jp"
            "--url" "relay://jp"
            "--transports" "quic"]
           (:argv relay)))
    (is (= :ready (:reason relay)))
    (is (= :unknown-relay (:reason missing)))
    (is (nil? (:argv missing)))))

(deftest cloud-auth-key-is-carried-only-to-driver-argv
  (let [plan (cloud/cloud-plan fleet (assoc spec :overlay/auth-key "shared-secret"))
        connect (cloud/connect-plan plan "asher" {})
        relay (cloud/relay-plan plan "jp-1" {})
        records (cloud/plan-records plan)]
    (is (= "shared-secret" (:auth-key plan)))
    (is (= ["--auth-key" "shared-secret"]
           (take-last 2 (:argv connect))))
    (is (= ["--auth-key" "shared-secret"]
           (take-last 2 (:argv relay))))
    (is (not-any? :auth-key records))))

(deftest cloud-auth-key-source-is-control-plane-metadata-only
  (let [plan (cloud/cloud-plan fleet spec)
        records (cloud/plan-records plan)]
    (is (= :operator-seed (:auth-key-source plan)))
    (is (= "MURAKUMO_OVERLAY_AUTH_KEY" (:auth-key-env plan)))
    (is (nil? (:auth-key plan)))
    (is (not-any? :auth-key-source records))
    (is (not-any? :auth-key-env records))))

(deftest cloud-bootstrap-plan-orders-relays-before-connects
  (let [plan (cloud/cloud-plan fleet spec)
        bootstrap (cloud/bootstrap-plan plan {})
        manifest (cloud/bootstrap-manifest plan {})]
    (is (= ["jp-1" "us-1"]
           (mapv (comp :name :relay) (:relays bootstrap))))
    (is (= ["asher" "judah"]
           (mapv (comp :name :route) (:connects bootstrap))))
    (is (every? :argv (:relays bootstrap)))
    (is (every? :argv (:connects bootstrap)))
    (is (= "cloud.murakumo.bootstrap" (:$type manifest)))
    (is (= [:relays :connects] (mapv :name (:phases manifest))))
    (is (= [:relay :relay]
           (mapv :phase (get-in manifest [:phases 0 :steps]))))
    (is (= [:connect :connect]
           (mapv :phase (get-in manifest [:phases 1 :steps]))))))

(deftest cloud-cli-shapes-are-stable
  (let [plan (cloud/cloud-plan fleet spec)
        lines (cloud/summary-lines plan)]
    (is (re-find #"murakumo.cloud murakumo.cloud  overlay" (first lines)))
    (is (some #(re-find #"asher\s+jp\s+jp-1\s+quic,webrtc" %) lines))
    (is (= lines (cloud/command-lines :plan plan)))
    (is (some #(re-find #"asher\s+quic,webrtc\s+jp-1" %)
              (cloud/command-lines :routes plan)))
    (is (some #(re-find #"authorized: from=operator to=fleet capability=ssh" %)
              (cloud/command-lines :dial plan "asher")))
    (is (some #(re-find #"webrtc\s+webrtc://asher:4101" %)
              (cloud/command-lines :dial plan "asher")))
    (is (some #(re-find #"denied by policy" %)
              (cloud/command-lines :dial plan "asher" {:from :browser :capability :ssh})))
    (is (some #(re-find #"murakumo-overlay dial --overlay" %)
              (cloud/command-lines :connect plan "asher")))
    (is (some #(re-find #"murakumo-overlay relay --overlay" %)
              (cloud/command-lines :relay plan "jp-1")))
    (is (some #(re-find #"murakumo.cloud bootstrap overlay" %)
              (cloud/command-lines :bootstrap plan)))
    (is (some #(re-find #"relays:" %)
              (cloud/command-lines :bootstrap plan)))
    (is (some #(re-find #"connects:" %)
              (cloud/command-lines :bootstrap plan)))
    (is (some #(re-find #"cloud.murakumo.bootstrap" %)
              (cloud/command-lines :bootstrap plan nil {:format :edn})))
    (is (some #(re-find #"denied by policy" %)
              (cloud/command-lines :connect plan "asher" {:from :browser :capability :ssh})))
    (is (= (mapv pr-str (cloud/plan-records plan))
           (cloud/command-lines :records plan)))
    (is (= {:command :records
            :cloud-path "prod.edn"
            :fleet-path "fleet-prod.edn"}
           (cloud/parse-flags ["records" "--cloud=prod.edn" "--fleet=fleet-prod.edn"])))
    (is (= {:command :dial
            :cloud-path "cloud.edn"
            :fleet-path "fleet.edn"
            :target "asher"
            :from :browser
            :capability :live}
           (cloud/parse-flags ["dial" "asher" "--from=browser" "--capability=live"])))
    (is (= {:command :connect
            :cloud-path "cloud.edn"
            :fleet-path "fleet.edn"
            :target "asher"
            :driver "murakumo-net"}
           (cloud/parse-flags ["connect" "asher" "--driver=murakumo-net"])))
    (is (= {:command :relay
            :cloud-path "cloud.edn"
            :fleet-path "fleet.edn"
            :target "jp-1"}
           (cloud/parse-flags ["relay" "jp-1"])))
    (is (= {:command :bootstrap
            :cloud-path "cloud.edn"
            :fleet-path "fleet.edn"
            :format :edn}
           (cloud/parse-flags ["bootstrap" "--format=edn"])))
    (is (= {:command :bootstrap
            :cloud-path "cloud.edn"
            :fleet-path "fleet.edn"
            :auth-key "shared-secret"}
           (cloud/parse-flags ["bootstrap" "--auth-key=shared-secret"])))))
