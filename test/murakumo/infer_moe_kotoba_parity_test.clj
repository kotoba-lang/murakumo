(ns murakumo.infer-moe-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.moe :as moe]
            [murakumo.infer.plan :as plan]))

(def port-source (slurp "kotoba/infer_moe_core.kotoba"))
(def export-prefix "gib capacity-default expert-ratio-milli verdict-name resident-est")
(def GiB plan/GiB)

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

(deftest capacity-default-matches-readme-table
  (let [corpus [[(* 31 GiB) 0]
                [(* 32 GiB) 208]
                [(* 48 GiB) 320]
                [(* 64 GiB) 432]
                [(* 128 GiB) 512]
                [(* 256 GiB) 512]]
        cases (into {} (map-indexed
                        (fn [i [u _]] [(str "c_" i) (str "(capacity-default " u ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [u exp]] (map-indexed vector corpus)]
      (testing (str u)
        (let [cljc (moe/capacity-for-usable u)
              expected (or cljc 0)]
          (is (= expected exp))
          (is (= expected (get actual (str "c_" i)))))))))

(deftest expert-ratio-and-verdict-match
  (let [qwen {:model/experts 512 :model/active-experts 10 :model/moe-shared-expert? true}
        low {:model/experts 512 :model/active-experts 100 :model/moe-shared-expert? true}
        nos {:model/experts 512 :model/active-experts 10 :model/moe-shared-expert? false}
        unk {}
        actual-i (compile-i64-cases
                  {"r" "(expert-ratio-milli 512 10)"
                   "r0" "(expert-ratio-milli 512 0)"})
        actual-s (compile-string-cases
                  {"v1" "(verdict-name 512 10 1)"
                   "v2" "(verdict-name 512 100 1)"
                   "v3" "(verdict-name 512 10 0)"
                   "v4" "(verdict-name 0 0 0)"})]
    (is (= (long (* 1000 (moe/expert-ratio qwen))) (get actual-i "r")))
    (is (= 0 (get actual-i "r0")))
    (is (= (name (:verdict (moe/verdict qwen))) (get actual-s "v1")))
    (is (= (name (:verdict (moe/verdict low))) (get actual-s "v2")))
    (is (= (name (:verdict (moe/verdict nos))) (get actual-s "v3")))
    (is (= (name (:verdict (moe/verdict unk))) (get actual-s "v4")))))

(deftest resident-est-matches-moe
  (let [w 46000000000 e 512 c 208
        actual (compile-i64-cases
                {"r" (str "(resident-est " w " " e " " c ")")
                 "f" (str "(resident-est " w " 0 0)")})]
    (is (= (moe/resident-bytes-estimate {:model/weight-bytes w :model/experts e} c)
           (get actual "r")))
    (is (= (moe/resident-bytes-estimate {:model/weight-bytes w} nil)
           (get actual "f")))))
