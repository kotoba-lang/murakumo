(ns murakumo.infer-expert-stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.expert-stream :as expert]
            [murakumo.infer.plan :as plan]))

(def model
  {:model/id "qwen3.8-flash-next-ud-iq3-xxs"
   :model/family "Qwen3.8-Flash-Next"
   :model/weight-bytes 81961823936
   :model/target-node "dan"
   :model/layers 48 :model/experts 512 :model/active-experts 10
   :model/gguf "Qwen3.8-Flash-Next-UD-IQ3_XXS-00001-of-00003.gguf"})

(deftest planner-gates-on-whole-sharded-artifact
  (let [small {:name "small" :mem-bytes (* 16 plan/GiB)
               :disk-free-bytes (* 80 plan/GiB)}
        dan {:name "dan" :mem-bytes (* 16 plan/GiB)
             :disk-free-bytes (* 94 plan/GiB)}
        result (expert/plan model [small dan])]
    (is (:fits? result))
    (is (= "dan" (get-in result [:node :name])))
    (is (= :expert-aware-nvme (:engine result)))
    (is (zero? (:cache-mib result)))
    (is (false? (:page-cache-bypassed? result)))
    (is (false? (:mtp-enabled? result)))))

(deftest planner-credits-only-observed-artifact-bytes
  (let [node {:name "dan" :mem-bytes (* 16 plan/GiB)
              :disk-free-bytes (* 12 plan/GiB)
              :model-present-bytes (:model/weight-bytes model)}
        result (expert/plan model [node])]
    (is (:fits? result))
    (is (= expert/disk-reserve-bytes (:required-disk-bytes result)))))

(deftest command-keeps-the-measured-cell-lossless
  (let [argv (expert/command {:bin "bmoe-cli" :model-path "/m/first.gguf"
                              :prompt "hello" :tokens 32 :csv "/tmp/a.csv"})
        text (clojure.string/join " " argv)]
    (is (re-find #"--moe-stream" text))
    (is (re-find #"--cache-mb 0" text))
    (is (not (re-find #"--drop-cold-experts" text)))
    (is (re-find #"--prefetch 0" text))
    (is (not (re-find #"--n-expert-used" text)))))

(deftest qualification-requires-real-token-parity
  (is (:speed-qualified?
       (expert/qualify {:off-token-ids [1 2] :on-token-ids [1 2]
                        :on-repeat-token-ids [1 2]
                        :off-tok-s 1.0 :on-tok-s 1.5})))
  (is (false? (:execution-qualified?
               (expert/qualify {:off-token-ids [1] :on-token-ids [2]
                                :on-repeat-token-ids [2]
                                :off-tok-s 1.0 :on-tok-s 2.0})))))
