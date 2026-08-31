(ns murakumo.fleet.accelerator-test
  "Accelerator memory admission (ADR-2608318500).

  The three things this fixes, and therefore the three this pins:

  1. **A probe that failed is not an idle device.** The unmeasured answer must
     be a REFUSAL. If it were `admit`, a broken probe would open the whole
     accelerator — CLAUDE.md's first question, `入力が無いとき何を返すか`.
  2. **The test runs before the allocation.** So it must answer from measured
     bytes alone, with no side effect and nothing started.
  3. **Two classes, asymmetric.** A resident is not evicted by opportunistic
     work; opportunistic work must fit in what is actually left.

  Numbers are gad's, measured 2026-08-31: VRAM 48.0 GB + GTT 23.3 GB = a 71.3
  GB aperture, an inference head at 41.2 GB, and a Hunyuan3D instance that had
  81.77 GB — more than the whole device."
  (:require [clojure.test :refer [deftest testing is]]
            [murakumo.fleet.accelerator :as accel]))

(def ^:private gib (* 1024 1024 1024))
(defn- gb [x] (long (* x gib)))

;; gad, as measured
(def gad {:vram-total (gb 48.0) :gtt-total (gb 23.3) :claims [] :unreadable-claims 0})
(def head-bytes (gb 41.2))          ; the inference head's share while it was up
(def hunyuan-bytes (gb 81.77))      ; larger than the aperture, on its own

(deftest aperture-is-vram-plus-gtt
  (testing "Vulkan reported 73052 MiB for exactly this device; sysfs agrees"
    (is (= (+ (gb 48.0) (gb 23.3)) (accel/aperture-bytes gad)))
    (is (< 71.0 (/ (accel/aperture-bytes gad) (double gib)) 71.4))))

(deftest unmeasured-refuses-and-never-admits
  (testing "a probe that read nothing yields an unmeasured aperture"
    (doseq [broken [{:vram-total accel/unmeasured :gtt-total (gb 23.3) :claims []}
                    {:vram-total (gb 48.0) :gtt-total accel/unmeasured :claims []}
                    {:vram-total accel/unmeasured :gtt-total accel/unmeasured :claims []}]]
      (is (neg? (accel/aperture-bytes broken)))
      (let [d (accel/admit broken {} :resident (gb 1))]
        (is (false? (:admitted? d)) "unmeasured must refuse")
        (is (= :unmeasured (:reason d)))
        (is (zero? (:granted d)) "an unreadable device grants nothing"))))
  (testing "and an EMPTY device is a different, admitting answer"
    ;; The whole point: 0 bytes allocated is measured, -1 is not. If these two
    ;; ever return the same verdict, the check has stopped being one.
    (let [empty-dev {:vram-total 0 :gtt-total 0 :claims []}]
      (is (zero? (accel/aperture-bytes empty-dev)))
      (is (= :over-aperture (:reason (accel/admit empty-dev {} :resident (gb 1))))
          "an empty aperture refuses for size, NOT for unmeasured"))))

(deftest hunyuan-is-refused-for-the-reason-it-actually-fails
  (testing "81.77 GB against a 71.3 GB aperture cannot be served by any device state"
    (let [d (accel/admit gad {} :opportunistic hunyuan-bytes)]
      (is (false? (:admitted? d)))
      (is (= :over-aperture (:reason d))
          "over-aperture, not no-remaining: no eviction or retry makes it fit")))
  (testing "the same claim is still refused when the head is already resident"
    (let [busy (assoc gad :claims [{:pid "1" :bytes head-bytes :comm "llama-server"}])
          reg {"llama-server" :resident}
          d (accel/admit busy reg :opportunistic hunyuan-bytes)]
      (is (false? (:admitted? d))))))

(deftest resident-is-not-evicted-by-opportunistic-work
  (let [busy (assoc gad :claims [{:pid "1" :bytes head-bytes :comm "llama-server"}
                                 {:pid "2" :bytes (gb 8) :comm "ComfyUI-034"}])
        reg {"llama-server" :resident "ComfyUI-034" :opportunistic}]
    (testing "opportunistic work that would only fit by displacing the head is refused"
      (let [d (accel/admit busy reg :opportunistic (gb 25))]
        (is (false? (:admitted? d)))
        (is (contains? #{:would-evict-resident :no-remaining} (:reason d)))))
    (testing "opportunistic work that fits in what is genuinely left is admitted"
      (let [d (accel/admit busy reg :opportunistic (gb 4))]
        (is (true? (:admitted? d)) (str "refused as " (:reason d)))
        (is (= :admitted (:reason d)))))
    (testing "a resident claim ignores opportunistic holders, because they are evictable"
      ;; 41.2 resident + 8 opportunistic against ~62.7 usable: a 15 GB resident
      ;; request does not fit if opportunistic counted, and does if it does not.
      (is (true? (:admitted? (accel/admit busy reg :resident (gb 15))))))))

(deftest a-refusal-always-says-why-and-what-would-fit
  (let [busy (assoc gad :claims [{:pid "1" :bytes head-bytes :comm "llama-server"}])
        reg {"llama-server" :resident}
        d (accel/admit busy reg :opportunistic (gb 40))]
    (is (false? (:admitted? d)))
    (is (keyword? (:reason d)) "262 core dumps were unreadable because nothing said why")
    (is (pos? (:granted d)) "and a caller must be told what WOULD fit, so it can shrink")
    (is (true? (:admitted? (accel/admit busy reg :opportunistic (:granted d))))
        "granted must be honest: exactly that request has to be admitted")))

(deftest a-third-class-is-refused-rather-than-guessed
  (let [d (accel/admit gad {} :best-effort (gb 1))]
    (is (false? (:admitted? d)))
    (is (= :unknown-class (:reason d)))))

(deftest nonsense-requests-are-refused-not-silently-admitted
  (doseq [r [0 -1 (- (gb 5))]]
    (is (= :non-positive-request (:reason (accel/admit gad {} :resident r))))))

;; ── the probe half: measuring must be able to say it could not ─────────

(deftest probe-parsing-distinguishes-absent-from-zero
  (testing "both totals present"
    (let [p (accel/parse-probe "vram-total\t51539607552\ngtt-total\t25017075302\n")]
      (is (= 51539607552 (:vram-total p)))
      (is (= 25017075302 (:gtt-total p)))))
  (testing "a total the kernel did not expose stays unmeasured"
    (let [p (accel/parse-probe "vram-total\t51539607552\n")]
      (is (= accel/unmeasured (:gtt-total p)))
      (is (neg? (accel/aperture-bytes p)))))
  (testing "empty output is unmeasured, not an empty device"
    (doseq [out [nil "" "\n\n"]]
      (is (= accel/unmeasured (:vram-total (accel/parse-probe out))))))
  (testing "an unparseable claim is counted, never treated as zero bytes"
    ;; Under-counting commitments is how an admission test admits something
    ;; that does not fit.
    (let [p (accel/parse-probe (str "vram-total\t100\ngtt-total\t0\n"
                                    "claim\t7\t4096\tllama-server\n"
                                    "claim\t8\tnot-a-number\tComfyUI\n"))]
      (is (= 1 (count (:claims p))))
      (is (= 1 (:unreadable-claims p))))))

(deftest unknown-holders-count-against-opportunistic-not-against-nothing
  (let [busy (assoc gad :claims [{:pid "99" :bytes (gb 30) :comm "some-unregistered-thing"}])
        c (accel/committed busy {})]
    (is (= 0 (:resident c)))
    (is (= (gb 30) (:opportunistic c))
        "an unknown holder must reduce what opportunistic work may take")))
