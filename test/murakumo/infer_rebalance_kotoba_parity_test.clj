(ns murakumo.infer-rebalance-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.rebalance :as rb]))

(def port-source (slurp "kotoba/infer_rebalance_core.kotoba"))
(def export-prefix
  "shard-ceiling-gb os-kv-headroom-gb usable-gb pool-for-class lane-base largest-remainder-3 seats-text seats-media seats-postproc pool-demand-pack seats-from-pool-pack classify-run-flags")

(def largest-remainder
  "Private cljc largest-remainder (var-quote for oracle parity)."
  @(var murakumo.infer.rebalance/largest-remainder))

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- unpack [packed]
  (let [b 65536]
    {:text-pool (mod packed b)
     :media-pool (mod (quot packed b) b)
     :postproc-pool (quot packed (* b b))}))

(deftest usable-gb-matches-node-capacity
  (let [actual (compile-i64-cases
                {"c" "(shard-ceiling-gb)"
                 "h" "(os-kv-headroom-gb)"
                 "u16" "(usable-gb 16)"
                 "u8" "(usable-gb 8)"
                 "u4" "(usable-gb 4)"
                 "u32" "(usable-gb 32)"})]
    (is (= rb/shard-ceiling-gb (get actual "c")))
    (is (= 6 (get actual "h")))
    (is (= (:usable-gb (rb/node-capacity {:id "x" :ram-gb 16})) (get actual "u16")))
    (is (= (:usable-gb (rb/node-capacity {:id "x" :ram-gb 8})) (get actual "u8")))
    (is (= (:usable-gb (rb/node-capacity {:id "x" :ram-gb 4})) (get actual "u4")))
    (is (= (:usable-gb (rb/node-capacity {:id "x" :ram-gb 32})) (get actual "u32")))))

(deftest pool-for-class-matches-class-map
  (let [classes ["text" "image" "video" "audio" "postproc" "other"]
        cases (into {} (map-indexed
                        (fn [i c]
                          [(str "p_" i) (str "(pool-for-class " (kotoba-literal c) ")")])
                        classes))
        actual (compile-string-cases cases)
        expected {"text" "text-pool" "image" "media-pool" "video" "media-pool"
                  "audio" "media-pool" "postproc" "postproc-pool" "other" "text-pool"}]
    (doseq [[i c] (map-indexed vector classes)]
      (is (= (get expected c) (get actual (str "p_" i)))))))

(deftest largest-remainder-3-matches-cljc-map-fold
  (let [cases [["a" 10 5 3 2 1]
               ["b" 7 1 1 1 1]
               ["c" 5 10 0 0 1]
               ["d" 0 1 1 1 1]
               ["e" 9 0 0 0 1]
               ["f" 3 1 1 1 1]
               ["g" 20 7 2 1 1]
               ["h" 4 1 1 1 2]
               ["i" 11 4 4 2 1]]
        kotoba-cases (into {}
                           (map (fn [[label total wt wm wp floor]]
                                  [label (str "(largest-remainder-3 "
                                              total " " wt " " wm " " wp " " floor ")")])
                                cases))
        actual (compile-i64-cases
                (merge kotoba-cases
                       {"lane" "(lane-base)"
                        ;; unpack one known pack: a → text 5 media 3 post 2
                        "ut" "(seats-text (largest-remainder-3 10 5 3 2 1))"
                        "um" "(seats-media (largest-remainder-3 10 5 3 2 1))"
                        "up" "(seats-postproc (largest-remainder-3 10 5 3 2 1))"}))]
    (is (= 65536 (get actual "lane")))
    (is (= 5 (get actual "ut")))
    (is (= 3 (get actual "um")))
    (is (= 2 (get actual "up")))
    (doseq [[label total wt wm wp floor] cases]
      (let [cljc (largest-remainder total
                                    {:text-pool wt :media-pool wm :postproc-pool wp}
                                    floor)
            packed (get actual label)
            got (unpack packed)]
        (is (= cljc got) (str label " cljc=" cljc " kotoba=" got " packed=" packed))))))

(deftest pool-demand-pack-matches-cljc
  (let [cases [["d0" 0 0 0 0 0]
               ["d1" 5 2 1 0 3]
               ["d2" 1 0 0 0 0]
               ["d3" 0 1 1 1 0]
               ["d4" 10 0 0 5 2]]
        kotoba-cases (into {}
                           (map (fn [[label t i v a p]]
                                  [label (str "(pool-demand-pack "
                                              t " " i " " v " " a " " p ")")])
                                cases))
        actual (compile-i64-cases kotoba-cases)]
    (doseq [[label t i v a p] cases]
      (let [cljc (rb/pool-demand {:text t :image i :video v :audio a :postproc p})
            got (unpack (get actual label))]
        (is (= cljc got) (str label " cljc=" cljc " kotoba=" got))))))

(deftest seats-from-pool-pack-composes-largest-remainder
  (let [actual (compile-i64-cases
                {"s1" "(seats-from-pool-pack 10 (pool-demand-pack 5 2 1 0 3) 1)"
                 "s2" "(seats-from-pool-pack 7 (pool-demand-pack 1 1 0 0 1) 1)"
                 "s3" "(seats-from-pool-pack 0 (pool-demand-pack 5 2 1 0 3) 1)"})
        ;; class demand 5,2,1,0,3 → pool weights 5,3,3
        cljc1 (largest-remainder 10 (rb/pool-demand {:text 5 :image 2 :video 1 :audio 0 :postproc 3}) 1)
        cljc2 (largest-remainder 7 (rb/pool-demand {:text 1 :image 1 :video 0 :audio 0 :postproc 1}) 1)
        cljc3 (largest-remainder 0 (rb/pool-demand {:text 5 :image 2 :video 1 :audio 0 :postproc 3}) 1)]
    (is (= cljc1 (unpack (get actual "s1"))))
    (is (= cljc2 (unpack (get actual "s2"))))
    (is (= cljc3 (unpack (get actual "s3"))))))

(deftest classify-run-flags-matches-demand-cond-order
  (let [actual (compile-i64-cases
                {;; priority: images > video > audio > swarm > tokens
                 "c_img" "(classify-run-flags 1 1 1 1 1)"
                 "c_vid" "(classify-run-flags 0 1 1 1 1)"
                 "c_aud" "(classify-run-flags 0 0 1 1 1)"
                 "c_sw" "(classify-run-flags 0 0 0 1 1)"
                 "c_tok" "(classify-run-flags 0 0 0 0 1)"
                 "c_none" "(classify-run-flags 0 0 0 0 0)"})]
    (is (= 2 (get actual "c_img")))
    (is (= 3 (get actual "c_vid")))
    (is (= 4 (get actual "c_aud")))
    (is (= 5 (get actual "c_sw")))
    (is (= 1 (get actual "c_tok")))
    (is (= 0 (get actual "c_none")))))
