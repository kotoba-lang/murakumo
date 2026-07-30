(ns murakumo.overlay-driver-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.overlay.driver :as driver]))

(def port-source (slurp "kotoba/overlay_driver_core.kotoba"))
(def export-prefix
  (str "endpoint-kind blank? option-name dial-ok-reason command-is-dial? "
       "starts-with? scheme-quic scheme-webrtc scheme-https scheme-relay "
       "kind-quic kind-webrtc kind-webtransport kind-relay kind-unknown "
       "flag-dash-prefix cmd-dial "
       "reason-ok reason-unknown-command reason-missing-options"))

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
                 "rok" (str "(dial-ok-reason true 0)")
                 "rmiss" (str "(dial-ok-reason true 2)")
                 "runk" (str "(dial-ok-reason false 0)")
                 "sq" "(scheme-quic)"
                 "sw" "(scheme-webrtc)"
                 "sh" "(scheme-https)"
                 "sr" "(scheme-relay)"
                 "kq" "(kind-quic)"
                 "kw" "(kind-webtransport)"
                 "ku" "(kind-unknown)"
                 "fd" "(flag-dash-prefix)"
                 "cd" "(cmd-dial)"
                 "ro" "(reason-ok)"
                 "ru" "(reason-unknown-command)"
                 "rm" "(reason-missing-options)"})]
    (is (= (name (driver/endpoint-kind "quic://asher:4001")) (get actual "q")))
    (is (= (name (driver/endpoint-kind "webrtc://h:1")) (get actual "w")))
    (is (= (name (driver/endpoint-kind "https://x/.well-known")) (get actual "h")))
    (is (= (name (driver/endpoint-kind "relay://jp/n")) (get actual "r")))
    (is (= (name (driver/endpoint-kind "ftp://x")) (get actual "u")))
    (is (= "overlay" (get actual "oname")))
    (is (= "ok" (get actual "rok")))
    (is (= "missing-options" (get actual "rmiss")))
    (is (= "unknown-command" (get actual "runk")))
    (is (= driver/scheme-quic (get actual "sq")))
    (is (= "quic://" (get actual "sq")))
    (is (= driver/scheme-webrtc (get actual "sw")))
    (is (= driver/scheme-https (get actual "sh")))
    (is (= driver/scheme-relay (get actual "sr")))
    (is (= driver/kind-quic (get actual "kq")))
    (is (= driver/kind-webtransport (get actual "kw")))
    (is (= "webtransport" (get actual "kw")))
    (is (= driver/kind-unknown (get actual "ku")))
    (is (= driver/flag-dash-prefix (get actual "fd")))
    (is (= "--" (get actual "fd")))
    (is (= driver/cmd-dial (get actual "cd")))
    (is (= "dial" (get actual "cd")))
    (is (= driver/reason-ok (get actual "ro")))
    (is (= driver/reason-unknown-command (get actual "ru")))
    (is (= driver/reason-missing-options (get actual "rm"))))
  (let [n (compile-i64-cases
           ;; Profile 5: blank?/command-is-dial?/starts-with? are :bool.
           {"b0" (str "(if (blank? " (kotoba-literal "") ") 1 0)")
            "b1" (str "(if (blank? " (kotoba-literal "x") ") 1 0)")
            "cd" (str "(if (command-is-dial? " (kotoba-literal "dial") ") 1 0)")
            "cn" (str "(if (command-is-dial? " (kotoba-literal "relay") ") 1 0)")
            "sw" (str "(if (starts-with? " (kotoba-literal "quic://a") " "
                      (kotoba-literal "quic://") ") 1 0)")})]
    (is (= 1 (get n "b0")))
    (is (= 0 (get n "b1")))
    (is (= 1 (get n "cd")))
    (is (= 0 (get n "cn")))
    (is (= 1 (get n "sw")))))
