(ns murakumo.infer-schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.schedule :as sched]))

(def GiB (* 1024 1024 1024))

(defn node [name & {:keys [ckpts free queue engines]
                    :or {ckpts #{} free (* 13 GiB) queue 0 engines #{:comfyui}}}]
  {:name name :engines engines :checkpoints ckpts :free-bytes free :queue queue})

(def sdxl {:model/engine :comfyui :model/checkpoint "animagine-xl-4.0.safetensors"
           :model/min-free-bytes (* 8 GiB)})
(def wan {:model/engine :comfyui :model/checkpoint "wan2.2-i2v.safetensors"
          :model/min-free-bytes (* 12 GiB)})

(deftest eligibility
  (testing "needs the engine, the checkpoint (or fetch), and free memory"
    (is (sched/eligible? (node "a" :ckpts #{"animagine-xl-4.0.safetensors"}) sdxl))
    (is (not (sched/eligible? (assoc (node "a") :engines #{:llamacpp}) sdxl)))
    (is (not (sched/eligible? (node "a" :free (* 4 GiB)) wan))
        "4 GiB free cannot host a 12 GiB-min video model")))

(deftest warm-preferred
  (testing "a node already holding the checkpoint beats a cold fetcher"
    (let [warm (node "warm" :ckpts #{"animagine-xl-4.0.safetensors"} :queue 1)
          cold (node "cold" :queue 0)]
      (is (= "warm" (:name (sched/pick [cold warm] sdxl)))))))

(deftest least-loaded
  (testing "among warm nodes, fewest queued jobs wins; free memory breaks ties"
    (let [a (node "a" :ckpts #{"animagine-xl-4.0.safetensors"} :queue 2 :free (* 13 GiB))
          b (node "b" :ckpts #{"animagine-xl-4.0.safetensors"} :queue 0 :free (* 10 GiB))
          c (node "c" :ckpts #{"animagine-xl-4.0.safetensors"} :queue 0 :free (* 13 GiB))]
      (is (= "c" (:name (sched/pick [a b c] sdxl)))))))

(deftest batch-spreads
  (testing "a batch of 4 jobs spreads across the warm minis, not all onto one"
    (let [nodes (mapv #(node % :ckpts #{"animagine-xl-4.0.safetensors"})
                      ["dan" "zebulun" "joseph"])
          jobs (repeat 4 {:model sdxl})
          assigned (sched/assign nodes jobs)
          used (frequencies (map :node assigned))]
      (is (every? some? (map :node assigned)))
      (is (= 3 (count used)) "all three minis get work")
      (is (<= (apply max (vals used)) 2) "no mini gets more than 2 of 4"))))

(deftest none-eligible
  (testing "no eligible node → nil, not a crash"
    (is (nil? (sched/pick [(assoc (node "a") :engines #{:foo})] sdxl)))
    (is (nil? (:node (first (sched/assign [] [{:model sdxl}])))))))
