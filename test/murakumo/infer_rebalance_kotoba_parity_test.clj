(ns murakumo.infer-rebalance-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.rebalance :as rb]))

(def port-source (slurp "kotoba/infer_rebalance_core.kotoba"))

(def ^:private rebal-pair-lit
  "[:record :rebalance/pair [[:a :i64] [:b :i64]]]")
(def ^:private rebal-triple-lit
  "[:record :rebalance/triple [[:a :i64] [:b :i64] [:c :i64]]]")
(def ^:private seats-in-lit
  "[:record :rebalance/seats-in [[:total :i64] [:text-w :i64] [:media-w :i64] [:postproc-w :i64] [:floor :i64]]]")

(defn- rebal-pair-call [export a b]
  (str "(" export " (record-new " rebal-pair-lit " " a " " b "))"))

(defn- rebal-pair-bool [export a b]
  (str "(if " (rebal-pair-call export a b) " 1 0)"))

(defn- rebal-triple-call [export a b c]
  (str "(" export " (record-new " rebal-triple-lit " " a " " b " " c "))"))

(def ^:private reb-triple-lit
  "[:record :rebalance/triple [[:a :i64] [:b :i64] [:c :i64]]]")
(def ^:private reb-order-nth-lit
  "[:record :rebalance/order-nth [[:i0 :i64] [:i1 :i64] [:i2 :i64] [:n :i64]]]")

(defn- seat-order-call [st sm sp]
  (str "(seat-order-record (record-new " reb-triple-lit " " st " " sm " " sp "))"))

(defn- order-nth-call [st sm sp n]
  (str "(let [o " (seat-order-call st sm sp) "] "
       "(order-nth (record-new " reb-order-nth-lit " "
       "(record-get o :i0) (record-get o :i1) (record-get o :i2) " n ")))"))
(def export-prefix
  (str "shard-ceiling-gb os-kv-headroom-gb usable-gb pool-for-class "
       "seats-of-text seats-of-media seats-of-postproc seats-total "
       "pool-demand-record pool-weight-text pool-weight-media pool-weight-postproc "
       "pool-seats-of-text pool-seats-of-media pool-seats-of-postproc "
       "classify-run-flags "
       "demand-empty demand-text demand-image demand-video "
       "demand-audio demand-postproc demand-inc demand-to-pool-record "
       "workers-count seats-equal "
       "seats-for-online-text seats-for-online-media seats-for-online-postproc "
       "pipeline-effective-gb "
       "node-online? move-needed "
       "rebalance-reason-code rebalance-reason-name "
       "digit-char nat-str i64-str pool-code pool-name "
       "seat-order-record order-nth take-end take-count "
       "pipeline-note rebalance-reason-detail"))

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


(def ^:private seats-in-literal
  "T5.2 native: seats-of-* input record full descriptor."
  (str "[:record :rebalance/seats-in "
       "[[:total :i64] [:text-w :i64] [:media-w :i64] "
       "[:postproc-w :i64] [:floor :i64]]]"))

(defn- seats-in-call [export total wt wm wp floor]
  (str "(" export " (record-new " seats-in-literal " "
       total " " wt " " wm " " wp " " floor "))"))

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
        ;; T5.3: three scalar lane projections, no pack. One label per lane.
        kotoba-cases (into {}
                           (mapcat (fn [[label total wt wm wp floor]]
                                     [[(str label "-t") (seats-in-call "seats-of-text" total wt wm wp floor)]
                                      [(str label "-m") (seats-in-call "seats-of-media" total wt wm wp floor)]
                                      [(str label "-p") (seats-in-call "seats-of-postproc" total wt wm wp floor)]])
                                   cases))
        actual (compile-i64-cases
                (merge kotoba-cases
                       {"ut" (seats-in-call "seats-of-text" 10 5 3 2 1)
                        "um" (seats-in-call "seats-of-media" 10 5 3 2 1)
                        "up" (seats-in-call "seats-of-postproc" 10 5 3 2 1)
                        "tot" (seats-in-call "seats-total" 10 5 3 2 1)}))]
    (is (= 5 (get actual "ut")))
    (is (= 3 (get actual "um")))
    (is (= 2 (get actual "up")))
    (is (= 10 (get actual "tot")))
    (doseq [[label total wt wm wp floor] cases]
      (let [cljc (largest-remainder total
                                    {:text-pool wt :media-pool wm :postproc-pool wp}
                                    floor)
            got {:text-pool (get actual (str label "-t"))
                 :media-pool (get actual (str label "-m"))
                 :postproc-pool (get actual (str label "-p"))}]
        (is (= cljc got) (str label " cljc=" cljc " kotoba=" got))))))

