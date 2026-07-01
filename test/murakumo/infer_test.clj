;; Offline unit tests for the pure inference planner/engine (no fleet, no SSH).
(ns murakumo.infer-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.engine :as engine]
            [murakumo.infer.plan :as plan]))

(def GiB plan/GiB)

(defn- mini [name] {:name name :host name :mem-bytes (* 16 GiB)
                    :os-reserve-bytes (* 5/2 GiB)})
(def head {:name "head" :host "localhost" :head? true :mem-bytes (* 32 GiB)
           :os-reserve-bytes (* 12 GiB)})

(def glm {:model/id "glm-5.2-reap50-q2k" :model/format :gguf
          :model/layers 78 :model/weight-bytes 139000000000})

(deftest usable-memory
  (testing "16 GiB worker: 16 − 2.5 os − 1.25 headroom = 12.25 GiB"
    (is (= (* 49/4 GiB) (plan/usable-bytes (mini "a")))))
  (testing "an explicit wired limit caps the ceiling"
    (is (= (- (* 10 GiB) (* 5/4 GiB))
           (plan/usable-bytes (assoc (mini "a") :wired-limit-bytes (* 10 GiB))))))
  (testing "a node smaller than the reserves contributes nothing"
    (is (zero? (plan/usable-bytes {:mem-bytes (* 2 GiB)})))))

(deftest partition-is-contiguous-and-complete
  (let [nodes (conj (mapv mini ["a" "b" "c" "d"]) head)
        asg (plan/partition-layers glm nodes)]
    (testing "every layer assigned exactly once, in ring order"
      (is (= 78 (reduce + (map :span asg))))
      (is (= (map (comp second :layers) asg)
             (rest (concat (map (comp first :layers) asg) [78])))))
    (testing "spans are memory-proportional: the 32 GiB head gets the biggest slice"
      (is (apply = (map :span (butlast asg))))
      (is (> (:span (last asg)) (:span (first asg)))))))

(deftest fits-gate
  (testing "11 workers + head hold 139 GB of GLM-5.2 REAP50 Q2_K"
    (let [nodes (conj (mapv #(mini (str "m" %)) (range 11)) head)
          pl (plan/plan glm nodes)]
      (is (:fits? pl))
      (is (every? :fits? (:assignments pl)))))
  (testing "the same fleet does NOT hold the 214 GiB MLX 4-bit — the honest gate"
    (let [nodes (conj (mapv #(mini (str "m" %)) (range 11)) head)]
      (is (not (:fits? (plan/plan (assoc glm :model/weight-bytes 229780000000) nodes))))))
  (testing "zero-memory fleet plans to zero, not to a crash"
    (is (not (:fits? (plan/plan glm [{:mem-bytes 0}]))))))

(deftest deterministic
  (let [nodes (conj (mapv mini ["a" "b" "c"]) head)]
    (is (= (plan/plan glm nodes) (plan/plan glm nodes)))))

(deftest llamacpp-rpc-commands
  (let [nodes [(assoc (mini "a") :ip "100.0.0.1")
               (assoc (mini "b") :ip "100.0.0.2" :rpc-cache? false)
               head]
        pl (plan/plan glm nodes)
        {:keys [workers head]} (engine/commands pl :llamacpp-rpc
                                                {:bin-dir "bin" :model-path "m.gguf"})]
    (testing "one rpc-server per worker — the head is NOT a worker"
      (is (= ["a" "b"] (map :name workers)))
      (is (re-find #"-c$" (:cmd (first workers))))
      (is (not (re-find #"-c$" (:cmd (second workers))))))
    (testing "head drives the ring: endpoints in order, tensor-split = workers + head last"
      (is (re-find #"--rpc 100\.0\.0\.1:50052,100\.0\.0\.2:50052 " (:cmd head)))
      (is (re-find #"--tensor-split \d+,\d+,\d+ " (:cmd head))))))

(deftest mlx-ring-commands
  (let [pl (plan/plan glm [(assoc (mini "a") :ip "100.0.0.1") head])
        {:keys [hosts head]} (engine/commands pl :mlx-ring
                                              {:hosts-file "hosts.json" :venv "~/mlxlm"
                                               :model-repo "mlx-community/GLM-5.2-4bit"})]
    (is (= [{:ssh "a" :ips ["100.0.0.1"]} {:ssh "localhost" :ips ["localhost"]}] hosts))
    (is (re-find #"--backend ring" (:cmd head)))))
