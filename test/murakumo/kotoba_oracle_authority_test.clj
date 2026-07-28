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
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.kekkai.gate :as gate]
            [murakumo.token :as tok]
            [murakumo.report :as report]
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
  (is (oracle/ready? :token))
  (is (oracle/ready? :report))
  (is (some #{:kekkai-gate} (oracle/catalog-ids)))
  (is (some #{:token} (oracle/catalog-ids)))
  (is (some #{:report} (oracle/catalog-ids))))

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


(def ^:private token-source "kotoba/token_core.kotoba")
(def ^:private token-resource "murakumo/oracle/token_core.kir.edn")

(defn- token-live-kir []
  (:kir (compiler/compile-source (slurp token-source) :wasm32-kotoba-v1 {})))

(defn- token-resource-kir []
  (edn/read-string (slurp (io/resource token-resource))))

(deftest product-shell-token-uses-oracle-results
  (testing "encode-claims-json + signing-input + wire-token via oracle"
    (is (= "{\"sub\":\"a\",\"scope\":\"chat\",\"iat\":1,\"exp\":2}"
           (tok/encode-claims-json {:sub "a" :scope "chat" :iat 1 :exp 2})))
    (is (= "mk1.PAY" (tok/signing-input "PAY")))
    (is (= "mk1.PAY.SIG" (tok/wire-token "PAY" "SIG"))))
  (testing "constant-time= / version / scope via oracle"
    (is (true? (tok/constant-time= "abc" "abc")))
    (is (false? (tok/constant-time= "abc" "abd")))
    (is (true? (tok/version-ok? "mk1")))
    (is (false? (tok/version-ok? "mk0")))
    (is (true? (tok/scope-allows? "all" "chat")))
    (is (false? (tok/scope-allows? "image" "chat"))))
  (testing "claims fields via oracle claim-*"
    (let [cl (tok/claims {:sub "x" :scope "chat" :now 100 :ttl 10})]
      (is (= "x" (:sub cl)))
      (is (= "chat" (:scope cl)))
      (is (= 100 (:iat cl)))
      (is (= 110 (:exp cl))))
    (let [cl (tok/claims {:now 0})]
      (is (= "anonymous" (:sub cl)))
      (is (= "all" (:scope cl)))
      (is (= 2592000 (:exp cl))))))

(deftest token-oracle-call-matches-live-compile
  (let [live (token-live-kir)]
    (is (= (ir/execute live 'encode-claims-json ["shinshi" "chat" 1 2])
           (oracle/call :token 'encode-claims-json ["shinshi" "chat" 1 2])))
    (is (= (ir/execute live 'signing-input ["P"])
           (oracle/call :token 'signing-input ["P"])))
    (is (= (ir/execute live 'wire-token ["P" "S"])
           (oracle/call :token 'wire-token ["P" "S"])))
    (is (= (ir/execute live 'constant-time-eq ["ab" "ab"])
           (oracle/call :token 'constant-time-eq ["ab" "ab"])))
    (is (= (ir/execute live 'scope-allows? ["all" "x"])
           (oracle/call :token 'scope-allows? ["all" "x"])))))

(deftest token-precompiled-kir-does-not-drift
  (is (= (token-live-kir) (token-resource-kir))
      "token KIR drift — run: clojure -M:test -m murakumo.kotoba-oracle-gen (or deps -M -m ...)"))

(def ^:private report-source "kotoba/report_core.kotoba")
(def ^:private report-resource "murakumo/oracle/report_core.kir.edn")

(defn- report-live-kir []
  (:kir (compiler/compile-source (slurp report-source) :wasm32-kotoba-v1 {})))

(defn- report-resource-kir []
  (edn/read-string (slurp (io/resource report-resource))))

(deftest product-shell-report-uses-oracle-results
  (testing "table headers/rows via report oracle"
    (is (= "NODE       TAILSCALE-IP     ONLINE   SSH       MESH"
           (report/nodes-header)))
    (is (= (report/nodes-row {:name "asher" :ip "100.1.2.3" :online? true} true "up/running")
           (oracle/call :report 'nodes-row ["asher" "100.1.2.3" 1 1 "up/running"])))
    (is (= "installed/running" (report/mesh-status "installed" "running")))
    (is (str/starts-with? (report/command-help) "murakumo —")))
  (testing "constant lines from oracle"
    (is (= "unreachable — skipped" report/unreachable-skipped-line))
    (is (string? report/mesh-pass1-line))))

(deftest report-oracle-call-matches-live-compile
  (let [live (report-live-kir)]
    (is (= (ir/execute live 'nodes-header [])
           (oracle/call :report 'nodes-header [])))
    (is (= (ir/execute live 'nodes-row ["a" "1.2.3.4" 1 0 "mesh"])
           (oracle/call :report 'nodes-row ["a" "1.2.3.4" 1 0 "mesh"])))
    (is (= (ir/execute live 'status-header [])
           (oracle/call :report 'status-header [])))
    (is (= (ir/execute live 'command-help [])
           (oracle/call :report 'command-help [])))))

(deftest report-precompiled-kir-does-not-drift
  (is (= (report-live-kir) (report-resource-kir))
      "report KIR drift — regenerate report_core.kir.edn via kotoba-oracle-gen"))
