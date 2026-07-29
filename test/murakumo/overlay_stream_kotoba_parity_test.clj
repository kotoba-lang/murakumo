(ns murakumo.overlay-stream-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.overlay.stream :as stream]))
(def port-source (slurp "kotoba/overlay_stream_core.kotoba"))
(def export-prefix
  "default-window-size advance-seq ack-accepted? initial-next-seq type-stream type-frame type-ack")

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

(deftest stream-type-tokens-match
  (let [s (compile-string-cases
           {"ts" "(type-stream)"
            "tf" "(type-frame)"
            "ta" "(type-ack)"})
        n (compile-i64-cases
           {"ins" "(initial-next-seq)"
            "w" "(default-window-size)"})
        st (stream/open-stream {:overlay "o" :node "n" :name "x" :principal {}} "ssh" 0)
        fr (stream/frame st "p")
        ac (stream/ack {:stream "s" :seq 0} true)]
    (is (= stream/type-stream (get s "ts")))
    (is (= "murakumo.overlay.stream" (get s "ts")))
    (is (= stream/type-frame (get s "tf")))
    (is (= "murakumo.overlay.stream-frame" (get s "tf")))
    (is (= stream/type-ack (get s "ta")))
    (is (= "murakumo.overlay.stream-ack" (get s "ta")))
    (is (= stream/initial-next-seq (get n "ins")))
    (is (= 0 (get n "ins")))
    (is (= stream/default-window-size (get n "w")))
    (is (= stream/type-stream (:type st)))
    (is (= stream/initial-next-seq (:next-seq st)))
    (is (= stream/type-frame (:type fr)))
    (is (= stream/type-ack (:type ac)))))
