;; murakumo.overlay-stream-test — stream multiplexing model tests.

(ns murakumo.overlay-stream-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.stream :as stream]))

(def session
  {:overlay "bafyOverlay"
   :node "bafyNode"
   :name "local"
   :principal {:from "operator" :to "fleet" :capability "ssh"}})

(deftest stream-frames-are-ordered-and-addressed
  (let [s (stream/open-stream session "ssh" 100)
        result (stream/frames s ["a" "b"])]
    (is (= "murakumo.overlay.stream" (:type s)))
    (is (= 64 (:window s)))
    (is (= [0 1] (mapv :seq (:frames result))))
    (is (= [(:id s) (:id s)] (mapv :stream (:frames result))))
    (is (= 2 (get-in result [:stream :next-seq])))
    (is (= {:type "murakumo.overlay.stream-ack"
            :stream (:id s)
            :seq 0
            :accepted? true}
           (stream/ack (first (:frames result)) true)))))
