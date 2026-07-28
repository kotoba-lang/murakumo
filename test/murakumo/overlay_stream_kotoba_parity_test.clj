(ns murakumo.overlay-stream-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.overlay.stream :as stream]))
(def port-source (slurp "kotoba/overlay_stream_core.kotoba"))
(def export-prefix "default-window-size advance-seq ack-accepted?")

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest stream-scalars-match
  (let [actual (compile-i64-cases
                {"w" "(default-window-size)"
                 "a0" "(advance-seq 0)"
                 "a1" "(advance-seq 1)"
                 "t" "(ack-accepted? 1)"
                 "f" "(ack-accepted? 0)"})
        s (stream/open-stream {:overlay "o" :node "n" :name "x" :principal {}} "ssh" 0)]
    (is (= stream/default-window-size (get actual "w")))
    (is (= (:window s) (get actual "w")))
    (is (= (:next-seq (stream/advance s)) (get actual "a0")))
    (is (= 2 (get actual "a1")))
    (is (= 1 (get actual "t")))
    (is (= 0 (get actual "f")))))
