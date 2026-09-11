;; murakumo.overlay-driver-test — offline tests for native overlay driver planning.

(ns murakumo.overlay-driver-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.driver :as driver]))

(def dial-argv
  ["dial"
   "--overlay" "bafyOverlay"
   "--node" "bafyNode"
   "--name" "asher"
   "--from" "operator"
   "--to" "fleet"
   "--capability" "ssh"
   "--direct" "quic://asher:4001"
   "--transport" "quic"
   "--relay" "relay://jp/bafyNode"
   "--relay-transport" "quic"])

(deftest canonical-driver-argv-parses-to-session
  (let [opts (driver/parse-argv dial-argv)
        result (driver/dial-result opts)]
    (is (= :dial (:command opts)))
    (is (= "bafyOverlay" (:overlay opts)))
    (is (= :quic (driver/endpoint-kind "quic://asher:4001")))
    (is (= :relay (driver/endpoint-kind "relay://jp/bafyNode")))
    (is (true? (:ok? result)))
    (is (= {:type "murakumo.overlay.session"
            :overlay "bafyOverlay"
            :node "bafyNode"
            :name "asher"
            :principal {:from "operator" :to "fleet" :capability "ssh"}
            :direct {:transport "quic"
                     :endpoint "quic://asher:4001"
                     :kind :quic}
            :relay {:transport "quic"
                    :endpoint "relay://jp/bafyNode"
                    :kind :relay}}
           (:session result)))))

(deftest argv-parser-accepts-inline-options
  (is (= {:command :dial-check
          :via "relay"
          :overlay "bafyOverlay"}
         (driver/parse-argv ["dial-check" "--via=relay" "--overlay=bafyOverlay"]))))

(deftest driver-validation-is-explicit
  (is (= {:ok? false :reason :unknown-command :command :listen}
         (driver/command-result (driver/parse-argv ["listen"]))))
  (is (= {:ok? false
          :reason :missing-options
          :missing [:node :name :from :to :capability :direct :transport]}
         (driver/dial-result (driver/parse-argv ["dial" "--overlay" "bafyOverlay"]))))
  (is (re-find #"murakumo-overlay error: missing-options"
               (first (driver/command-lines ["dial" "--overlay" "bafyOverlay"])))))

(deftest canonical-relay-argv-parses-to-relay-session
  (let [argv ["relay"
              "--overlay" "bafyOverlay"
              "--name" "jp-1"
              "--region" "jp"
              "--url" "relay://jp"
              "--transports" "quic,webrtc"]
        result (driver/command-result (driver/parse-argv argv))]
    (is (true? (:ok? result)))
    (is (= {:type "murakumo.overlay.relay"
            :overlay "bafyOverlay"
            :name "jp-1"
            :region "jp"
            :url "relay://jp"
            :transports ["quic" "webrtc"]}
           (:session result)))))

(deftest bootstrap-manifest-validates-every-step-in-order
  (let [manifest {:$type "cloud.murakumo.bootstrap"
                  :overlay "bafyOverlay"
                  :driver "murakumo-overlay"
                  :phases [{:name :relays
                            :steps [{:phase :relay
                                     :target "jp-1"
                                     :argv ["murakumo-overlay" "relay"
                                            "--overlay" "bafyOverlay"
                                            "--name" "jp-1"
                                            "--region" "jp"
                                            "--url" "relay://jp"
                                            "--transports" "quic"]}]}
                           {:name :connects
                            :steps [{:phase :connect
                                     :target "asher"
                                     :argv dial-argv}]}]}
        result (driver/command-result
                (driver/parse-argv ["bootstrap" "--manifest-file" "bootstrap.edn"])
                (fn [path]
                  (when (= "bootstrap.edn" path)
                    manifest)))]
    (is (true? (:ok? result)))
    (is (= "murakumo.overlay.bootstrap" (get-in result [:session :type])))
    (is (= [:relays :connects]
           (mapv :name (get-in result [:session :phases]))))
    (is (every? :ok? (get-in result [:session :phases 0 :steps])))
    (is (every? :ok? (get-in result [:session :phases 1 :steps])))
    (is (= {:ok? false :reason :invalid-manifest}
           (driver/command-result
            (driver/parse-argv ["bootstrap" "--manifest-file" "empty.edn"])
            (constantly nil))))))