(def ^:private demand-ty
  "[:record :rebalance/demand [[:text :i64] [:image :i64] [:video :i64] [:audio :i64] [:postproc :i64]]]")

(defn- pool-demand-call [t i v a p]
  (str "(pool-demand-record (record-new " demand-ty " "
       t " " i " " v " " a " " p "))"))

(deftest pool-demand-record-matches-cljc
  (let [cases [["d0" 0 0 0 0 0]
               ["d1" 5 2 1 0 3]
               ["d2" 1 0 0 0 0]
               ["d3" 0 1 1 1 0]
               ["d4" 10 0 0 5 2]]
        kotoba-cases (into {}
                           (mapcat (fn [[label t i v a p]]
                                     (let [r (pool-demand-call t i v a p)]
                                       [[(str label "-t") (str "(pool-weight-text " r ")")]
                                        [(str label "-m") (str "(pool-weight-media " r ")")]
                                        [(str label "-p") (str "(pool-weight-postproc " r ")")]]))
                                   cases))
        actual (compile-i64-cases kotoba-cases)]
    (doseq [[label t i v a p] cases]
      (let [cljc (rb/pool-demand {:text t :image i :video v :audio a :postproc p})
            got {:text-pool (get actual (str label "-t"))
                 :media-pool (get actual (str label "-m"))
                 :postproc-pool (get actual (str label "-p"))}]
        (is (= cljc got) (str label " cljc=" cljc " kotoba=" got))))))

(deftest pool-seats-of-composes-largest-remainder
  (let [;; demand class counts → pool weights (text, media=img+vid+aud, postproc)
        pool-w (fn [t i v a p] [t (+ i v a) p])
        lanes (fn [prefix total tw mw pw]
                (into {} (map (fn [[k lane]]
                                [(str prefix "-" (name k))
                                 (seats-in-call (str "pool-seats-of-" lane)
                                                total tw mw pw 1)])
                              {:t "text" :m "media" :p "postproc"})))
        [t1 m1 p1] (pool-w 5 2 1 0 3)
        [t2 m2 p2] (pool-w 1 1 0 0 1)
        actual (compile-i64-cases
                (merge (lanes "s1" 10 t1 m1 p1)
                       (lanes "s2" 7 t2 m2 p2)
                       (lanes "s3" 0 t1 m1 p1)))
        got (fn [prefix] {:text-pool (get actual (str prefix "-t"))
                          :media-pool (get actual (str prefix "-m"))
                          :postproc-pool (get actual (str prefix "-p"))})]
    ;; class demand 5,2,1,0,3 → pool weights 5,3,3
    (is (= (largest-remainder 10 (rb/pool-demand {:text 5 :image 2 :video 1 :audio 0 :postproc 3}) 1)
           (got "s1")))
    (is (= (largest-remainder 7 (rb/pool-demand {:text 1 :image 1 :video 0 :audio 0 :postproc 1}) 1)
           (got "s2")))
    (is (= (largest-remainder 0 (rb/pool-demand {:text 5 :image 2 :video 1 :audio 0 :postproc 3}) 1)
           (got "s3")))))

(defn- opt-str-form [s]
  (if (nil? s)
    "(option-none-of [:option :string])"
    (str "(option-some-of [:option :string] " (kotoba-literal s) ")")))

(def ^:private run-flags-ty
  "[:record :rebalance/run-flags [[:images [:option :string]] [:video [:option :string]] [:audio [:option :string]] [:swarm [:option :string]] [:tokens [:option :string]]]]")

(defn- classify-call [img vid aud sw tok]
  (str "(classify-run-flags (record-new " run-flags-ty " "
       img " " vid " " aud " " sw " " tok "))"))

(deftest classify-run-flags-matches-demand-cond-order
  (let [s (opt-str-form "x")
        n (opt-str-form nil)
        actual (compile-i64-cases
                {;; priority: images > video > audio > swarm > tokens
                 "c_img" (classify-call s s s s s)
                 "c_vid" (classify-call n s s s s)
                 "c_aud" (classify-call n n s s s)
                 "c_sw" (classify-call n n n s s)
                 "c_tok" (classify-call n n n n s)
                 "c_none" (classify-call n n n n n)})]
    (is (= 2 (get actual "c_img")))
    (is (= 3 (get actual "c_vid")))
    (is (= 4 (get actual "c_aud")))
    (is (= 5 (get actual "c_sw")))
    (is (= 1 (get actual "c_tok")))
    (is (= 0 (get actual "c_none")))))

