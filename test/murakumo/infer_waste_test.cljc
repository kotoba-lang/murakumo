;; Offline unit tests for the pure waste single-node planner (no fleet/SSH,
;; no container). The golden numbers are sqliteai/waste's own published
;; figures and its tools/memplan.py output — not values this planner produced
;; and then had blessed.
(ns murakumo.infer-waste-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.engine :as engine]
            [murakumo.infer.plan :as plan]
            [murakumo.infer.waste :as waste]))

(def GiB plan/GiB)
(def ^:private GB 1000000000)

(defn- node [name mem-gib disk-free-gb]
  {:name name :host name :mem-bytes (* mem-gib GiB)
   :disk-free-bytes (* disk-free-gb GB)})

;; moonshotai/Kimi-Linear-48B-A3B-Instruct config.json, 2026-08-03.
(def kimi-linear
  {:model/id "kimi-linear-48b" :model/engine :waste
   :model/layers 27 :model/hidden 2304 :model/vocab 163840
   :model/experts 256 :model/active-experts 8
   :model/moe-inter 1024 :model/dense-inter 9216
   :model/shared-experts 1 :model/first-dense 1
   :model/tie-embeddings? false
   :model/attn-heads 32 :model/kv-lora-rank 512 :model/q-lora-rank nil
   :model/qk-rope-dim 64 :model/qk-nope-dim 128 :model/v-head-dim 128
   :model/kda-layers 20 :model/mla-layers 7 :model/kda-heads 32
   :model/kda-head-dim 128 :model/conv-kernel 4
   :model/container-bytes (* 19 GB)})

;; moonshotai/Kimi-K3 config.json text_config, 2026-08-03.
(def kimi-k3
  {:model/id "kimi-k3" :model/engine :waste
   :model/layers 93 :model/hidden 7168 :model/vocab 163840
   :model/experts 896 :model/active-experts 16
   :model/moe-inter 3072 :model/dense-inter 33792
   :model/shared-experts 2 :model/first-dense 1
   :model/tie-embeddings? false
   :model/attn-heads 96 :model/kv-lora-rank 512 :model/q-lora-rank 1536
   :model/qk-rope-dim 64 :model/qk-nope-dim 128 :model/v-head-dim 128
   :model/kda-layers 69 :model/mla-layers 24 :model/kda-heads 96
   :model/kda-head-dim 128 :model/conv-kernel 4
   :model/container-bytes (* 982 GB)})

(defn- gib [b] (/ (double b) GiB))

(deftest memplan-matches-upstream-memplan-py
  (testing "Kimi-Linear @ctx 4096 — waste tools/memplan.py prints these to 2dp"
    (let [mp (waste/memplan kimi-linear {:ctx 4096})]
      (is (= 1.01 (-> mp :trunk-bytes gib (* 100) Math/round (/ 100.0))))
      (is (= 1.18 (-> mp :floor-bytes gib (* 100) Math/round (/ 100.0))))
      ;; memplan.py rounds these to 0 and 1 decimals respectively
      (is (= 12 (-> mp :routed-disk-bytes gib Math/round)))
      (is (= 0.4 (-> mp :working-set-bytes gib (* 10) Math/round (/ 10.0))))
      (is (= :estimated (:floor-source mp)))))
  (testing "Kimi-K3 @ctx 4096"
    (let [mp (waste/memplan kimi-k3 {:ctx 4096})]
      (is (= 16.38 (-> mp :trunk-bytes gib (* 100) Math/round (/ 100.0))))
      (is (= 17.51 (-> mp :floor-bytes gib (* 100) Math/round (/ 100.0))))
      (is (= 1344 (-> mp :routed-disk-bytes gib Math/round)))
      (is (= 24.0 (-> mp :working-set-bytes gib (* 10) Math/round (/ 10.0)))))))

