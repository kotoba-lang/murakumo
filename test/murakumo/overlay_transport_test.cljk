;; murakumo.overlay-transport-test — transport adapter boundary tests.

(ns murakumo.overlay-transport-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.transport :as transport]))

(def session
  {:type "murakumo.overlay.session"
   :overlay "bafyOverlay"
   :node "bafyNode"
   :name "local"
   :principal {:from "operator" :to "fleet" :capability "ssh"}
   :direct {:transport "quic"
            :endpoint "quic://127.0.0.1:4001"
            :kind :quic}})

(deftest transport-registry-declares-native-and-external-paths
  (let [records (transport/transport-records)
        by-kind (into {} (map (juxt :transport identity) records))]
    (is (= :native (get-in by-kind [:relay :status])))
    (is (= :external-adapter (get-in by-kind [:quic :status])))
    (is (= "MURAKUMO_WEBRTC_DRIVER" (get-in by-kind [:webrtc :driver-env])))
    (is (true? (get-in by-kind [:quic :multiplex?])))))

(deftest socket-probe-rejects-invalid-endpoint
  (is (= {:ok? false
          :mode :invalid-endpoint
          :reason :invalid-endpoint}
         (transport/probe-socket! {:host nil :port nil}))))

(deftest adapter-plan-builds-executable-driver-request
  (let [plan (transport/adapter-plan session :direct :check "/bin/echo")]
    (is (true? (:ready? plan)))
    (is (= :quic (:transport plan)))
    (is (= "murakumo.overlay.adapter-request" (get-in plan [:request :type])))
    (is (= ["check" "--request-edn"] (subvec (:argv plan) 1 3)))
    (is (re-find #"murakumo.overlay.adapter-request" (last (:argv plan))))))

(deftest adapter-check-runs-command-and-reports-output
  (let [plan (transport/adapter-plan session :direct :check "/bin/echo")
        result (transport/run-adapter! plan)]
    (is (true? (:ok? result)))
    (is (= :exited (:mode result)))
    (is (= 0 (:exit result)))
    (is (re-find #"--request-edn" (:out result)))))

(deftest adapter-check-reports-missing-driver
  (let [result (transport/run-adapter!
                (transport/adapter-plan session :direct :check nil))]
    (is (= {:ok? false
            :type "murakumo.overlay.adapter-result"
            :mode :adapter-missing
            :reason :adapter-not-configured
            :transport :quic}
           result))))

(deftest adapter-supervisor-plan-carries-restart-policy
  (let [plan (transport/adapter-supervisor-plan
              session
              :direct
              {:command "/bin/echo"
               :restart :on-failure
               :max-restarts 7})]
    (is (true? (:ok? plan)))
    (is (= :on-failure (:restart plan)))
    (is (= 7 (:max-restarts plan)))
    (is (= :serve (get-in plan [:plan :request :action])))))
