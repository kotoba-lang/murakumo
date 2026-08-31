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

;; ── demand: who should be HOLDING the device, not just what fits ───────
;;
;; Measured on gad over the same 24 hours, 2026-08-31:
;;
;;   chat completions on the serving head   2985   (0 in the last hour: down)
;;   POST /v1/generation                      97   (1 in the last hour)
;;   ComfyUI queued, all five instances         0
;;
;; The side holding more than the entire aperture served about 3% of the
;; requests and had nothing queued. These fixtures are those numbers.

(def chat-24h {:served 2985 :queued 2})
(def generation-24h {:served 97 :queued 0})
(def generation-idle {:served 0 :queued 0})

(deftest demand-is-measured-from-work-not-declared
  (is (> (accel/demand-score chat-24h) (accel/demand-score generation-24h))
      "31x the requests must score higher")
  (testing "queued work outweighs an equal count of past completions"
    ;; Something queued is demand that exists now; a completion is only
    ;; evidence that demand recurs.
    (is (> (accel/demand-score {:served 0 :queued 10})
           (accel/demand-score {:served 10 :queued 0})))))

(deftest an-unmeasured-side-is-never-scored-as-idle
  ;; The same discipline as the aperture sentinel, and it matters more here:
  ;; this comparison decides who KEEPS the accelerator, so reading a failed
  ;; probe as zero demand would evict a healthy holder.
  (doseq [broken [{:served accel/unmeasured :queued 0}
                  {:served 5 :queued accel/unmeasured}
                  (accel/parse-demand "")]]
    (is (nil? (accel/demand-score broken)))
    (let [d (accel/lease broken generation-24h 99 0)]
      (is (false? (:revoke? d)) "an unmeasured holder must not be evicted")
      (is (= :unmeasured-demand (:reason d))))))

(deftest idle-hysteresis-protects-a-gap-not-an-empty-window
  ;; The first version of this test asserted that ONE idle observation always
  ;; protects the holder. It does not, and should not: `generation-idle` served
  ;; nothing in the whole window and has nothing queued, while a challenger is
  ;; waiting. Hysteresis is for a holder between two requests, not for one that
  ;; did no work at all.
  (testing "a holder still working keeps the device through a momentary gap"
    (let [between-requests {:served 400 :queued 0}]
      (is (false? (:revoke? (accel/lease between-requests {:served 420 :queued 0} 1 0))))))
  (testing "a holder that did nothing in the window loses to a waiting challenger"
    (let [d (accel/lease generation-idle chat-24h 1 0)]
      (is (true? (:revoke? d)))))
  (testing "three consecutive idle observations are a fact even with no challenger"
    (let [d (accel/lease generation-idle {:served 0 :queued 0} 3 0)]
      (is (= :revoke-idle (:reason d)))))
  (testing "and an idle holder with nobody waiting and no streak simply keeps it"
    ;; Nothing is gained by unloading a model when no one wants the device.
    (let [d (accel/lease generation-idle {:served 0 :queued 0} 0 0)]
      (is (false? (:revoke? d)))
      (is (= :keep-no-challenger (:reason d))))))

(deftest the-minority-workload-is-not-starved-forever
  ;; This is what the first version of the rule got wrong, caught by a test
  ;; written from gad's real 31-to-1 ratio: under a pure bid the generation
  ;; side could never hold the accelerator again at any queue depth. A rule
  ;; that always names the same winner is a hardcoded owner with arithmetic in
  ;; front of it.
  (let [busy-generation {:served 97 :queued 4}]
    (testing "it does lose while the streak is short"
      (is (= :revoke-outbid (:reason (accel/lease busy-generation chat-24h 0 0))))
      ;; and with the roles swapped, the challenger loses early in its streak
      (is (false? (:revoke? (accel/lease chat-24h busy-generation 0 1))))
      (is (= :keep (:reason (accel/lease chat-24h busy-generation 0 1)))))
    (testing "after enough consecutive losses its QUEUED work takes a turn"
      (let [d (accel/lease chat-24h busy-generation 0 6)]
        (is (true? (:revoke? d)))
        (is (= :revoke-starved (:reason d)))))
    (testing "but only when it actually has work queued"
      ;; Aging must not accrue to a workload that is merely unpopular; only a
      ;; queue means someone is waiting right now.
      (let [d (accel/lease chat-24h {:served 97 :queued 0} 0 99)]
        (is (false? (:revoke? d)))
        (is (= :keep (:reason d)))))))

(deftest a-busy-holder-keeps-the-device-unless-clearly-outbid
  (testing "gad's real numbers: chat outbids generation by 31x"
    (let [d (accel/lease generation-24h chat-24h 0 0)]
      (is (true? (:revoke? d)))
      (is (= :revoke-outbid (:reason d)))))
  (testing "a challenger that merely ties does not take the device"
    ;; Incumbency is the tie-break: moving costs a reload — an 18k-token prompt
    ;; re-evaluated cold measured 83 s on this device — and staying costs
    ;; nothing.
    (is (false? (:revoke? (accel/lease chat-24h chat-24h 0 0)))))
  (testing "and a challenger just under the margin does not either"
    (is (false? (:revoke? (accel/lease {:served 100 :queued 0}
                                       {:served 140 :queued 0} 0 0))))
    (is (true? (:revoke? (accel/lease {:served 100 :queued 0}
                                      {:served 200 :queued 0} 0 0))))))

(deftest demand-parsing-tells-absent-from-zero
  (let [p (accel/parse-demand "served\t97\nqueued\t0\n")]
    (is (= 97 (:served p)))
    (is (= 0 (:queued p)) "zero queued is a real measurement"))
  (let [p (accel/parse-demand "served\t97\n")]
    (is (= accel/unmeasured (:queued p)) "an absent line is not zero"))
  (let [p (accel/parse-demand "served\tnot-a-number\nqueued\t3\n")]
    (is (= accel/unmeasured (:served p)))
    (is (nil? (accel/demand-score p)))))
