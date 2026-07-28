(ns murakumo.overlay-driver-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.overlay.driver :as driver]))

(def port-source (slurp "kotoba/overlay_driver_core.kotoba"))
(def export-prefix
  "endpoint-kind blank? option-name dial-ok-reason command-is-dial?")

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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

(deftest endpoint-kind-matches-driver
  (let [actual (compile-string-cases
                {"q" (str "(endpoint-kind " (kotoba-literal "quic://asher:4001") ")")
                 "w" (str "(endpoint-kind " (kotoba-literal "webrtc://h:1") ")")
                 "h" (str "(endpoint-kind " (kotoba-literal "https://x/.well-known") ")")
                 "r" (str "(endpoint-kind " (kotoba-literal "relay://jp/n") ")")
                 "u" (str "(endpoint-kind " (kotoba-literal "ftp://x") ")")
                 "oname" (str "(option-name " (kotoba-literal "--overlay") ")")
                 "rok" (str "(dial-ok-reason 1 0)")
                 "rmiss" (str "(dial-ok-reason 1 2)")
                 "runk" (str "(dial-ok-reason 0 0)")})]
    (is (= (name (driver/endpoint-kind "quic://asher:4001")) (get actual "q")))
    (is (= (name (driver/endpoint-kind "webrtc://h:1")) (get actual "w")))
    (is (= (name (driver/endpoint-kind "https://x/.well-known")) (get actual "h")))
    (is (= (name (driver/endpoint-kind "relay://jp/n")) (get actual "r")))
    (is (= (name (driver/endpoint-kind "ftp://x")) (get actual "u")))
    (is (= "overlay" (get actual "oname")))
    (is (= "ok" (get actual "rok")))
    (is (= "missing-options" (get actual "rmiss")))
    (is (= "unknown-command" (get actual "runk"))))
  (let [n (compile-i64-cases
           {"b0" (str "(blank? " (kotoba-literal "") ")")
            "b1" (str "(blank? " (kotoba-literal "x") ")")
            "cd" (str "(command-is-dial? " (kotoba-literal "dial") ")")
            "cn" (str "(command-is-dial? " (kotoba-literal "relay") ")")})]
    (is (= 1 (get n "b0")))
    (is (= 0 (get n "b1")))
    (is (= 1 (get n "cd")))
    (is (= 0 (get n "cn")))))