(deftest run-command-builds-dry-run-plan
  (let [manifest {:$type "cloud.murakumo.bootstrap"
                  :overlay "bafyOverlay"
                  :driver "murakumo-overlay"
                  :phases [{:name :relays
                            :steps [{:phase :relay
                                     :target "jp-1"
                                     :argv ["murakumo-overlay" "relay"
                                            "--overlay" "bafyOverlay"
                                            "--name" "jp-1"
                                            "--region" "jp"
                                            "--url" "relay://jp"
                                            "--transports" "quic"]}]}
                           {:name :connects
                            :steps [{:phase :connect
                                     :target "asher"
                                     :argv dial-argv}]}]}
        result (driver/command-result
                (driver/parse-argv ["run" "--manifest-file" "bootstrap.edn"])
                (constantly manifest))]
    (is (true? (:ok? result)))
    (is (= "murakumo.overlay.run-plan" (get-in result [:session :type])))
    (is (= :dry-run (get-in result [:session :mode])))
    (is (= [:relays :connects]
           (mapv :name (get-in result [:session :phases]))))
    (is (= [:run]
           (mapv :action (get-in result [:session :phases 0 :steps]))))
    (is (= [:relay]
           (mapv :phase (get-in result [:session :phases 0 :steps]))))
    (is (= [:relays]
           (mapv :phase-group (get-in result [:session :phases 0 :steps]))))
    (is (= [:run]
           (mapv :action (get-in result [:session :phases 1 :steps]))))))

(deftest dispatch-command-attaches-runtime-adapters
  (let [manifest {:$type "cloud.murakumo.bootstrap"
                  :overlay "bafyOverlay"
                  :driver "murakumo-overlay"
                  :phases [{:name :relays
                            :steps [{:phase :relay
                                     :target "jp-1"
                                     :argv ["murakumo-overlay" "relay"
                                            "--overlay" "bafyOverlay"
                                            "--name" "jp-1"
                                            "--region" "jp"
                                            "--url" "relay://jp"
                                            "--transports" "quic"]}]}
                           {:name :connects
                            :steps [{:phase :connect
                                     :target "asher"
                                     :argv dial-argv}]}]}
        result (driver/command-result
                (driver/parse-argv ["dispatch" "--manifest-file" "bootstrap.edn"])
                (constantly manifest))]
    (is (true? (:ok? result)))
    (is (= "murakumo.overlay.dispatch-plan" (get-in result [:session :type])))
    (is (= ["murakumo.runtime.relay"]
           (mapv :adapter (get-in result [:session :phases 0 :steps]))))
    (is (= ["murakumo.runtime.quic"]
           (mapv :adapter (get-in result [:session :phases 1 :steps]))))))

(deftest execute-command-builds-execution-report
  (let [manifest {:$type "cloud.murakumo.bootstrap"
                  :overlay "bafyOverlay"
                  :driver "murakumo-overlay"
                  :phases [{:name :relays
                            :steps [{:phase :relay
                                     :target "jp-1"
                                     :argv ["murakumo-overlay" "relay"
                                            "--overlay" "bafyOverlay"
                                            "--name" "jp-1"
                                            "--region" "jp"
                                            "--url" "relay://jp"
                                            "--transports" "quic"]}]}
                           {:name :connects
                            :steps [{:phase :connect
                                     :target "asher"
                                     :argv dial-argv}]}]}
        result (driver/command-result
                (driver/parse-argv ["execute" "--manifest-file" "bootstrap.edn"])
                (constantly manifest)
                (fn [step] {:ok? true :argv (:argv step) :adapter (:adapter step) :pid 42}))]
    (is (true? (:ok? result)))
    (is (= "murakumo.overlay.execution-report" (get-in result [:session :type])))
    (is (= :execute (get-in result [:session :mode])))
    (is (true? (get-in result [:session :ok?])))
    (is (= [42]
           (mapv #(get-in % [:execution :pid])
                 (get-in result [:session :phases 0 :steps]))))
    (is (= ["murakumo.runtime.relay"]
           (mapv #(get-in % [:execution :adapter])
                 (get-in result [:session :phases 0 :steps]))))
    (is (= [:relays :connects]
           (mapv :name (get-in result [:session :phases]))))))
