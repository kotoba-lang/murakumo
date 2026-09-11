;; murakumo.overlay-runtime-test — runtime adapter boundary checks.

(ns murakumo.overlay-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.runtime :as runtime]))

(deftest runtime-adapter-registry-is-explicit
  (is (runtime/known-adapter? "murakumo.runtime.relay"))
  (is (runtime/known-adapter? "murakumo.runtime.quic"))
  (is (runtime/known-adapter? "murakumo.runtime.webrtc"))
  (is (runtime/known-adapter? "murakumo.runtime.webtransport"))
  (is (runtime/known-adapter? "murakumo.runtime.relay-client"))
  (is (= :placeholder (:status (runtime/adapter "murakumo.runtime.quic"))))
  (is (= #{"murakumo.runtime.relay"
           "murakumo.runtime.quic"
           "murakumo.runtime.webrtc"
           "murakumo.runtime.webtransport"
           "murakumo.runtime.relay-client"}
         (set (map :adapter (runtime/adapter-records))))))

(deftest execute-step-returns-stable-placeholder-contract
  (let [step {:adapter "murakumo.runtime.quic"
              :runtime :quic
              :argv ["murakumo-overlay" "dial"]
              :session {:type "murakumo.overlay.session"
                        :direct {:kind :quic}}}]
    (is (= {:ok? true
            :mode :would-run
            :adapter "murakumo.runtime.quic"
            :runtime :quic
            :opens :identity-stream
            :status :placeholder
            :listen nil
            :connect {:endpoint :direct
                      :kind :quic
                      :transport nil
                      :host nil
                      :port 4001
                      :path nil
                      :overlay nil
                      :node nil
                      :name nil
                      :principal nil}
            :argv ["murakumo-overlay" "dial"]
            :session {:type "murakumo.overlay.session"
                      :direct {:kind :quic}}}
           (runtime/execute-step step)))))

(deftest relay-listen-spec-derives-bind-settings
  (is (= {:bind-host "0.0.0.0"
          :advertise-host "jp-tyo-1.murakumo.cloud"
          :port 4701
          :transports ["quic" "webrtc"]}
         (runtime/relay-listen-spec
          {:url "relay://jp-tyo-1.murakumo.cloud"
           :transports ["quic" "webrtc"]})))
  (is (= {:bind-host "127.0.0.1"
          :advertise-host "127.0.0.1"
          :port 0
          :transports ["quic"]}
         (runtime/relay-listen-spec
          {:url "relay://127.0.0.1:7777"
           :bind-host "127.0.0.1"
           :port 0
           :transports ["quic"]}))))

(deftest dial-connect-spec-derives-target
  (is (= {:endpoint :direct
          :kind :quic
          :transport "quic"
          :host "127.0.0.1"
          :port 4567
          :path nil
          :overlay "bafyOverlay"
          :node "bafyNode"
          :name "local"
          :principal {:from "operator" :to "fleet" :capability "ssh"}}
         (runtime/dial-connect-spec
          {:overlay "bafyOverlay"
           :node "bafyNode"
           :name "local"
           :principal {:from "operator" :to "fleet" :capability "ssh"}
           :direct {:transport "quic"
                    :kind :quic
                    :endpoint "quic://127.0.0.1:4567"}})))
  (is (= {:endpoint :relay
          :kind :relay
          :transport "quic"
          :host "relay.local"
          :port 4701
          :path "/bafyNode"
          :overlay nil
          :node nil
          :name nil
          :principal nil}
         (runtime/dial-connect-spec
          {:relay {:transport "quic"
                   :kind :relay
                   :endpoint "relay://relay.local/bafyNode"}}
          :relay))))

(deftest execute-step-rejects-unknown-adapter
  (is (= {:ok? false
          :mode :adapter-missing
          :adapter "murakumo.runtime.custom"
          :reason :unknown-adapter
          :argv ["murakumo-overlay" "dial"]}
         (runtime/execute-step {:adapter "murakumo.runtime.custom"
                                :argv ["murakumo-overlay" "dial"]}))))
