;; W6 parity gate: kotoba/infer_waste_core.kotoba compiled fresh must agree
;; with murakumo.infer.waste's host path (which executes the SHIPPED KIR). A
;; drift between the source and the artifact, or between either and the host
;; wiring, fails here rather than in a plan that quietly recommends a node
;; that cannot run the model.
(ns murakumo.infer-waste-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.waste :as waste]))

(def port-source (slurp "kotoba/infer_waste_core.kotoba"))

(def export-prefix
  (str "gib expert-params expert-rec-bytes emb-head-bytes "
       "mla-q-params mla-kv-params mla-o-params attn-bytes "
       "router-norms-bytes shared-bytes dense-ffn-bytes trunk-bytes "
       "kda-state-bytes mla-kv-bytes state-bytes "
       "scratch-bytes min-cache-bytes floor-bytes "
       "routed-disk-bytes working-set-bytes "
       "os-cap-bytes recommended-bytes resolve-budget cache-bytes "
       "saturating-budget "
       "hit-rate-milli io-per-token-bytes tok-per-s-milli "
       "fits? budget-over-cap? verdict-name"))

(def ^:private verdict-ty
  "[:record :waste/verdict [[:ram-ok :bool] [:disk-ok :bool] [:cap-ok :bool]]]")

(defn- compile-cases
  "Append zero-arg wrappers of `ret` type and execute each on a fresh compile."
  [ret cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] " ret " " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " "
                                        (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(def ^:private compile-i64-cases (partial compile-cases ":i64"))
(def ^:private compile-bool-cases (partial compile-cases ":bool"))
(def ^:private compile-string-cases (partial compile-cases ":string"))

;; The Kimi-Linear shape, as murakumo.infer.waste/config->shape emits it.
(def kimi-linear
  {:model/layers 27 :model/hidden 2304 :model/vocab 163840
   :model/experts 256 :model/active-experts 8
   :model/moe-inter 1024 :model/dense-inter 9216
   :model/shared-experts 1 :model/first-dense 1
   :model/tie-embeddings? false
   :model/attn-heads 32 :model/kv-lora-rank 512 :model/q-lora-rank nil
   :model/qk-rope-dim 64 :model/qk-nope-dim 128 :model/v-head-dim 128
   :model/kda-layers 20 :model/mla-layers 7 :model/kda-heads 32
   :model/kda-head-dim 128 :model/conv-kernel 4})

(deftest memplan-components-match
  (let [mp (waste/memplan kimi-linear {:ctx 4096})
        actual (compile-i64-cases
                {"expert_rec" "(expert-rec-bytes 2304 1024 2120)"
                 "emb_head" "(emb-head-bytes 163840 2304 4250 false)"
                 "router_norms" "(router-norms-bytes 26 256 27 2304)"
                 "shared" "(shared-bytes 26 1 (expert-params 2304 1024) 4250)"
                 "dense_ffn" "(dense-ffn-bytes 1 2304 9216 4250)"
                 "kda" "(kda-state-bytes 20 32 128 4)"
                 "mla_kv" "(mla-kv-bytes 7 4096 512 64)"
                 "scratch" "(scratch-bytes 2304 1024 163840 10)"
                 "min_cache" "(min-cache-bytes 8 (expert-rec-bytes 2304 1024 2120))"
                 "routed" "(routed-disk-bytes 26 256 (expert-params 2304 1024) 2120)"
                 "ws" "(working-set-bytes 26 8 (expert-params 2304 1024) 2120)"})]
    (is (= (:expert-rec-bytes mp) (get actual "expert_rec")))
    (is (= (:min-cache-bytes mp) (get actual "min_cache")))
    (is (= (:scratch-bytes mp) (get actual "scratch")))
    (is (= (:routed-disk-bytes mp) (get actual "routed")))
    (is (= (:working-set-bytes mp) (get actual "ws")))
    (is (= (:state-bytes mp) (+ (get actual "kda") (get actual "mla_kv"))))
    (testing "trunk is the sum of its five parts, computed in the guest"
      (let [attn (get (compile-i64-cases
                       {"attn" (str "(attn-bytes 27 (+ (mla-q-params 2304 2304 32 192)"
                                    " (+ (mla-kv-params 2304 512 64 32 256)"
                                    " (mla-o-params 32 128 2304))) 4250)")})
                      "attn")]
        (is (= (:trunk-bytes mp)
               (+ (get actual "emb_head") attn (get actual "router_norms")
                  (get actual "shared") (get actual "dense_ffn"))))))))

(deftest budget-stepping-matches
  ;; floor 29.06 GB, working set 17.19 GB — waste's README K3 run on 64 GB
  (let [floor 29060000000
        ws 17190000000
        host (waste/budget {:floor-bytes floor :working-set-bytes ws
                            :min-cache-bytes 370000000
                            :routed-disk-bytes 982000000000}
                           64000000000)
        actual (compile-i64-cases
                {"cap" "(os-cap-bytes 64000000000)"
                 "rec" "(recommended-bytes 29060000000 17190000000)"
                 "b64" "(resolve-budget 29060000000 17190000000 (os-cap-bytes 64000000000))"
                 "b128" "(resolve-budget 29060000000 17190000000 (os-cap-bytes 128000000000))"
                 "b34" "(resolve-budget 29060000000 17190000000 (os-cap-bytes 34000000000))"
                 "cache" "(cache-bytes 46250000000 29060000000 370000000)"
                 "nocap" "(resolve-budget 29060000000 17190000000 0)"})]
    (is (= (:os-cap-bytes host) (get actual "cap")))
    (is (= (:recommended-bytes host) (get actual "rec")))
    (is (= (:budget-bytes host) (get actual "b64")))
    (is (= 46250000000 (get actual "b64")) "the README's printed 46.25 GB")
    (is (= 17560000000 (get actual "cache")) "the README's printed 17.56 GB")
    (testing "128 GB still gets the full floor + 3x"
      (is (= (get actual "rec") (get actual "b128"))))
    (testing "34 GB gets the floor and no cache"
      (is (= 29060000000 (get actual "b34"))))
    (testing "cap 0 means no cap known, and the recommendation stands"
      (is (= (get actual "rec") (get actual "nocap"))))))

(deftest hit-curve-matches
  (let [actual (compile-i64-cases
                (into {} (map (fn [f] [(str "h_" f) (str "(hit-rate-milli " f ")")])
                              [0 30 60 121 242 484 968 1000 1200])))]
    ;; tools/memplan.py HIT_CURVE knots, x1000
    (is (= 0 (get actual "h_0")))
    (is (= 132 (get actual "h_30")))
    (is (= 403 (get actual "h_60")))
    (is (= 619 (get actual "h_121")))
    (is (= 848 (get actual "h_242")))
    (is (= 939 (get actual "h_484")))
    (is (= 942 (get actual "h_968")))
    (is (= 1000 (get actual "h_1000")))
    (testing "a fraction over 1.0 clamps rather than extrapolating"
      (is (= 1000 (get actual "h_1200"))))))

(deftest saturating-and-predicates-match
  (let [i64 (compile-i64-cases
             {"sat_fits" "(saturating-budget 1270000000 12480000000 30000000 25600000000)"
              "sat_nofit" "(saturating-budget 29060000000 982000000000 370000000 56000000000)"
              "io" "(io-per-token-bytes 390000000 530)"
              "tps" "(tok-per-s-milli 183300000 940)"
              "tps0" "(tok-per-s-milli 0 940)"})
        bools (compile-bool-cases
               {"fit_yes" "(fits? 1270000000 29000000000)"
                "fit_no" "(fits? 29060000000 25600000000)"
                "over_yes" "(budget-over-cap? 30000000000 25600000000)"
                "over_no" "(budget-over-cap? 2440000000 25600000000)"
                "over_nocap" "(budget-over-cap? 30000000000 0)"})]
    (is (= 13720000000 (get i64 "sat_fits")))
    (is (zero? (get i64 "sat_nofit"))
        "982 GB of experts does not fit under a 56 GB cap")
    (is (= 183300000 (get i64 "io")))
    (is (zero? (get i64 "tps0")) "no I/O ⇒ 0, host reports it as unbounded")
    (is (true? (get bools "fit_yes")))
    (is (false? (get bools "fit_no")))
    (is (true? (get bools "over_yes")))
    (is (false? (get bools "over_no")))
    (is (false? (get bools "over_nocap")) "cap 0 means unknown, not zero")
    (testing "host agrees with the freshly compiled guest"
      (is (= (get i64 "tps") (long (* 1000 (:disk-bound-tok-s
                                            (waste/throughput
                                             {:routed-disk-bytes 12480000000
                                              :working-set-bytes 390000000}
                                             (long (* 0.096 12480000000))
                                             940)))))))))

(deftest verdict-names-match
  (let [actual (compile-string-cases
                {"v_fits" (str "(verdict-name (record-new " verdict-ty " true true true))")
                 "v_ram" (str "(verdict-name (record-new " verdict-ty " false true true))")
                 "v_disk" (str "(verdict-name (record-new " verdict-ty " true false true))")
                 "v_cap" (str "(verdict-name (record-new " verdict-ty " true true false))")
                 ;; ram loses to nothing: a node that cannot open the model is
                 ;; not "storage-bound"
                 "v_none" (str "(verdict-name (record-new " verdict-ty " false false false))")})]
    (is (= "fits" (get actual "v_fits")))
    (is (= "below-ram-floor" (get actual "v_ram")))
    (is (= "no-container-space" (get actual "v_disk")))
    (is (= "budget-over-os-cap" (get actual "v_cap")))
    (is (= "below-ram-floor" (get actual "v_none")))
    (doseq [[k v] {"v_fits" {:ram-ok? true :disk-ok? true :cap-ok? true}
                   "v_ram" {:ram-ok? false :disk-ok? true :cap-ok? true}
                   "v_disk" {:ram-ok? true :disk-ok? false :cap-ok? true}
                   "v_cap" {:ram-ok? true :disk-ok? true :cap-ok? false}}]
      (is (= (get actual k) (name (:verdict (waste/verdict v))))))))
