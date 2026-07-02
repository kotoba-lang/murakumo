(ns murakumo.infer-postproc-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.postproc :as pp]))

(deftest luminance
  (is (= 0.0 (pp/luma [0 0 0])))
  (is (= 255.0 (pp/luma [255 255 255])))
  (testing "green dominates Rec.601"
    (is (> (pp/luma [0 255 0]) (pp/luma [255 0 0])))))

(deftest feature-reduction
  (let [pixels (concat (repeat 10 [0 0 0]) (repeat 6 [255 255 255]))
        f (pp/features pixels)]
    (testing "black+white split lands in the extreme bins, mean between"
      (is (= 16 (:n f)))
      (is (= 10 (first (:histogram f))))        ; 10 black → bin 0
      (is (= 6 (last (:histogram f))))          ; 6 white → bin 15
      (is (< 90 (:mean-luma f) 100)))           ; 6/16 * 255 ≈ 95.6
    (testing "signature is a stable 4-hex fingerprint"
      (is (re-matches #"[0-9a-f]{4}" (:signature f))))))

(deftest verify-guards-credit
  (let [pixels (repeat 64 [128 128 128])
        good (:histogram (pp/features pixels))]
    (testing "a correct histogram verifies; a tampered one does not"
      (is (pp/verify pixels good))
      (is (not (pp/verify pixels (assoc (vec good) 0 999)))))))

(deftest wgsl-kernel-present
  (testing "the shipped WGSL kernel names the buffers its contract needs"
    (let [k (pp/wgsl-reduce-kernel)]
      (is (re-find #"@compute" k))
      (is (re-find #"array<atomic<u32>,16>" k))
      (is (re-find #"0.299\*r \+ 0.587\*g \+ 0.114\*b" k)))))

(deftest deterministic
  (let [pixels [[10 20 30] [200 100 50] [0 0 0] [255 255 255]]]
    (is (= (pp/features pixels) (pp/features pixels)))))
