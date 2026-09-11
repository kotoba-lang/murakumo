;; Offline unit tests for the pure mlx-moe single-node planner (no fleet/SSH).
(ns murakumo.infer-moe-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.engine :as engine]
            [murakumo.infer.moe :as moe]
            [murakumo.infer.plan :as plan]))

(def GiB plan/GiB)

;; :os-reserve-bytes 2.5 GiB (explicit, matches infer_test.clj's `mini`) + the
;; default 1.25 GiB headroom = 3.75 GiB off the top, so a node needs mem-gib =
;; tier + 3.75ish to actually CLEAR that tier's usable-bytes floor.
(defn- node [name mem-gib] {:name name :host name :mem-bytes (* mem-gib GiB)
                            :os-reserve-bytes (* 5/2 GiB)})

;; mu-hashmi/mlx-moe README table for mlx-community/Qwen3-Coder-Next-4bit.
(def qwen-coder-next
  {:model/id "qwen3-coder-next-mlx-moe" :model/format :mlx
   :model/layers 48 :model/weight-bytes 46000000000
   :model/experts 512 :model/active-experts 10 :model/moe-shared-expert? true})

(def glm-mxfp4-profiled
  {:model/id "glm-5.2-mxfp4-mlx-moe" :model/format :mlx
   :model/layers 78 :model/weight-bytes 395110000000
   :model/experts 256 :model/active-experts 8 :model/moe-shared-expert? true
   :model/mlx-moe-capacity-tiers [[24 8] [20 4]]})

(deftest capacity-tiers
  (testing "the README's measured hardware table, exactly"
    (is (= 208 (moe/capacity-for-usable (* 32 GiB))))
    (is (= 320 (moe/capacity-for-usable (* 48 GiB))))
    (is (= 432 (moe/capacity-for-usable (* 64 GiB))))
    (is (= 512 (moe/capacity-for-usable (* 128 GiB)))))
  (testing "below the smallest tier, mlx-moe hasn't been measured — no guess"
    (is (nil? (moe/capacity-for-usable (* 31 GiB)))))
  (testing "above the top tier still caps at the largest measured capacity"
    (is (= 512 (moe/capacity-for-usable (* 256 GiB))))))

(deftest model-specific-capacity-tiers
  (testing "GLM-5.2 mxfp4 uses the measured profiled-cache tiers, not Qwen's README tiers"
    (is (nil? (moe/capacity-for-usable glm-mxfp4-profiled (* 19 GiB))))
    (is (= 4 (moe/capacity-for-usable glm-mxfp4-profiled (* 20 GiB))))
    (is (= 8 (moe/capacity-for-usable glm-mxfp4-profiled (* 24 GiB))))))

(deftest expert-ratio-and-verdict
  (testing "512 experts / top-10 = 51x — the README's headline ratio"
    (is (= 51.2 (moe/expert-ratio qwen-coder-next))))
  (testing "high ratio + shared expert → recommended"
    (is (= :recommended (:verdict (moe/verdict qwen-coder-next)))))
  (testing "no shared expert → not-recommended even at a decent ratio"
    (is (= :not-recommended
           (:verdict (moe/verdict (assoc qwen-coder-next :model/moe-shared-expert? false))))))
  (testing "shared expert but low ratio → workable, not recommended"
    (is (= :workable
           (:verdict (moe/verdict (assoc qwen-coder-next :model/active-experts 100))))))
  (testing "missing registry fields → honest unknown, not a guess"
    (is (= :unknown (:verdict (moe/verdict {:model/id "mystery"}))))))

(deftest single-node-picks-the-biggest-node
  (testing "best-memory node wins, others are simply not chosen (no fleet split)"
    (let [nodes [(node "small" 16) (node "big" 68) (node "medium" 52)]
          pl (moe/plan qwen-coder-next nodes)]
      (is (= "big" (:name (:node (first (:assignments pl))))))
      (is (:fits? pl))
      (is (= 432 (:capacity pl)))    ; 68 GiB mem − 3.75 overhead ≈ 64.25 GiB usable → 64 GiB tier
      (is (:head? (:node (first (:assignments pl))))))))

(deftest fits-gate-is-honest
  (testing "every node under the 32 GiB floor → does not fit, not a crash"
    (let [pl (moe/plan qwen-coder-next [(node "tiny" 8) (node "small" 16)])]
      (is (not (:fits? pl)))
      (is (nil? (:capacity pl)))))
  (testing "profiled GLM-5.2 mxfp4 still rejects 16 GiB M4 minis"
    (let [pl (moe/plan glm-mxfp4-profiled [(node "m4-mini" 16)])]
      (is (not (:fits? pl)))
      (is (nil? (:capacity pl)))))
  (testing "empty fleet → does not fit, not a crash"
    (let [pl (moe/plan qwen-coder-next [])]
      (is (not (:fits? pl)))
      (is (empty? (:assignments pl))))))

(deftest resident-estimate-scales-with-capacity
  (testing "a 208-capacity plan holds far less than the full 46 GB checkpoint
            (46e9 * 208/512 ≈ 18.7e9 bytes ≈ 17.4 GiB — near the README's
            measured 19.1 GB for this exact tier)"
    (let [pl (moe/plan qwen-coder-next [(node "min" 36)])   ; 36 − 3.75 ≈ 32.25 GiB usable → 32 GiB tier
          est (:est-bytes (first (:assignments pl)))]
      (is (= 208 (:capacity pl)))
      (is (< est (:model/weight-bytes qwen-coder-next)))
      (is (< (* 15 GiB) est (* 20 GiB))))))

(deftest plan-shape-is-engine-compatible
  (testing "reuses plan/plan's shape — engine/commands + report don't need special-casing"
    (let [pl (moe/plan qwen-coder-next [(node "a" 68)])]
      (is (= :mlx-moe (:engine pl)))
      (is (= [] (engine/workers pl)))                    ; the sole node is :head?, not a worker
      (is (= 48 (engine/head-span pl)))                   ; full-layer span
      (let [{:keys [head]} (engine/commands pl :mlx-moe
                                            {:model-repo "mlx-community/Qwen3-Coder-Next-4bit"
                                             :capacity (:capacity pl) :kv-bits 8})]
        (is (re-find #"^mlx-moe serve mlx-community/Qwen3-Coder-Next-4bit " (:cmd head)))
        (is (re-find #"--capacity 432" (:cmd head)))
        (is (re-find #"--kv-bits 8" (:cmd head)))))))

(deftest mlx-moe-cmd-omits-unset-flags
  (is (= "mlx-moe serve mlx-community/Qwen3-Coder-Next-4bit --host 0.0.0.0 --port 8080"
         (engine/mlx-moe-cmd {:model-repo "mlx-community/Qwen3-Coder-Next-4bit"})))
  (is (re-find #"^~/mlxlm/bin/mlx-moe serve "
               (engine/mlx-moe-cmd {:venv "~/mlxlm" :model-repo "m"}))))

(deftest mlx-moe-cmd-emits-profile-cache-knobs
  (let [cmd (engine/mlx-moe-cmd {:model-repo "mlx-community/GLM-5.2-mxfp4"
                                 :capacity 8
                                 :pin-top-k 8
                                 :kv-bits 8
                                 :profile "$HOME/.cache/mlx-moe/glm.json"
                                 :warmup "none"
                                 :extra-args ["--debug"]})]
    (is (re-find #"--capacity 8" cmd))
    (is (re-find #"--pin-top-k 8" cmd))
    (is (re-find #"--kv-bits 8" cmd))
    (is (re-find #"--profile \$HOME/\.cache/mlx-moe/glm\.json" cmd))
    (is (re-find #"--warmup none" cmd))
    (is (re-find #"--debug$" cmd))))
