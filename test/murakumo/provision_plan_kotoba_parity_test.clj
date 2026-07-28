(ns murakumo.provision-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.provision.plan :as plan]))

(def port-source (slurp "kotoba/provision_plan_core.kotoba"))
(def export-prefix
  "plist-label remote-bin remote-store ssh-rsync-options peer-advertise-wait-ms default-p2p-port digit-char nat-str i64-str operator-seed-missing? resolve-p2p-port multiaddr webrtc-port mesh-binary-status-command remote-store-command")

(def fleet
  {:fleet/port 8077
   :fleet/p2p-port 4001
   :nodes [{:name "asher" :ip "100.0.0.1"}
           {:name "judah" :ip "100.0.0.2" :p2p-port 5001}]})

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

(deftest constants-and-commands-match
  (let [s (compile-string-cases
           {"pl" "(plist-label)" "rb" "(remote-bin)" "rs" "(remote-store)"
            "so" "(ssh-rsync-options)"
            "mb" "(mesh-binary-status-command)"
            "rc" "(remote-store-command)"
            "ma" (str "(multiaddr " (kotoba-literal "100.0.0.1") " 4001)")})
        n (compile-i64-cases
           {"w" "(peer-advertise-wait-ms)" "d" "(default-p2p-port)"
            "m0" (str "(operator-seed-missing? " (kotoba-literal "") ")")
            "m1" (str "(operator-seed-missing? " (kotoba-literal "seed") ")")
            "p0" "(resolve-p2p-port 0 0 1 4001)"
            "p1" "(resolve-p2p-port 1 5001 1 4001)"
            "p2" "(resolve-p2p-port 0 0 0 0)"
            "wp" "(webrtc-port 4001)"})]
    (is (= plan/plist-label (get s "pl")))
    (is (= plan/remote-bin (get s "rb")))
    (is (= plan/remote-store (get s "rs")))
    (is (= plan/ssh-rsync-options (get s "so")))
    (is (= (plan/mesh-binary-status-command) (get s "mb")))
    (is (= (plan/remote-store-command) (get s "rc")))
    (is (= (plan/multiaddr "100.0.0.1" 4001) (get s "ma")))
    (is (= plan/peer-advertise-wait-ms (get n "w")))
    (is (= 4001 (get n "d")))
    (is (= (if (plan/operator-seed-missing? "") 1 0) (get n "m0")))
    (is (= (if (plan/operator-seed-missing? "seed") 1 0) (get n "m1")))
    (is (= (plan/node-p2p-port fleet (first (:nodes fleet))) (get n "p0")))
    (is (= (plan/node-p2p-port fleet (second (:nodes fleet))) (get n "p1")))
    (is (= (plan/node-p2p-port {} {}) (get n "p2")))
    (is (= 4101 (get n "wp")))))
