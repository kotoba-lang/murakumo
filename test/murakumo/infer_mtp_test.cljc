(ns murakumo.infer-mtp-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.mtp :as mtp]))

(def off {:token-ids [10 11 12 13] :generated-tokens 4 :elapsed-ms 400})
(def on {:token-ids [10 11 12 13] :generated-tokens 4 :elapsed-ms 250
         :fully-offloaded? true :drafted-tokens 9 :accepted-tokens 5})

(deftest explicit-canary-plan-carries-mtp-contract
  (let [plan (mtp/candidate-plan
              {:home "/Users/asher" :llama-server "/opt/llama-server"
               :memory-bytes (* 16 1073741824)})]
    (is (:admitted? plan))
    (is (= "murakumo-edge-mtp-canary" (:model-id plan)))
    (is (some #{"draft-mtp"} (:argv plan)))))

(deftest qualifies-only-observed-lossless-speedup
  (let [result (mtp/qualify {:off off :on on :on-repeat on})]
    (is (:execution-qualified? result))
    (is (:speed-qualified? result))
    (is (= 10.0 (:off-end-to-end-tok-s result)))
    (is (= 16.0 (:on-end-to-end-tok-s result)))
    (is (= 1.6 (:speedup result)))))

(deftest rejects-theater
  (testing "flags without observed draft acceptance do not qualify"
    (is (false? (:execution-qualified?
                 (mtp/qualify {:off off :on (dissoc on :accepted-tokens)
                               :on-repeat on})))))
  (testing "different token IDs fail even when MTP is faster"
    (is (false? (:execution-qualified?
                 (mtp/qualify {:off off :on (assoc on :token-ids [10 99])
                               :on-repeat (assoc on :token-ids [10 99])}))))))
