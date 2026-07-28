(ns murakumo.infer-rebalance-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.rebalance :as rb]))

(def port-source (slurp "kotoba/infer_rebalance_core.kotoba"))
(def export-prefix "shard-ceiling-gb os-kv-headroom-gb usable-gb pool-for-class")

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