(defn- run-flags
  "Host projection of demand-from-runs unit/kind presence → optional tokens."
  [run]
  (let [u (or (:units run) {})
        kind (or (:run/kind run) (:model run))]
    [(when (or (:images u) (get u "images")) "images")
     (when (or (:video-seconds u) (get u "video-seconds")) "video")
     (when (or (:audio-seconds u) (get u "audio-seconds")) "audio")
     (when (= "browser-swarm" (str kind)) "swarm")
     (when (or (:tokens u) (get u "tokens")) "tokens")]))

(deftest demand-from-runs-fold-matches-cljc
  "Host demand-from-runs; guest demand-inc single-step on :rebalance/demand-step.
  Final pool via demand-to-pool from demand-inc chain with scalar priors."
  (let [runs [{:units {:tokens 100}}
              {:units {:images 1}}
              {:units {:images 1}}
              {:model "browser-swarm" :units {:jobs 1}}
              {:units {:video-seconds 3}}]
        demand-step-lit
        "[:record :rebalance/demand-step [[:text :i64] [:image :i64] [:video :i64] [:audio :i64] [:postproc :i64] [:class-code :i64]]]"
        cljc (rb/demand-from-runs runs)
        pool (rb/pool-demand cljc)
        ;; last run class code (guest classify-run-flags)
        last-code
        (let [[img vid aud sw tok] (run-flags (last runs))]
          (classify-call (opt-str-form img)
                         (opt-str-form vid)
                         (opt-str-form aud)
                         (opt-str-form sw)
                         (opt-str-form tok)))
        ;; prior totals = host fold of all but last run
        prior (rb/demand-from-runs (butlast runs))
        final-guest
        (str "(demand-inc (record-new " demand-step-lit " "
             (long (:text prior 0)) " "
             (long (:image prior 0)) " "
             (long (:video prior 0)) " "
             (long (:audio prior 0)) " "
             (long (:postproc prior 0)) " "
             last-code "))")
        actual (compile-i64-cases
                {"t" (str "(demand-text " final-guest ")")
                 "i" (str "(demand-image " final-guest ")")
                 "v" (str "(demand-video " final-guest ")")
                 "a" (str "(demand-audio " final-guest ")")
                 "p" (str "(demand-postproc " final-guest ")")
                 "pt" (str "(pool-weight-text (demand-to-pool-record " final-guest "))")
                 "pm" (str "(pool-weight-media (demand-to-pool-record " final-guest "))")
                 "pp" (str "(pool-weight-postproc (demand-to-pool-record " final-guest "))")
                 "s1t" (str "(demand-text (demand-inc (record-new " demand-step-lit
                             " 0 0 0 0 0 1)))")
                 "s2i" (str "(demand-image (demand-inc (record-new " demand-step-lit
                             " 1 0 0 0 0 2)))")
                 "noop" (str "(demand-text (demand-inc (record-new " demand-step-lit
                              " 3 0 0 0 0 0)))")})]
    (is (= 1 (get actual "s1t")))
    (is (= 1 (get actual "s2i")))
    (is (= 3 (get actual "noop")) "class 0 leaves demand unchanged")
    (is (= (:text cljc) (get actual "t")))
    (is (= (:image cljc) (get actual "i")))
    (is (= (:video cljc) (get actual "v")))
    (is (= (:audio cljc) (get actual "a")))
    (is (= (:postproc cljc) (get actual "p")))
    (is (= 1 (get actual "t")))
    (is (= 2 (get actual "i")))
    (is (= 1 (get actual "v")))
    (is (= 1 (get actual "p")))
    (is (= pool {:text-pool (get actual "pt")
                 :media-pool (get actual "pm")
                 :postproc-pool (get actual "pp")}))))

