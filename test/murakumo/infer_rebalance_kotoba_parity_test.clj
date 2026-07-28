(ns murakumo.infer-rebalance-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.rebalance :as rb]))

(def port-source (slurp "kotoba/infer_rebalance_core.kotoba"))
(def export-prefix
  (str "shard-ceiling-gb os-kv-headroom-gb usable-gb pool-for-class lane-base "
       "largest-remainder-3 seats-text seats-media seats-postproc "
       "pool-demand-pack seats-from-pool-pack classify-run-flags "
       "demand-base demand-empty demand-text demand-image demand-video "
       "demand-audio demand-postproc demand-inc demand-to-pool-pack "
       "workers-count seats-for-online seats-equal pipeline-effective-gb "
       "node-online? move-needed assigned-from-seats "
       "rebalance-reason-code rebalance-reason-name"))

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

(defn- run-flags
  "Host projection of demand-from-runs unit/kind presence → classify-run-flags args."
  [run]
  (let [u (or (:units run) {})
        kind (or (:run/kind run) (:model run))]
    [(if (or (:images u) (get u "images")) 1 0)
     (if (or (:video-seconds u) (get u "video-seconds")) 1 0)
     (if (or (:audio-seconds u) (get u "audio-seconds")) 1 0)
     (if (= "browser-swarm" (str kind)) 1 0)
     (if (or (:tokens u) (get u "tokens")) 1 0)]))

(deftest demand-from-runs-fold-matches-cljc
  (let [runs [{:units {:tokens 100}}
              {:units {:images 1}}
              {:units {:images 1}}
              {:model "browser-swarm" :units {:jobs 1}}
              {:units {:video-seconds 3}}]
        ;; host projects flags; guest folds demand-inc + classify-run-flags
        fold (reduce (fn [expr run]
                       (let [[hi hv ha sw tok] (run-flags run)]
                         (str "(demand-inc " expr
                              " (classify-run-flags "
                              hi " " hv " " ha " " sw " " tok "))")))
                     "(demand-empty)"
                     runs)
        actual (compile-i64-cases
                {"t" (str "(demand-text " fold ")")
                 "i" (str "(demand-image " fold ")")
                 "v" (str "(demand-video " fold ")")
                 "a" (str "(demand-audio " fold ")")
                 "p" (str "(demand-postproc " fold ")")
                 "pool" (str "(demand-to-pool-pack " fold ")")
                 "base" "(demand-base)"})
        cljc (rb/demand-from-runs runs)
        pool (rb/pool-demand cljc)]
    (is (= 4096 (get actual "base")))
    (is (= (:text cljc) (get actual "t")))
    (is (= (:image cljc) (get actual "i")))
    (is (= (:video cljc) (get actual "v")))
    (is (= (:audio cljc) (get actual "a")))
    (is (= (:postproc cljc) (get actual "p")))
    (is (= 1 (get actual "t")))
    (is (= 2 (get actual "i")))
    (is (= 1 (get actual "v")))
    (is (= 1 (get actual "p")))
    (is (= pool (unpack (get actual "pool"))))))

(deftest placement-pure-layer
  (let [actual (compile-i64-cases
                {"w0" "(workers-count 0)"
                 "w1" "(workers-count 1)"
                 "w4" "(workers-count 4)"
                 "eq1" "(seats-equal 10 10)"
                 "eq0" "(seats-equal 10 11)"
                 "pipe" "(pipeline-effective-gb 10 3)"
                 "asg" "(assigned-from-seats (largest-remainder-3 4 2 1 1 1))"
                 "mn0" "(move-needed 0 0)"
                 "mn1" "(move-needed 0 1)"
                 "rc0" "(rebalance-reason-code 3 0)"
                 "rc1" "(rebalance-reason-code 0 0)"
                 "rc2" "(rebalance-reason-code 3 2)"
                 ;; seats-for-online: 4 online → 3 workers; demand 5/2/0/0/1 → pool 5,2,1
                 "sfo" "(seats-for-online 4 (pool-demand-pack 5 2 0 0 1) 1)"})
        lr @(var murakumo.infer.rebalance/largest-remainder)
        expected-seats (lr 3 (rb/pool-demand {:text 5 :image 2 :video 0 :audio 0 :postproc 1}) 1)]
    (is (= 0 (get actual "w0")))
    (is (= 0 (get actual "w1")))
    (is (= 3 (get actual "w4")))
    (is (= 1 (get actual "eq1")))
    (is (= 0 (get actual "eq0")))
    (is (= 30 (get actual "pipe")))
    (is (= 4 (get actual "asg")))
    (is (= 0 (get actual "mn0")))
    (is (= 1 (get actual "mn1")))
    (is (= 0 (get actual "rc0")))
    (is (= 1 (get actual "rc1")))
    (is (= 2 (get actual "rc2")))
    (is (= expected-seats (unpack (get actual "sfo"))))))

(deftest node-online-and-reason-names
  (let [online (compile-i64-cases
                {"up" "(node-online? \"up\")"
                 "down" "(node-online? \"down\")"
                 "empty" "(node-online? \"\")"})
        names (compile-string-cases
               {"n0" "(rebalance-reason-name 0)"
                "n1" "(rebalance-reason-name 1)"
                "n2" "(rebalance-reason-name 2)"})]
    (is (= 1 (get online "up")))
    (is (= 0 (get online "down")))
    (is (= 0 (get online "empty")))
    (is (= "stable" (get names "n0")))
    (is (= "initial placement" (get names "n1")))
    (is (= "demand-shift" (get names "n2")))))

