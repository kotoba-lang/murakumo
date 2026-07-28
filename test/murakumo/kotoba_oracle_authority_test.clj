;; W6 product-shell oracle authority:
;;   1. precompiled KIR resource is loadable and executes pure helpers
;;   2. murakumo.kekkai.gate public API (JVM) matches live-compiled kotoba
;;   3. checked-in KIR resource does not drift from kotoba/kekkai_gate_core.kotoba
;;
;; This is the CI gate that keeps dual-source honest: kotoba source is SSoT,
;; the resource is the product artifact, cljc is the thin shell.

(ns murakumo.kotoba-oracle-authority-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.kekkai.gate :as gate]
            [murakumo.kotoba.oracle :as oracle]
            [murakumo.kotoba-oracle-gen :as gen]))

(def ^:private source-path "kotoba/kekkai_gate_core.kotoba")
(def ^:private resource-path "murakumo/oracle/kekkai_gate_core.kir.edn")

(defn- live-kir []
  (:kir (compiler/compile-source (slurp source-path) :wasm32-kotoba-v1 {})))

(defn- resource-kir []
  (edn/read-string (slurp (io/resource resource-path))))

(deftest oracle-catalog-ready
  (is (oracle/ready? :kekkai-gate))
  (is (some #{:kekkai-gate} (oracle/catalog-ids))))

(deftest product-shell-gate-uses-oracle-results
  (testing "parse-status delegates to kotoba parse-status-out"
    (is (= "authorized" (gate/parse-status {:exit 0 :out "authorized\n"})))
    (is (= "pending" (gate/parse-status {:exit 1 :out "pending\n"})))
    (is (= "unknown" (gate/parse-status {:exit 127 :out ""})))
    (is (= "unknown" (gate/parse-status {:exit 127 :out nil}))))
  (testing "denial-line delegates to denial-line-of"
    (is (= "[kekkai] judah: not authorized (pending) — excluded from fleet ops"
           (gate/denial-line {:name "judah" :kekkai/status "pending"}))))
  (testing "default-ledger-path + default-kekkai-dir from oracle"
    (is (= "kekkai-tailnet.edn" gate/default-ledger-path))
    (is (= "/home/jun/github/com-junkawasaki/orgs/kotoba-lang/kekkai"
           (gate/default-kekkai-dir "/home/jun"))))
  (testing "partition-nodes uses oracle authorized?"
    (let [nodes [{:name "a"} {:name "b"}]
          status {"a" "authorized" "b" "pending"}
          {:keys [admitted denied]} (gate/partition-nodes nodes status)]
      (is (= [{:name "a"}] admitted))
      (is (= [{:name "b" :kekkai/status "pending"}] denied)))))

(deftest oracle-call-matches-live-compile
  (let [live (live-kir)
        corpus [["authorized\n"] [""] ["pending\n"] ["revoked\n"]]]
    (doseq [args corpus]
      (is (= (ir/execute live 'parse-status-out args)
             (oracle/call :kekkai-gate 'parse-status-out args))
          (str "parse-status-out " (pr-str args))))
    (doseq [st ["authorized" "pending" "unknown"]]
      (is (= (ir/execute live 'authorized? [st])
             (oracle/call :kekkai-gate 'authorized? [st]))
          (str "authorized? " st)))
    (is (= (ir/execute live 'default-ledger-path [])
           (oracle/call :kekkai-gate 'default-ledger-path [])))
    (is (= (ir/execute live 'denial-line-of ["x" "revoked"])
           (oracle/call :kekkai-gate 'denial-line-of ["x" "revoked"])))
    (is (= (ir/execute live 'default-kekkai-dir-under ["/h"])
           (oracle/call :kekkai-gate 'default-kekkai-dir-under ["/h"])))))

(deftest precompiled-kir-does-not-drift-from-source
  "Fail CI when kotoba source changed but resource was not regenerated.
   Fix: clojure -M:test -m murakumo.kotoba-oracle-gen"
  (let [live (live-kir)
        shipped (resource-kir)]
    (is (= live shipped)
        (str "KIR drift: " source-path " ≠ classpath:" resource-path
             " — run: clojure -M:test -m murakumo.kotoba-oracle-gen"))))

(deftest gen-compile-kir-roundtrip
  (let [kir (gen/compile-kir source-path)]
    (is (map? kir))
    (is (= "authorized" (ir/execute kir 'parse-status-out ["authorized\n"])))))
