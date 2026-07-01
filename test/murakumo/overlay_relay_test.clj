;; murakumo.overlay-relay-test — host relay listener checks.

(ns murakumo.overlay-relay-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.dial :as dial]
            [murakumo.overlay.relay :as relay]))

(deftest relay-listener-can-bind-and-close
  (let [result (relay/check! {:type "murakumo.overlay.relay"
                              :overlay "bafyOverlay"
                              :name "local"
                              :region "test"
                              :url "relay://127.0.0.1:0"
                              :bind-host "127.0.0.1"
                              :port 0
                              :transports ["quic"]})]
    (is (true? (:ok? result)))
    (is (= :closed (:mode result)))
    (is (pos? (get-in result [:listen :bound-port])))
    (is (= "127.0.0.1" (get-in result [:listen :bind-host])))))

(deftest relay-frame-hardening-rejects-oversize-and-unauthenticated-frames
  (let [session {:type "murakumo.overlay.relay"
                 :overlay "bafyOverlay"
                 :name "local"
                 :region "test"
                 :auth-key "shared-secret"
                 :require-auth? true
                 :max-frame-bytes 3}
        dial-session {:type "murakumo.overlay.session"
                      :overlay "bafyOverlay"
                      :node "bafyNode"
                      :name "client"
                      :auth-key "shared-secret"}
        connect {:path "/bafyNode" :transport "quic"}
        ok-frame (dial/relay-frame dial-session connect 0 "abc")
        big-frame (dial/relay-frame dial-session connect 1 "abcd")
        plain-frame (assoc (dissoc ok-frame :sealed? :iv :ciphertext :tag :alg :mac)
                           :payload "abc")]
    (is (true? (:accepted? (relay/relay-frame-ack session ok-frame))))
    (is (false? (:accepted? (relay/relay-frame-ack session big-frame))))
    (is (false? (:size-ok? (relay/relay-frame-ack session big-frame))))
    (is (false? (:accepted? (relay/relay-frame-ack session plain-frame))))
    (is (false? (:auth-ok? (relay/relay-frame-ack session plain-frame))))))
