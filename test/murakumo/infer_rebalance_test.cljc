(ns murakumo.infer-rebalance-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.rebalance :as rb]))

(def snapshot
  {:fleet "murakumo"
   :nodes [{:id "n1" :status "up" :ram-gb 16 :roles ["relay" "llama"] :disk-free "20-40G"}
           {:id "n2" :status "up" :ram-gb 16 :roles ["llama"] :disk-free "40-60G"}
           {:id "n3" :status "up" :ram-gb 16 :roles ["llama"] :disk-free "40-60G"}
           {:id "n4" :status "up" :ram-gb 16 :roles ["comfy"] :disk-free "20-40G"}
           {:id "n5" :status "up" :ram-gb 16 :roles ["llama"] :disk-free "40-60G"}
           {:id "n6" :status "down"}]})

(deftest capacity-drops-down-nodes
  (let [cap (rb/capacity snapshot)]
    (is (= 5 (count cap)))
    (is (every? #(= 10 (:usable-gb %)) cap))          ; 16-6 → capped at ceiling 10
    (is (= #{:relay :llama} (:roles-capable (first cap))))))

(deftest demand-buckets-by-class
  (let [runs [{:units {:tokens 100}} {:units {:images 1}} {:units {:images 1}}
              {:model "browser-swarm" :units {:jobs 1}} {:units {:video-seconds 3}}]
        d (rb/demand-from-runs runs)]
    (is (= 1 (:text d))) (is (= 2 (:image d))) (is (= 1 (:video d))) (is (= 1 (:postproc d)))))

(deftest reserves-head-and-apportions-by-demand
  (let [cap (rb/capacity snapshot)
        ;; text-heavy demand → most worker nodes to the text pool
        plan (rb/target-allocation cap {:text 80 :image 10 :video 0 :audio 0 :postproc 10})]
    (is (= "n1" (:head plan)))                        ; relay-capable node is head
    (is (= 5 (:online plan)))                          ; 5 online total
    (is (= 4 (reduce + (vals (:pool-seats plan)))))    ; 4 workers seated (head reserved)
    (is (>= (get-in plan [:pool-seats :text-pool]) 2)) ; text dominates
    (is (= (get-in plan [:pipeline :effective-gb])
           (* 10 (count (get-in plan [:pools :text-pool])))))
    (testing "media demand present → media pool gets ≥1 (floor)"
      (is (pos? (get-in plan [:pool-seats :media-pool]))))))

(deftest zero-demand-for-a-pool-gets-zero-seats
  (let [cap (rb/capacity snapshot)
        plan (rb/target-allocation cap {:text 100 :image 0 :video 0 :audio 0 :postproc 0})]
    (is (= 0 (get-in plan [:pool-seats :media-pool])))
    (is (= 0 (get-in plan [:pool-seats :postproc-pool])))
    (is (= 4 (get-in plan [:pool-seats :text-pool])))))

(deftest rebalance-moves-nodes-on-demand-shift
  (let [cap (rb/capacity snapshot)
        p1 (rb/target-allocation cap {:text 100 :image 0 :video 0 :audio 0 :postproc 0})
        ;; demand flips to media-heavy
        r (rb/rebalance {:pools (:pools p1)} cap {:text 5 :image 90 :video 0 :audio 0 :postproc 5})]
    (is (:changed? r))
    (is (seq (:moves r)))
    (is (>= (get-in r [:target :pool-seats :media-pool]) 2))
    (testing "each move names from/to pools"
      (is (every? #(and (:id %) (:to %)) (:moves r))))))

(deftest rebalance-stable-when-demand-unchanged
  (let [cap (rb/capacity snapshot)
        d {:text 60 :image 30 :video 0 :audio 0 :postproc 10}
        p (rb/target-allocation cap d)
        r (rb/rebalance {:pools (:pools p) :demand d} cap d)]
    (is (not (:changed? r)))
    (is (empty? (:moves r)))))

(deftest empty-fleet-is-safe
  (let [r (rb/target-allocation [] {:text 10})]
    (is (nil? (:head r))) (is (zero? (:online r)))))
