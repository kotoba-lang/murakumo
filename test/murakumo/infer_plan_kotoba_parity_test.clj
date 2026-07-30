;; W6 pure-planner oracle: murakumo.infer.plan usable-bytes + choose-strategy
;; + plan-lr lanes / fits gates + integer partition-layers walk
;; vs kotoba/infer_plan_core.kotoba.

(ns murakumo.infer-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.plan :as plan]))

(def port-source (slurp "kotoba/infer_plan_core.kotoba"))
(def GiB plan/GiB)
(def plan-lr @(var murakumo.infer.plan/largest-remainder))

(def export-prefix
  (str "gib default-os-reserve default-headroom usable-bytes choose-strategy-name "
       "plan-lr-l0 plan-lr-l1 plan-lr-l2 plan-fits-total? span-fits? "
       "uniform-layer-bytes dense-units-milli moe-layer-bytes "
       "model-record model-layers model-dense model-frac-milli "
       "layer-byte-at layer-wsum partition-target "
       "advance-hi est-bytes-range partition-3-ends "
       "assignment-span plan-fits-3 ok-mark pick-max-idx-3 moe-capacity-ok "
       "digit-char nat-str i64-str bytes-to-gib-milli bytes-to-gib-floor layers-range-str "
       "mem-gib-milli usable-gib-milli est-gib-milli "
       "partition-1-end plan-fits-1 partition-2-ends plan-fits-2 "
       "asg-row-pack asg-row-span asg-row-fits pick-max-idx-2 ends-lo ends-hi "
       "lo-acc-pack partition-step partition-step-hi partition-step-acc "
       "partition-last fits-and"))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- wired-arg [node]
  (if-let [w (:wired-limit-bytes node)] w -1))

(defn- usable-call [node]
  (let [os (or (:os-reserve-bytes node) plan/default-os-reserve)
        head (or (:headroom-bytes node) plan/default-headroom)
        wired (wired-arg node)]
    (str "(usable-bytes " (:mem-bytes node) " " os " " head " " wired ")")))

(defn- unpack3 [packed]
  (let [b 65536]
    [(mod packed b) (mod (quot packed b) b) (quot packed (* b b))]))

(deftest constants-match-plan-cljc
  (let [actual (compile-i64-cases
                {"g" "(gib)"
                 "os" "(default-os-reserve)"
                 "hd" "(default-headroom)"})]
    (is (= GiB (get actual "g")))
    (is (= plan/default-os-reserve (get actual "os")))
    (is (= plan/default-headroom (get actual "hd")))))

(deftest usable-bytes-matches-plan-cljc
  (let [nodes [{:name "a" :mem-bytes (* 16 GiB) :os-reserve-bytes (* 5/2 GiB)}
               {:name "b" :mem-bytes (* 16 GiB) :os-reserve-bytes (* 5/2 GiB)
                :wired-limit-bytes (* 10 GiB)}
               {:name "c" :mem-bytes (* 2 GiB)}
               {:name "d" :mem-bytes (* 32 GiB) :os-reserve-bytes (* 12 GiB)}]
        cases (into {} (map-indexed (fn [i n] [(str "u_" i) (usable-call n)]) nodes))
        actual (compile-i64-cases cases)]
    (doseq [[i n] (map-indexed vector nodes)]
      (testing (:name n)
        (is (= (plan/usable-bytes n)
               (get actual (str "u_" i))))))))

(deftest choose-strategy-name-matches-plan-cljc
  (let [corpus [{:link-gbps 1 :ranks 12 :model {:model/experts 128 :model/kv-heads 4}}
                {:link-gbps 40 :ranks 4 :model {:model/experts 128 :model/kv-heads 8}}
                {:link-gbps 40 :ranks 5 :model {:model/experts 128 :model/kv-heads 8}}
                {:link-gbps nil :ranks 8 :model {:model/experts 64 :model/kv-heads 8}}]
        call (fn [{:keys [link-gbps ranks model]}]
               (let [link (long (or link-gbps 0))
                     exp (long (or (:model/experts model) 0))
                     kv (long (or (:model/kv-heads model) 0))
                     r (long (or ranks 0))]
                 (str "(choose-strategy-name " link " " r " " exp " " kv ")")))
        cases (into {} (map-indexed (fn [i m] [(str "s_" i) (call m)]) corpus))
        actual (compile-string-cases cases)]
    (doseq [[i m] (map-indexed vector corpus)]
      (testing (pr-str m)
        (is (= (name (:strategy (plan/choose-strategy m)))
               (get actual (str "s_" i))))))))

