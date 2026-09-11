;; murakumo.overlay-peer-test — peer discovery state tests.

(ns murakumo.overlay-peer-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.peer :as peer]))

(def route
  {:overlay "bafyOverlay"
   :node "bafyNode"
   :name "asher"
   :direct [{:transport :quic :endpoint "quic://asher:4001"}]
   :relay {:relay "jp-1" :transport :quic :endpoint "relay://jp/bafyNode"}})

(deftest peer-catalog-remembers-routes-and-fallbacks
  (let [peers (peer/catalog [route])
        p (get peers "bafyNode")]
    (is (= "asher" (:name p)))
    (is (= :unknown (:health p)))
    (is (= :direct (:via (peer/choose-path p))))
    (is (= :relay (:via (peer/choose-path (assoc p :health :down)))))
    (is (= "asher" (:name (peer/by-name peers "asher"))))
    (is (= :seen (get-in (peer/remember {} route 42) ["bafyNode" :health])))))
