;; W6 pure-planner oracle: murakumo.infer.plan usable-bytes + choose-strategy
;; + plan-lr-3 / fits gates + integer partition-layers walk
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
       "lane-base plan-lr-3 plan-lr-pack-get plan-fits-total? span-fits? "
       "uniform-layer-bytes dense-units-milli moe-layer-bytes "
       "model-pack model-layers model-dense model-frac-milli "
       "layer-byte-at layer-wsum partition-target "
       "advance-hi est-bytes-range partition-3-ends "
       "assignment-span plan-fits-3 ok-mark pick-max-idx-3 moe-capacity-ok "
       "digit-char nat-str i64-str bytes-to-gib-milli bytes-to-gib-floor layers-range-str "
       "mem-gib-milli usable-gib-milli est-gib-milli"))

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

(deftest plan-lr-3-matches-cljc-largest-remainder
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
        kotoba-cases (into {}
                           (map (fn [[label total w0 w1 w2]]
                                  [label (str "(plan-lr-3 " total " " w0 " " w1 " " w2 ")")])
                                cases))
        actual (compile-i64-cases
                (merge kotoba-cases
                       {"g0" "(plan-lr-pack-get (plan-lr-3 10 5 3 2) 0)"
                        "g1" "(plan-lr-pack-get (plan-lr-3 10 5 3 2) 1)"
                        "g2" "(plan-lr-pack-get (plan-lr-3 10 5 3 2) 2)"}))]
    (is (= 5 (get actual "g0")))
    (is (= 3 (get actual "g1")))
    (is (= 2 (get actual "g2")))
    (doseq [[label total w0 w1 w2] cases]
      (let [want (cljc total w0 w1 w2)
            got (unpack3 (get actual label))]
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

(defn- model-pack-call [model]
  (str "(model-pack " (:model/layers model) " "
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
                       (let [mp (model-pack-call model)
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
                           mp (model-pack-call model)
                           w (:model/weight-bytes model)]
                       [label (str "(partition-3-ends " w " " mp " "
                                   (us 0) " " (us 1) " " (us 2) ")")]))
                   cases))
        actual (compile-i64-cases
                (merge kotoba-cases
                       {"t0" "(partition-target 1200 100 300)"
                        "t1" "(partition-target 1200 0 0)"
                        "ah" (str "(advance-hi 1200 (model-pack 12 0 100) 0 0 "
                                  (quot (* 1200 1) 3) ")")
                        "er" "(est-bytes-range 1200 (model-pack 12 0 100) 0 4)"}))]
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
        mp "(model-pack 12 0 100)"
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