(deftest plan-lr-lanes-match-cljc-largest-remainder
  (let [cases [["a" 10 5 3 2]
               ["b" 10 1 1 1]
               ["c" 5 5 0 0]
               ["d" 4 3 3 2]
               ["e" 7 1 1 1]
               ["f" 0 1 1 1]
               ["g" 9 0 0 0]]
        ;; cljc quotas = total * w_i / sumw as doubles
        cljc (fn [total w0 w1 w2]
               (let [sumw (+ w0 w1 w2)]
                 (if (or (zero? total) (zero? sumw))
                   [0 0 0]
                   (let [qs (mapv #(* total (/ (double %) sumw)) [w0 w1 w2])]
                     (plan-lr total qs)))))
        ;; T5.3: three scalar lane projections, no pack. One label per lane.
        kotoba-cases (into {}
                           (mapcat (fn [[label total w0 w1 w2]]
                                     (let [args (str total " " w0 " " w1 " " w2 ")")]
                                       [[(str label "-0") (str "(plan-lr-l0 " args)]
                                        [(str label "-1") (str "(plan-lr-l1 " args)]
                                        [(str label "-2") (str "(plan-lr-l2 " args)]]))
                                   cases))
        actual (compile-i64-cases
                (merge kotoba-cases
                       {"g0" "(plan-lr-l0 10 5 3 2)"
                        "g1" "(plan-lr-l1 10 5 3 2)"
                        "g2" "(plan-lr-l2 10 5 3 2)"}))]
    (is (= 5 (get actual "g0")))
    (is (= 3 (get actual "g1")))
    (is (= 2 (get actual "g2")))
    (doseq [[label total w0 w1 w2] cases]
      (let [want (cljc total w0 w1 w2)
            got [(get actual (str label "-0"))
                 (get actual (str label "-1"))
                 (get actual (str label "-2"))]]
        (is (= want got) (str label " want=" want " got=" got))))))

(deftest plan-fits-and-layer-bytes
  (let [actual (compile-i64-cases
                {"ft1" "(plan-fits-total? 100 80)"
                 "ft0" "(plan-fits-total? 70 80)"
                 "sf1" "(span-fits? 50 50)"
                 "sf0" "(span-fits? 51 50)"
                 "ul" "(uniform-layer-bytes 1000 10)"
                 "du" "(dense-units-milli 78 3 100)"
                 "moe" "(moe-layer-bytes 1000000 78 3 100)"})]
    (is (= 1 (get actual "ft1")))
    (is (= 0 (get actual "ft0")))
    (is (= 1 (get actual "sf1")))
    (is (= 0 (get actual "sf0")))
    (is (= 100 (get actual "ul")))
    ;; units = 3*100 + 75*1000 = 75300 milli
    (is (= (+ (* 3 100) (* 75 1000)) (get actual "du")))
    (is (= (quot (* 1000000 1000) (+ (* 3 100) (* 75 1000))) (get actual "moe")))))

(defn- frac-milli [model]
  (long (* 1000 (double (or (:model/dense-layer-frac model) 1/10)))))

(defn- model-record-call [model]
  (str "(model-record " (:model/layers model) " "
       (or (:model/dense-layers model) 0) " " (frac-milli model) ")"))

(defn- cljc-ends [model nodes]
  (mapv (fn [a] (second (:layers a))) (plan/partition-layers model nodes)))

(deftest layer-byte-at-and-wsum
  (let [models [{:model/layers 10 :model/weight-bytes 1000 :model/dense-layers 0}
                {:model/layers 10 :model/weight-bytes 1000000 :model/dense-layers 3
                 :model/dense-layer-frac 1/10}
                {:model/layers 20 :model/weight-bytes 10000000 :model/dense-layers 3
                 :model/dense-layer-frac 1/10}]
        cases (into {}
                    (mapcat
                     (fn [i model]
                       (let [mp (model-record-call model)
                             w (:model/weight-bytes model)
                             L (:model/layers model)]
                         [[(str "w_" i) (str "(layer-wsum " w " " mp ")")]
                          [(str "b0_" i) (str "(layer-byte-at " w " " mp " 0)")]
                          [(str "bl_" i) (str "(layer-byte-at " w " " mp " " (dec L) ")")]]))
                     (range) models))
        actual (compile-i64-cases cases)]
    (doseq [[i model] (map-indexed vector models)]
      (let [lw (plan/layer-weights model)
            w (:model/weight-bytes model)
            L (:model/layers model)
            d (or (:model/dense-layers model) 0)
            f (frac-milli model)
            units (+ (* (min L d) f) (* (- L (min L d)) 1000))
            moe (if (< units 1) 0 (quot (* w 1000) units))
            db (quot (* moe f) 1000)
            int-wsum (+ (* (min L d) db) (* (- L (min L d)) moe))]
        (testing (str "model-" i)
          (is (= int-wsum (get actual (str "w_" i))))
          (is (= (long (first lw)) (get actual (str "b0_" i))))
          (is (= (long (last lw)) (get actual (str "bl_" i)))))))))

(deftest partition-3-ends-matches-cljc
  (let [cases
        [["eq" {:model/layers 12 :model/weight-bytes 1200}
          [{:name "a" :mem-bytes (* 16 GiB)}
           {:name "b" :mem-bytes (* 16 GiB)}
           {:name "c" :mem-bytes (* 16 GiB)}]]
         ["uneq" {:model/layers 12 :model/weight-bytes 1200}
          [{:name "a" :mem-bytes (* 32 GiB)}
           {:name "b" :mem-bytes (* 16 GiB)}
           {:name "c" :mem-bytes (* 8 GiB)}]]
         ["moe-eq" {:model/layers 20 :model/weight-bytes 10000000
                    :model/dense-layers 3 :model/dense-layer-frac 1/10}
          [{:name "a" :mem-bytes (* 16 GiB)}
           {:name "b" :mem-bytes (* 16 GiB)}
           {:name "c" :mem-bytes (* 16 GiB)}]]
         ["moe-uneq" {:model/layers 20 :model/weight-bytes 10000000
                      :model/dense-layers 3 :model/dense-layer-frac 1/10}
          [{:name "a" :mem-bytes (* 48 GiB)}
           {:name "b" :mem-bytes (* 16 GiB)}
           {:name "c" :mem-bytes (* 16 GiB)}]]
         ["zero" {:model/layers 10 :model/weight-bytes 1000}
          [{:name "a" :mem-bytes 0}
           {:name "b" :mem-bytes 0}
           {:name "c" :mem-bytes 0}]]]
        kotoba-cases
        (into {}
              (map (fn [[label model nodes]]
                     (let [us (mapv plan/usable-bytes nodes)
                           mp (model-record-call model)
                           w (:model/weight-bytes model)]
                       [label (str "(partition-3-ends " w " " mp " "
                                   (us 0) " " (us 1) " " (us 2) ")")]))
                   cases))
        actual (compile-i64-cases
                (merge kotoba-cases
                       {"t0" "(partition-target 1200 100 300)"
                        "t1" "(partition-target 1200 0 0)"
                        "ah" (str "(advance-hi 1200 (model-record 12 0 100) 0 0 "
                                  (quot (* 1200 1) 3) ")")
                        "er" "(est-bytes-range 1200 (model-record 12 0 100) 0 4)"}))]
    (is (= 400 (get actual "t0")))
    (is (= 0 (get actual "t1")))
    (is (= 4 (get actual "ah")))
    (is (= 400 (get actual "er")))
    (doseq [[label model nodes] cases]
      (let [want (cljc-ends model nodes)
            got (unpack3 (get actual label))]
        (testing label
          (is (= want got) (str label " want=" want " got=" got)))))))

(deftest plan-fits-3-matches-cljc-plan-gate
  (let [model {:model/layers 12 :model/weight-bytes 1200}
        nodes [{:name "a" :mem-bytes (* 16 GiB)}
               {:name "b" :mem-bytes (* 16 GiB)}
               {:name "c" :mem-bytes (* 16 GiB)}]
        us (mapv plan/usable-bytes nodes)
        mp "(model-record 12 0 100)"
        actual (compile-i64-cases
                {"fit" (str "(plan-fits-3 1200 " mp " " (us 0) " " (us 1) " " (us 2) ")")
                 "nofit" (str "(plan-fits-3 999999999999 " mp " " (us 0) " " (us 1) " " (us 2) ")")
                 "sp" "(assignment-span 2 7)"
                 "sp0" "(assignment-span 5 5)"})
        cljc-plan (plan/plan model nodes)]
    (is (= (if (:fits? cljc-plan) 1 0) (get actual "fit")))
    (is (= 0 (get actual "nofit")))
    (is (= 5 (get actual "sp")))
    (is (= 0 (get actual "sp0")))))

(deftest partition-n-neq-3-and-asg-row-maps
  "n=1 / n=2 partition + plan-fits + asg-row (host attaches node ids)."
  (let [model {:model/layers 12 :model/weight-bytes 1200}
        n1 [{:name "solo" :mem-bytes (* 32 GiB)}]
        n2eq [{:name "a" :mem-bytes (* 16 GiB)}
              {:name "b" :mem-bytes (* 16 GiB)}]
        n2uneq [{:name "a" :mem-bytes (* 32 GiB)}
                {:name "b" :mem-bytes (* 8 GiB)}]
        n2zero [{:name "a" :mem-bytes 0}
                {:name "b" :mem-bytes 0}]
        cljc-ends (fn [nodes]
                    (let [asg (plan/partition-layers model nodes)]
                      (mapv (fn [a] (second (:layers a))) asg)))
        us1 (mapv plan/usable-bytes n1)
        us2 (mapv plan/usable-bytes n2eq)
        us2u (mapv plan/usable-bytes n2uneq)
        us2z (mapv plan/usable-bytes n2zero)
        mp "(model-record 12 0 100)"
        actual (compile-i64-cases
                {"p1" (str "(partition-1-end " mp ")")
                 "f1" (str "(plan-fits-1 1200 " mp " " (us1 0) ")")
                 "f1n" (str "(plan-fits-1 999999999999 " mp " " (us1 0) ")")
                 "p2" (str "(partition-2-ends 1200 " mp " " (us2 0) " " (us2 1) ")")
                 "p2u" (str "(partition-2-ends 1200 " mp " " (us2u 0) " " (us2u 1) ")")
                 "p2z" (str "(partition-2-ends 1200 " mp " " (us2z 0) " " (us2z 1) ")")
                 "f2" (str "(plan-fits-2 1200 " mp " " (us2 0) " " (us2 1) ")")
                 "f2n" (str "(plan-fits-2 999999999999 " mp " " (us2 0) " " (us2 1) ")")
                 "f2u" (str "(plan-fits-2 1200 " mp " " (us2u 0) " " (us2u 1) ")")
                 "pm2a" "(pick-max-idx-2 10 5)"
                 "pm2b" "(pick-max-idx-2 5 10)"
                 "pm2t" "(pick-max-idx-2 10 10)"})
        p2 (get actual "p2")
        p2u (get actual "p2u")
        [hi0 hi1 _] (unpack3 p2)
        [hi0u hi1u _] (unpack3 p2u)
        ;; asg rows from ends for n2eq — host would zip with node names
        asg-actual
        (compile-i64-cases
         {"r0" (str "(asg-row-pack 1200 " mp " 0 " hi0 " " (us2 0) ")")
          "r1" (str "(asg-row-pack 1200 " mp " " hi0 " " hi1 " " (us2 1) ")")
          "elo0" (str "(ends-lo " p2 " 0)")
          "elo1" (str "(ends-lo " p2 " 1)")
          "ehi0" (str "(ends-hi " p2 " 0)")
          "ehi1" (str "(ends-hi " p2 " 1)")
          "sp0" (str "(asg-row-span (asg-row-pack 1200 " mp " 0 " hi0 " " (us2 0) "))")
          "ft0" (str "(asg-row-fits (asg-row-pack 1200 " mp " 0 " hi0 " " (us2 0) "))")})]
    (is (= 12 (get actual "p1")))
    (is (= (if (:fits? (plan/plan model n1)) 1 0) (get actual "f1")))
    (is (= 0 (get actual "f1n")))
    (is (= (cljc-ends n2eq) [hi0 hi1]))
    (is (= (cljc-ends n2uneq) [hi0u hi1u]))
    (is (= [0 12 0] (unpack3 (get actual "p2z"))))
    (is (= (if (:fits? (plan/plan model n2eq)) 1 0) (get actual "f2")))
    (is (= 0 (get actual "f2n")))
    (is (= (if (:fits? (plan/plan model n2uneq)) 1 0) (get actual "f2u")))
    (is (= 0 (get actual "pm2a")))
    (is (= 1 (get actual "pm2b")))
    (is (= 0 (get actual "pm2t")))
    (is (= 0 (get asg-actual "elo0")))
    (is (= hi0 (get asg-actual "elo1")))
    (is (= hi0 (get asg-actual "ehi0")))
    (is (= hi1 (get asg-actual "ehi1")))
    (let [cljc (plan/partition-layers model n2eq)
          a0 (first cljc)
          a1 (second cljc)
          r0 (get asg-actual "r0")
          r1 (get asg-actual "r1")]
      (is (= (:span a0) (get asg-actual "sp0")))
      (is (= (if (:fits? a0) 1 0) (get asg-actual "ft0")))
      (is (= (:span a0) (mod r0 65536)))
      (is (= (if (:fits? a0) 1 0) (mod (quot r0 65536) 65536)))
      (is (= (:span a1) (mod r1 65536)))
      (is (= (if (:fits? a1) 1 0) (mod (quot r1 65536) 65536))))))

(deftest partition-step-fold-matches-n4-cljc
  "Host-fold partition-step for n=4 ring equals cljc partition-layers ends."
  (let [model {:model/layers 20 :model/weight-bytes 2000}
        nodes [{:name "a" :mem-bytes (* 32 GiB)}
               {:name "b" :mem-bytes (* 16 GiB)}
               {:name "c" :mem-bytes (* 16 GiB)}
               {:name "d" :mem-bytes (* 8 GiB)}]
        us (mapv plan/usable-bytes nodes)
        total (reduce + us)
        mp "(model-record 20 0 100)"
        cljc-ends (mapv (fn [a] (second (:layers a))) (plan/partition-layers model nodes))
        ;; step0: lo=0 acc=0 cum=u0
        s0 (str "(partition-step 2000 " mp " (lo-acc-pack 0 0) " (us 0) " " total ")")
        actual0 (compile-i64-cases {"s0" s0 "last" (str "(partition-last " mp ")")
                                    "fa" "(fits-and 1 1)" "fb" "(fits-and 1 0)"})
        hi0 (mod (get actual0 "s0") 65536)
        acc0 (mod (quot (get actual0 "s0") 65536) 65536)
        s1 (str "(partition-step 2000 " mp " (lo-acc-pack " hi0 " " acc0 ") "
                (+ (us 0) (us 1)) " " total ")")
        actual1 (compile-i64-cases {"s1" s1})
        hi1 (mod (get actual1 "s1") 65536)
        acc1 (mod (quot (get actual1 "s1") 65536) 65536)
        s2 (str "(partition-step 2000 " mp " (lo-acc-pack " hi1 " " acc1 ") "
                (+ (us 0) (us 1) (us 2)) " " total ")")
        actual2 (compile-i64-cases {"s2" s2})
        hi2 (mod (get actual2 "s2") 65536)
        hi3 (get actual0 "last")
        pure-ends [hi0 hi1 hi2 hi3]
        ;; fits fold over asg rows
        rows (compile-i64-cases
              {"r0" (str "(asg-row-pack 2000 " mp " 0 " hi0 " " (us 0) ")")
               "r1" (str "(asg-row-pack 2000 " mp " " hi0 " " hi1 " " (us 1) ")")
               "r2" (str "(asg-row-pack 2000 " mp " " hi1 " " hi2 " " (us 2) ")")
               "r3" (str "(asg-row-pack 2000 " mp " " hi2 " " hi3 " " (us 3) ")")})
        f0 (mod (quot (get rows "r0") 65536) 65536)
        f1 (mod (quot (get rows "r1") 65536) 65536)
        f2 (mod (quot (get rows "r2") 65536) 65536)
        f3 (mod (quot (get rows "r3") 65536) 65536)
        fold (compile-i64-cases
              {"fall" (str "(fits-and (fits-and (fits-and " f0 " " f1 ") " f2 ") " f3 ")")
               "tot" (str "(plan-fits-total? " total " 2000)")})]
    (is (= 20 (get actual0 "last")))
    (is (= 1 (get actual0 "fa")))
    (is (= 0 (get actual0 "fb")))
    (is (= cljc-ends pure-ends) (str "cljc=" cljc-ends " pure=" pure-ends))
    (is (= (if (:fits? (plan/plan model nodes)) 1 0)
           (if (and (= 1 (get fold "tot")) (= 1 (get fold "fall"))) 1 0)))))

(deftest ok-mark-and-moe-pick
  (let [marks (compile-string-cases
               {"ok" "(ok-mark 1)"
                "bad" "(ok-mark 0)"})
        picks (compile-i64-cases
               {"p0" "(pick-max-idx-3 10 5 5)"
                "p1" "(pick-max-idx-3 5 10 5)"
                "p2" "(pick-max-idx-3 5 5 10)"
                "ptie" "(pick-max-idx-3 10 10 5)"
                "c0" "(moe-capacity-ok 0)"
                "c1" "(moe-capacity-ok 208)"})]
    (is (= "✓" (get marks "ok")))
    (is (= "✗" (get marks "bad")))
    (is (= 0 (get picks "p0")))
    (is (= 1 (get picks "p1")))
    (is (= 2 (get picks "p2")))
    (is (= 0 (get picks "ptie")))
    (is (= 0 (get picks "c0")))
    (is (= 1 (get picks "c1")))))

(deftest report-gib-helpers-match-cljc
  (let [nodes [{:name "a" :mem-bytes (* 16 GiB)}
               {:name "b" :mem-bytes (* 8 GiB)}
               {:name "c" :mem-bytes 1024}]
        cases (into {}
                    (mapcat
                     (fn [i n]
                       (let [os plan/default-os-reserve
                             hd plan/default-headroom
                             ub (plan/usable-bytes n)]
                         [[(str "m_" i) (str "(mem-gib-milli " (:mem-bytes n) ")")]
                          [(str "u_" i) (str "(usable-gib-milli " (:mem-bytes n) " " os " " hd " -1)")]
                          [(str "e_" i) (str "(est-gib-milli " ub ")")]]))
                     (range) nodes))
        actual (compile-i64-cases
                (merge cases
                       {"b0" "(bytes-to-gib-milli 0)"
                        "b16" (str "(bytes-to-gib-milli " (* 16 GiB) ")")
                        "f16" (str "(bytes-to-gib-floor " (* 16 GiB) ")")
                        "f1k" "(bytes-to-gib-floor 1024)"}))
        marks (compile-string-cases
               {"lr" "(layers-range-str 0 4)"
                "lr2" "(layers-range-str 4 12)"})]
    (is (= 0 (get actual "b0")))
    (is (= 16000 (get actual "b16")))
    (is (= 16 (get actual "f16")))
    (is (= 0 (get actual "f1k")))
    (is (= "0..4" (get marks "lr")))
    (is (= "4..12" (get marks "lr2")))
    (doseq [[i n] (map-indexed vector nodes)]
      (let [ub (plan/usable-bytes n)
            mem-m (long (* 1000.0 (/ (double (:mem-bytes n)) GiB)))
            use-m (long (* 1000.0 (/ (double ub) GiB)))
            est-m (long (* 1000.0 (/ (double ub) GiB)))]
        (testing (:name n)
          (is (= mem-m (get actual (str "m_" i))))
          (is (= use-m (get actual (str "u_" i))))
          (is (= est-m (get actual (str "e_" i)))))))))

