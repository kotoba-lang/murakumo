(ns murakumo.infer-evolution-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.infer.evolution :as evolution]))

(def GiB (* 1024 1024 1024))
(def model {:model/id "shinka-eval" :model/layers 2 :model/weight-bytes (* 2 GiB)
            :model/kv-heads 8})
(def node {:name "reuben" :health :healthy :caps #{:inference}
           :mem-bytes (* 16 GiB) :link-gbps 10})

(deftest dispatch-refuses-an-unknown-fleet
  (is (= {:status :blocked :reason :no-healthy-inference-nodes :jobs []}
         (evolution/dispatch-plan {:model model :nodes [(assoc node :health :down)]}))))

(deftest dispatch-is-bounded-and-reproducible
  (let [plan (evolution/dispatch-plan {:candidate-id "c-1" :benchmark-id "b-1"
                                       :prompts ["first" "second"] :model model :nodes [node]})]
    (is (= :ready (:status plan)))
    (is (= 2 (count (:jobs plan))))
    (is (= {:candidate-id "c-1" :benchmark-id "b-1" :prompt-id "b-1-0"
            :prompt "first" :max-tokens 512 :reproducible true}
           (get-in plan [:jobs 0 :input])))
    (is (= "shinka/c-1/b-1/0" (get-in plan [:jobs 0 :idempotency-key])))))

(deftest dispatch-defaults-to-safe-pipeline-without-link-telemetry
  (let [plan (evolution/dispatch-plan {:candidate-id "c-2" :benchmark-id "b-2"
                                       :prompts ["only"] :model model
                                       :nodes [(dissoc node :link-gbps)]})]
    (is (= :ready (:status plan)))
    (is (= :pipeline (get-in plan [:strategy :strategy])))))
