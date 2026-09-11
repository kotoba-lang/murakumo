;; murakumo.overlay-service-test — persistent proxy supervision model tests.

(ns murakumo.overlay-service-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.service :as service]))

(deftest service-spec-and-supervisor-plan-are-stable
  (let [spec (service/service-spec {:service "ssh"
                                    :listen "127.0.0.1:2222"
                                    :mode "bytes"
                                    :restart "always"
                                    :max-restarts "5"})
        plan (service/supervisor-plan {:overlay "bafyOverlay"
                                       :node "bafyNode"
                                       :name "local"
                                       :principal {:from "operator"
                                                   :to "fleet"
                                                   :capability "ssh"}}
                                      spec)]
    (is (= :bytes (:mode spec)))
    (is (= 5 (:max-restarts spec)))
    (is (true? (:ok? plan)))
    (is (= "ssh" (:service plan)))
    (is (= "127.0.0.1:2222" (:listen plan)))))