(deftest placement-pure-layer
  (let [actual (compile-i64-cases
                {"w0" "(workers-count 0)"
                 "w1" "(workers-count 1)"
                 "w4" "(workers-count 4)"
                 "eq1" (rebal-pair-call "seats-equal" 10 10)
                 "eq0" (rebal-pair-call "seats-equal" 10 11)
                 "pipe" (rebal-pair-call "pipeline-effective-gb" 10 3)
                 "asg" (seats-in-call "seats-total" 4 2 1 1 1)
                 "mn0" (rebal-pair-bool "move-needed" 0 0)
                 "mn1" (rebal-pair-bool "move-needed" 0 1)
                 "rc0" (rebal-pair-call "rebalance-reason-code" 3 0)
                 "rc1" (rebal-pair-call "rebalance-reason-code" 0 0)
                 "rc2" (rebal-pair-call "rebalance-reason-code" 3 2)
                 ;; seats-for-online-*: 4 online → 3 workers; demand 5/2/0/0/1 → pool 5,2,1
                 "sfo-t" (seats-in-call "seats-for-online-text" 4 5 2 1 1)
                 "sfo-m" (seats-in-call "seats-for-online-media" 4 5 2 1 1)
                 "sfo-p" (seats-in-call "seats-for-online-postproc" 4 5 2 1 1)})
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
    (is (= expected-seats {:text-pool (get actual "sfo-t")
                           :media-pool (get actual "sfo-m")
                           :postproc-pool (get actual "sfo-p")}))))

(deftest node-online-and-reason-names
  (let [online (compile-i64-cases
                ;; Profile 5: node-online? is :bool
                {"up" "(if (node-online? \"up\") 1 0)"
                 "down" "(if (node-online? \"down\") 1 0)"
                 "empty" "(if (node-online? \"\") 1 0)"})
        names (compile-string-cases
               {"n0" "(rebalance-reason-name 0)"
                "n1" "(rebalance-reason-name 1)"
                "n2" "(rebalance-reason-name 2)"
                "d2" (rebal-pair-call "rebalance-reason-detail" 2 3)
                "d0" (rebal-pair-call "rebalance-reason-detail" 0 0)
                "pn" (rebal-pair-call "pipeline-note" 3 10)})]
    (is (= 1 (get online "up")))
    (is (= 0 (get online "down")))
    (is (= 0 (get online "empty")))
    (is (= "stable" (get names "n0")))
    (is (= "initial placement" (get names "n1")))
    (is (= "demand-shift" (get names "n2")))
    (is (= "3 node(s) re-placed by demand shift" (get names "d2")))
    (is (= "stable" (get names "d0")))
    (is (= "text pool sharded as a 3-way pipeline (~30GB effective, ceiling 10GB/node)"
           (get names "pn")))))

(deftest seat-order-and-take-slices
  (let [actual (compile-i64-cases
                {;; seats text=5 media=3 post=2 → order 0,1,2
                 "o0" (order-nth-call 5 3 2 0)
                 "o1" (order-nth-call 5 3 2 1)
                 "o2" (order-nth-call 5 3 2 2)
                 ;; media heaviest
                 "m0" (order-nth-call 1 9 2 0)
                 "m1" (order-nth-call 1 9 2 1)
                 "m2" (order-nth-call 1 9 2 2)
                 ;; equal seats → stable index order text,media,post
                 "e0" (order-nth-call 2 2 2 0)
                 "e1" (order-nth-call 2 2 2 1)
                 "e2" (order-nth-call 2 2 2 2)
                 "te" (rebal-triple-call "take-end" 0 2 5)
                 "te2" (rebal-triple-call "take-end" 2 3 5)
                 "te3" (rebal-triple-call "take-end" 4 3 5)
                 "tc" (rebal-pair-call "take-count" 0 2)
                 "pc" (str "(pool-code \"media-pool\")")
                 "pc2" (str "(pool-code \"text-pool\")")})
        names (compile-string-cases
               {"pn0" "(pool-name 0)"
                "pn1" "(pool-name 1)"
                "pn2" "(pool-name 2)"})]
    (is (= 0 (get actual "o0")))
    (is (= 1 (get actual "o1")))
    (is (= 2 (get actual "o2")))
    (is (= 1 (get actual "m0")))
    (is (= 2 (get actual "m1")))
    (is (= 0 (get actual "m2")))
    (is (= 0 (get actual "e0")))
    (is (= 1 (get actual "e1")))
    (is (= 2 (get actual "e2")))
    (is (= 2 (get actual "te")))
    (is (= 5 (get actual "te2")))
    (is (= 5 (get actual "te3")))
    (is (= 2 (get actual "tc")))
    (is (= 1 (get actual "pc")))
    (is (= 0 (get actual "pc2")))
    (is (= "text-pool" (get names "pn0")))
    (is (= "media-pool" (get names "pn1")))
    (is (= "postproc-pool" (get names "pn2")))
    (testing "cljc sort-by order matches seat-order-record"
      (let [seats {:text-pool 1 :media-pool 9 :postproc-pool 2}
            ordered (mapv first (sort-by (comp - val) seats))]
        (is (= [:media-pool :postproc-pool :text-pool] ordered))
        (is (= [1 2 0] [(get actual "m0") (get actual "m1") (get actual "m2")]))))))

