(ns murakumo.infer-gc-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.gc :as gc]))

(def G gc/GiB)

(def entries
  [{:path "rpc/aaa" :class :rpc-cache :bytes (* 10 G) :atime-days 3}
   {:path "rpc/bbb" :class :rpc-cache :bytes (* 5 G) :atime-days 1}
   {:path "ollama/x" :class :protected :bytes (* 21 G) :atime-days 0}
   {:path "ckpt/animagine" :class :protected :bytes (* 7 G) :atime-days 0}
   {:path "hf/old" :class :hf-stale :bytes (* 8 G) :atime-days 30}
   {:path "hf/mid" :class :hf-stale :bytes (* 6 G) :atime-days 10}
   {:path "hf/recent" :class :hf-stale :bytes (* 5 G) :atime-days 1}
   {:path "comfy/tmp-old" :class :comfy-temp :bytes (* 2 G) :atime-days 20}
   {:path "comfy/tmp-new" :class :comfy-temp :bytes (* 1 G) :atime-days 2}])

(deftest never-evicts-protected
  (let [{:keys [evict]} (gc/plan entries (* 0.1 G) {:target-free-bytes (* 100 G)})]
    (is (not-any? #(= :protected (:class %)) evict))
    (testing "even under maximum pressure, ollama + active checkpoint survive"
      (is (not-any? #(#{"ollama/x" "ckpt/animagine"} (:path %)) evict)))))

(deftest rpc-cache-goes-first
  (testing "dead RPC caches are reclaimed before touching HF or comfy"
    (let [{:keys [evict reclaim-bytes]} (gc/plan entries (* 5 G) {:target-free-bytes (* 18 G)})]
      ;; need 13 GiB; both rpc caches = 15 GiB, so only rpc evicted
      (is (every? #(= :rpc-cache (:class %)) evict))
      (is (>= reclaim-bytes (* 13 G))))))

(deftest hf-lru-keeps-recent
  (testing "HF eviction is LRU: the 2 most-recent stay, older go"
    (let [{:keys [evict]} (gc/plan entries (* 1 G)
                                   {:target-free-bytes (* 40 G) :hf-keep 2})
          hf-evicted (set (map :path (filter #(= :hf-stale (:class %)) evict)))]
      (is (contains? hf-evicted "hf/old"))
      (is (not (contains? hf-evicted "hf/recent")))
      (is (not (contains? hf-evicted "hf/mid"))))))

(deftest comfy-keeps-fresh
  (testing "ComfyUI temp newer than keep-days is never a candidate"
    (let [{:keys [evict]} (gc/plan entries (* 0.1 G)
                                   {:target-free-bytes (* 200 G) :comfy-keep-days 7})
          comfy (set (map :path (filter #(= :comfy-temp (:class %)) evict)))]
      (is (contains? comfy "comfy/tmp-old"))
      (is (not (contains? comfy "comfy/tmp-new"))))))

(deftest stops-at-target
  (testing "reclaim stops once the target is met — no over-deletion"
    (let [{:keys [evict target-met? free-after]} (gc/plan entries (* 5 G) {:target-free-bytes (* 12 G)})]
      (is target-met?)
      (is (>= free-after (* 12 G)))
      ;; only needed 7 GiB; the 10 GiB rpc entry alone suffices
      (is (= 1 (count evict))))))

(deftest already-satisfied
  (testing "a node already above target evicts nothing"
    (let [{:keys [evict target-met?]} (gc/plan entries (* 50 G) {:target-free-bytes (* 20 G)})]
      (is (empty? evict))
      (is target-met?))))
