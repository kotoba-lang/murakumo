(ns murakumo.tunnel-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.tunnel :as tunnel]))

(def port-source (slurp "kotoba/tunnel_core.kotoba"))
(def export-prefix
  "default-connect-timeout-s default-control-persist-s rc-marker digit-char nat-str i64-str wrap-cmd connect-timeout-opt control-path-opt control-persist-opt scp-dest close-master-control-opt ensure-forward-command replace-forward-command remote-curl-command marker-prefix? strip-marker-digits")

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

(deftest wrap-and-commands-match
  (let [actual (compile-string-cases
                {"m" "(rc-marker)"
                 "w" (str "(wrap-cmd " (kotoba-literal "exit 7") ")")
                 "ct" "(connect-timeout-opt 8)"
                 "cp" (str "(control-path-opt " (kotoba-literal "/tmp/m/%C") ")")
                 "cs" "(control-persist-opt 45)"
                 "sd" (str "(scp-dest " (kotoba-literal "asher") " "
                           (kotoba-literal ".murakumo/bin/kotoba") ")")
                 "cm" (str "(close-master-control-opt " (kotoba-literal "/tmp/m/%C") ")")
                 "ef" (str "(ensure-forward-command 18099 8077 "
                           (kotoba-literal "asher") ")")
                 "rf" (str "(replace-forward-command 18077 8077 "
                           (kotoba-literal "asher") ")")
                 "rc" (str "(remote-curl-command "
                           (kotoba-literal "http://localhost:8077/health") ")")})]
    (is (= tunnel/rc-marker (get actual "m")))
    (is (= (tunnel/wrap-cmd "exit 7") (get actual "w")))
    (is (str/includes? (last (tunnel/ssh-argv "asher" "exit 7")) (get actual "w")))
    (is (= "ConnectTimeout=8" (get actual "ct")))
    (is (= "ControlPath=/tmp/m/%C" (get actual "cp")))
    (is (= "ControlPersist=45s" (get actual "cs")))
    (is (= "asher:.murakumo/bin/kotoba" (get actual "sd")))
    (is (= "ControlPath=/tmp/m/%C" (get actual "cm")))
    (is (= (tunnel/ensure-forward-command 18099 8077 "asher") (get actual "ef")))
    (is (= (tunnel/replace-forward-command 18077 8077 "asher") (get actual "rf")))
    (is (= (tunnel/remote-curl-command "http://localhost:8077/health") (get actual "rc")))))

(deftest constants-and-marker-helpers
  (let [n (compile-i64-cases
           {"t" "(default-connect-timeout-s)"
            "p" "(default-control-persist-s)"
            "mp" (str "(marker-prefix? " (kotoba-literal "__murakumo_rc=7") ")")
            "mn" (str "(marker-prefix? " (kotoba-literal "partial") ")")})
        s (compile-string-cases
           {"d" (str "(strip-marker-digits " (kotoba-literal "__murakumo_rc=7") ")")})]
    (is (= tunnel/default-connect-timeout-s (get n "t")))
    (is (= tunnel/default-control-persist-s (get n "p")))
    (is (= 1 (get n "mp")))
    (is (= 0 (get n "mn")))
    (is (= "7" (get s "d")))))
