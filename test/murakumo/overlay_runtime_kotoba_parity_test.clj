(ns murakumo.overlay-runtime-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.overlay.driver :as driver]
            [murakumo.overlay.runtime :as runtime]))

(def port-source (slurp "kotoba/overlay_runtime_core.kotoba"))
(def export-prefix
  (str "default-relay-port default-web-port default-quic-port "
       "default-port-for-kind known-adapter? adapter-kind "
       "digit-char nat-str i64-str endpoint-kind scheme-prefix-host "
       "starts-with? scheme-quic scheme-webrtc scheme-relay scheme-webtransport "
       "kind-quic kind-webrtc kind-webtransport kind-relay kind-other "
       "adapter-relay adapter-quic adapter-webrtc adapter-webtransport "
       "adapter-relay-client adapter-kind-relay-runtime adapter-kind-quic "
       "adapter-kind-webrtc adapter-kind-webtransport adapter-kind-relay"))

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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

(deftest ports-and-adapters
  (let [n (compile-i64-cases
           {"r" "(default-relay-port)" "w" "(default-web-port)" "q" "(default-quic-port)"
            "pq" (str "(default-port-for-kind " (kotoba-literal "quic") ")")
            "pr" (str "(default-port-for-kind " (kotoba-literal "relay") ")")
            "pw" (str "(default-port-for-kind " (kotoba-literal "webrtc") ")")
            "k1" (str "(known-adapter? " (kotoba-literal "murakumo.runtime.quic") ")")
            "k0" (str "(known-adapter? " (kotoba-literal "nope") ")")})
        s (compile-string-cases
           {"ak" (str "(adapter-kind " (kotoba-literal "murakumo.runtime.quic") ")")
            "ar" (str "(adapter-kind " (kotoba-literal "murakumo.runtime.relay") ")")
            "sq" "(scheme-quic)"
            "aq" "(adapter-quic)"
            "arly" "(adapter-relay)"
            "kq" "(kind-quic)"
            "ko" "(kind-other)"
            "akr" "(adapter-kind-relay-runtime)"})]
    (is (= runtime/default-relay-port (get n "r")))
    (is (= runtime/default-web-port (get n "w")))
    (is (= runtime/default-quic-port (get n "q")))
    (is (= (get runtime/default-port-by-kind :quic) (get n "pq")))
    (is (= (get runtime/default-port-by-kind :relay) (get n "pr")))
    (is (= (get runtime/default-port-by-kind :webrtc) (get n "pw")))
    (is (= (if (runtime/known-adapter? "murakumo.runtime.quic") 1 0) (get n "k1")))
    (is (= 0 (get n "k0")))
    (is (= (name (:kind (runtime/adapter "murakumo.runtime.quic"))) (get s "ak")))
    (is (= (name (:kind (runtime/adapter "murakumo.runtime.relay"))) (get s "ar")))
    (is (= runtime/scheme-quic (get s "sq")))
    (is (= "quic://" (get s "sq")))
    (is (= runtime/adapter-quic (get s "aq")))
    (is (= "murakumo.runtime.quic" (get s "aq")))
    (is (= runtime/adapter-relay (get s "arly")))
    (is (= runtime/kind-quic (get s "kq")))
    (is (= runtime/kind-other (get s "ko")))
    (is (= "other" (get s "ko")))
    (is (= runtime/adapter-kind-relay-runtime (get s "akr")))
    (is (= "relay-runtime" (get s "akr")))
    (is (true? (runtime/known-adapter? runtime/adapter-quic)))
    (is (= :quic (:kind (runtime/adapter runtime/adapter-quic))))))

(deftest endpoint-kind-and-host-parse
  (let [actual (compile-string-cases
                {"eq" (str "(endpoint-kind " (kotoba-literal "quic://asher:4001") ")")
                 "er" (str "(endpoint-kind " (kotoba-literal "relay://jp/bafyNode") ")")
                 "ew" (str "(endpoint-kind " (kotoba-literal "webrtc://h:1") ")")
                 "eo" (str "(endpoint-kind " (kotoba-literal "http://x") ")")
                 "h1" (str "(scheme-prefix-host " (kotoba-literal "relay://jp-tyo-1.murakumo.cloud") ")")
                 "h2" (str "(scheme-prefix-host " (kotoba-literal "quic://asher:4001") ")")
                 "h3" (str "(scheme-prefix-host " (kotoba-literal "relay://jp/bafy") ")")})]
    (is (= (name (driver/endpoint-kind "quic://asher:4001")) (get actual "eq")))
    (is (= (name (driver/endpoint-kind "relay://jp/bafyNode")) (get actual "er")))
    (is (= (name (driver/endpoint-kind "webrtc://h:1")) (get actual "ew")))
    (is (= "other" (get actual "eo")))
    (is (= (:host (runtime/relay-url-parts "relay://jp-tyo-1.murakumo.cloud"))
           (get actual "h1")))
    (is (= "asher" (get actual "h2")))
    (is (= "jp" (get actual "h3")))))