(deftest measured-floor-overrides-the-analytic-estimate
  ;; The analytic path is out by 40% on K3 (17.51 GiB against a real 29.06 GB
  ;; floor). A planner that trusted it would green-light a node that cannot
  ;; open the model, so a measured figure has to win and has to be visible.
  (let [mp (waste/memplan (assoc kimi-k3 :model/ram-floor-bytes 29060000000)
                          {:ctx 4096})]
    (is (= 29060000000 (:floor-bytes mp)))
    (is (= :measured (:floor-source mp)))
    (is (= 17.51 (-> mp :estimated-floor-bytes gib (* 100) Math/round (/ 100.0))))))

(deftest budget-reproduces-the-readme-run
  ;; waste's README prints, on a 64 GB M5 Pro running K3:
  ;;   "no --budget, using 46.25 GB of 64.00 GB (expert cache 17.56 GB)"
  ;; floor 29.06 GB and a 17.19 GB working set are the figures that produce
  ;; it; if the stepping rule ever drifts, this is where it shows.
  (let [b (waste/budget {:floor-bytes 29060000000
                         :working-set-bytes 17190000000
                         :min-cache-bytes 370000000
                         :routed-disk-bytes (* 982 GB)}
                        (* 64 GB))
        gb #(/ (double %) GB)]
    (is (= 56.0 (-> (:os-cap-bytes b) gb)))
    (is (= 46.25 (-> (:budget-bytes b) gb (* 100) Math/round (/ 100.0))))
    (is (= 17.56 (-> (:cache-bytes b) gb (* 100) Math/round (/ 100.0))))
    (is (false? (:over-cap? b)))
    ;; floor + 3 working sets is 80.6 GB, well over the 56 GB cap — the point
    ;; of the rule is that it steps DOWN rather than clamping
    (is (= 80.63 (-> (:recommended-bytes b) gb (* 100) Math/round (/ 100.0))))))

(deftest budget-runs-at-the-floor-when-not-even-one-working-set-fits
  (let [b (waste/budget {:floor-bytes 29060000000
                         :working-set-bytes 17190000000
                         :min-cache-bytes 370000000
                         :routed-disk-bytes (* 982 GB)}
                        (* 34 GB))]
    (is (= 29060000000 (:budget-bytes b)))
    ;; cap is 29.75 GB, the floor is 29.06 — it fits, barely, with no cache
    (is (false? (:over-cap? b)))
    (is (= 370000000 (:cache-bytes b)))))

(deftest saturating-budget-is-what-the-engine-cannot-choose-itself
  ;; The engine stops at floor + 3 working sets. On a model far smaller than
  ;; the machine that leaves RAM idle while every token still reads experts
  ;; off disk. This is the number that takes disk out of the loop.
  (let [mp (waste/memplan kimi-linear {:ctx 4096})
        b (waste/budget mp (* 27 GiB))]
    (is (< (:recommended-bytes b) (:saturating-budget-bytes b))
        "the engine's own recommendation is well below saturation here")
    (let [sat (waste/throughput mp (:saturating-budget-bytes b) 940)]
      (is (= 1000 (:cache-frac-milli sat)))
      (is (= 1000 (:hit-rate-milli sat)))
      (is (zero? (:io-per-token-bytes sat)))
      (is (nil? (:disk-bound-tok-s sat))
          "no I/O means disk is out of the loop, not that throughput is zero")))
  (testing "0 when the expert set cannot fit under the cap"
    (let [mp (waste/memplan kimi-k3 {:ctx 4096})
          b (waste/budget mp (* 64 GB))]
      (is (zero? (:saturating-budget-bytes b))))))

(deftest hit-curve-knots-match-gate-5
  ;; waste tools/memplan.py HIT_CURVE, measured by the C engine's own cache
  (let [mp (waste/memplan kimi-linear {:ctx 4096})
        routed (:routed-disk-bytes mp)
        ;; round UP off the knot: the cache fraction is integer milli, so
        ;; truncating 3.0% of the expert set lands on 29 and reads the
        ;; segment below the knot
        at (fn [milli]
             (:hit-rate-milli
              (waste/throughput mp (long (Math/ceil (* routed (/ milli 1000.0)))))))]
    (is (= 0 (at 0)))
    (is (= 132 (at 30)))
    (is (= 403 (at 60)))
    (is (= 619 (at 121)))
    (is (= 848 (at 242)))
    (is (= 939 (at 484)))
    (is (= 1000 (at 1000)))))

(deftest disk-gate-decides-before-ram
  (testing "K3 does not fit a machine that has the RAM but not the container"
    (let [pl (waste/plan (assoc kimi-k3 :model/ram-floor-bytes 29060000000)
                         [(node "big-ram" 128 400)])]
      (is (false? (:fits? pl)))
      (is (= :no-container-space (-> pl :verdict :verdict)))))
  (testing "and does not fit a machine with the disk but not the RAM"
    (let [pl (waste/plan (assoc kimi-k3 :model/ram-floor-bytes 29060000000)
                         [(node "big-disk" 16 2000)])]
      (is (false? (:fits? pl)))
      (is (= :below-ram-floor (-> pl :verdict :verdict)))))
  (testing "a node with both is chosen over one with more RAM and no disk"
    (let [pl (waste/plan (assoc kimi-k3 :model/ram-floor-bytes 29060000000)
                         [(node "ram-rich" 512 40) (node "usable" 64 2000)])]
      (is (true? (:fits? pl)))
      (is (= "usable" (-> pl :assignments first :node :name))))))

(deftest kimi-linear-fits-a-32-gib-mac-with-external-storage
  ;; This machine: M4, 32 GiB, container on a 926 GB USB volume measured at
  ;; 940 MB/s. The RAM answer is easy; the interesting output is that the
  ;; engine's default budget leaves 24 GiB unused.
  (let [pl (waste/plan kimi-linear [(node "local" 32 906)]
                       {:ctx 4096 :disk-mb-s 940})
        b (:budget pl)]
    (is (true? (:fits? pl)))
    (is (= :fits (-> pl :verdict :verdict)))
    (is (< (:budget-bytes b) (* 3 GB)) "engine default is tiny for this model")
    (is (> (:saturating-budget-bytes b) (* 13 GB)))
    (is (< (:saturating-budget-bytes b) (:os-cap-bytes b)))))

;; Converted on this fleet 2026-08-03 with waste 0.6.3 tools/convert.py
;; (--jobs 3, ~54 min on an M4); RAM figures from `waste plan --json` at
;; ctx 4096, container byte-exact from the file listing.
(def kimi-linear-measured
  (assoc kimi-linear
         :model/container-bytes 19171317244
         :model/ram-floor-bytes 1376453888
         :model/expert-set-bytes 17748197376
         :model/working-set-bytes 575963136
         :model/min-cache-bytes 42663936
         :model/expert-milli-bits 3014))

(deftest analytic-is-low-across-the-board-not-within-rounding
  ;; Recorded because the first reading of this got it wrong: upstream's
  ;; "1.28 GB minimum RAM" is GiB, and comparing it to an analytic 1.27
  ;; DECIMAL GB made an 8% miss look like agreement. The analytic path is
  ;; low on every term here, and by 30%+ on the two disk ones.
  (let [a (waste/memplan kimi-linear {:ctx 4096})
        m (waste/memplan kimi-linear-measured {:ctx 4096})]
    (is (< (:floor-bytes a) (:floor-bytes m)))
    (is (< (:routed-disk-bytes a) (:routed-disk-bytes m)))
    (is (< (:working-set-bytes a) (:working-set-bytes m)))
    (is (= -8 (Math/round (* 100.0 (/ (- (:floor-bytes a) (:floor-bytes m))
                                      (:floor-bytes m))))))
    (is (= -30 (Math/round (* 100.0 (/ (- (:routed-disk-bytes a) (:routed-disk-bytes m))
                                       (:routed-disk-bytes m))))))
    (is (= -32 (Math/round (* 100.0 (/ (- (:working-set-bytes a) (:working-set-bytes m))
                                       (:working-set-bytes m))))))
    (is (= :measured (:floor-source m)))))

(deftest measured-expert-bits-feed-the-analytic-path
  ;; A family that has converted once knows its real expert precision, and
  ;; the estimate for the next member should use it rather than memplan.py's
  ;; 2.12 assumption. 3014 vs 2120 is a 42% difference in every disk term.
  (let [with-bits (waste/memplan (assoc kimi-linear :model/expert-milli-bits 3014)
                                 {:ctx 4096})
        without (waste/memplan kimi-linear {:ctx 4096})
        measured 17748197376]
    (is (> (:routed-disk-bytes with-bits) (:routed-disk-bytes without)))
    ;; not exact — milli-bits quantizes 3.013888… to 3.014 — but within a
    ;; rounding rather than the 30% the default assumption was out by
    (is (< (Math/abs (- (:routed-disk-bytes with-bits) measured))
           (quot measured 1000))
        "config + measured bits reproduces the measured expert set to <0.1%")
    (testing "an explicit opt still wins over the registry"
      (is (= (:routed-disk-bytes without)
             (:routed-disk-bytes (waste/memplan
                                  (assoc kimi-linear :model/expert-milli-bits 3014)
                                  {:ctx 4096 :expert-milli-bits 2120})))))))

(deftest measured-plan-matches-the-run-that-was-actually-made
  ;; `waste run --budget 19081987328` on this machine: 0.44 tok/s at 94%
  ;; hits, against 0.13 tok/s at 78% on the engine's own 2.89 GB default.
  (let [pl (waste/plan kimi-linear-measured [(node "local" 32 906)]
                       {:ctx 4096 :disk-mb-s 30})
        b (:budget pl)]
    (is (true? (:fits? pl)))
    (is (= 3104343296 (:recommended-bytes b))
        "matches `waste plan --json` recommended_bytes")
    (is (= 19081987328 (:saturating-budget-bytes b))
        "the budget the run was actually given")
    (is (< (:saturating-budget-bytes b) (:os-cap-bytes b))
        "and it is under the 7/8 cap, which is why it did not page")))

(deftest engine-commands
  (testing "waste-cmd passes the budget in bytes (parse_size takes bare bytes)"
    (is (= "bin/waste run /m/k.waste \"hi\" --budget 13720000000 --ctx 4096 -n 32"
           (engine/waste-cmd {:bin-dir "bin" :container "/m/k.waste"
                              :prompt "hi" :budget 13720000000
                              :ctx 4096 :max-tokens 32}))))
  (testing "no budget ⇒ no flag, and the engine picks"
    (is (= "bin/waste plan /m/k.waste --json"
           (engine/waste-cmd {:bin-dir "bin" :container "/m/k.waste"
                              :subcmd "plan" :json? true}))))
  (testing "serve binds 0.0.0.0 — a head the fleet cannot reach is not a head"
    (is (= "python3 -m serve /m/k.waste --port 8090 --host 0.0.0.0 --budget 13720000000"
           (engine/waste-serve-cmd {:container "/m/k.waste" :port 8090
                                    :budget 13720000000}))))
  (testing "commands dispatches :waste to the serve command"
    (is (= {:head {:cmd "python3 -m serve /m/k.waste --port 8000 --host 0.0.0.0"}}
           (engine/commands {} :waste {:container "/m/k.waste"})))))

(deftest config-shape-round-trip
  (testing "a multimodal config nests the language model under text_config"
    (let [shape (waste/config->shape
                 {"model_type" "kimi_k3"
                  "text_config" {"num_hidden_layers" 93 "hidden_size" 7168
                                 "vocab_size" 163840 "num_experts" 896
                                 "num_experts_per_token" 16
                                 "moe_intermediate_size" 3072
                                 "intermediate_size" 33792
                                 "num_shared_experts" 2
                                 "first_k_dense_replace" 1
                                 "num_attention_heads" 96
                                 "kv_lora_rank" 512 "q_lora_rank" 1536
                                 "qk_rope_head_dim" 64 "qk_nope_head_dim" 128
                                 "v_head_dim" 128
                                 "linear_attn_config"
                                 {"num_heads" 96 "head_dim" 128
                                  "short_conv_kernel_size" 4
                                  "kda_layers" (vec (range 69))}}})]
      (is (= 93 (:model/layers shape)))
      (is (= 896 (:model/experts shape)))
      (is (= 69 (:model/kda-layers shape)))
      (is (= 24 (:model/mla-layers shape)))
      (is (true? (:model/moe-shared-expert? shape))))))
