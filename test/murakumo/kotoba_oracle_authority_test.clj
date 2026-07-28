;; W6 product-shell oracle authority:
;;   1. precompiled KIR resources are loadable and execute pure helpers
;;   2. JVM public APIs (gate / token / report) match live-compiled kotoba
;;   3. checked-in KIR resources do not drift from kotoba/*_core.kotoba
;;
;; This is the CI gate that keeps dual-source honest: kotoba source is SSoT,
;; the resource is the product artifact, host ns is the thin shell.

(ns murakumo.kotoba-oracle-authority-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.kekkai.gate :as gate]
            [murakumo.token :as tok]
            [murakumo.report :as report]
            [murakumo.infer.plan :as plan]
            [murakumo.dash.state :as dash]
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
  (is (oracle/ready? :report-core))
  (is (oracle/ready? :infer-plan))
  (is (oracle/ready? :dash-state))
  (is (some #{:kekkai-gate} (oracle/catalog-ids)))
  (is (some #{:token} (oracle/catalog-ids)))
  (is (some #{:report-core} (oracle/catalog-ids)))
  (is (some #{:infer-plan} (oracle/catalog-ids)))
  (is (some #{:dash-state} (oracle/catalog-ids))))

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
  (testing "headers + pad via oracle"
    (is (= "NODE       TAILSCALE-IP     ONLINE   SSH       MESH"
           (report/nodes-header)))
    (is (= "NODE       HEALTH   WASM-EXEC    LINKS  P2P-PORT"
           (report/status-header)))
    (is (= "asher      100.0.0.1        yes      ok        installed/running"
           (report/nodes-row {:name "asher" :ip "100.0.0.1" :online? true}
                             true "installed/running")))
    (is (= "judah      down    "
           (report/status-down-row {:name "judah"})))
    (is (= "asher      ok       ready        3      4001"
           (report/status-row {:name "asher"}
                              {:subsystems {:wasm_executor "ready"}}
                              "3" 4001))))
  (testing "command-help is pure multi-line oracle string"
    (is (re-find #"murakumo — kotoba WASM mesh control plane" (report/command-help)))
    (is (re-find #"reconcile <murakumo.app.edn>" (report/command-help))))
  (testing "reconcile pure builders via oracle (host joins CSV)"
    (let [lines (report/reconcile-lines
                 {:fleet "f1" :ts "T"
                  :apps [{:app "a1" :cid "bafy1234567890abcd" :desired 2
                          :running ["n1"] :action :satisfied}]})]
      (is (= "reconcile f1  @ T" (first lines)))
      (is (= "  APP            CID        DESIRED RUNNING ACTION    DETAIL"
             (second lines)))
      (is (re-find #"a1" (nth lines 2)))
      (is (re-find #"on n1" (nth lines 2))))))

(deftest report-oracle-call-matches-live-compile
  (let [live (report-live-kir)]
    (is (= (ir/execute live 'nodes-header [])
           (oracle/call :report-core 'nodes-header [])))
    (is (= (ir/execute live 'status-header [])
           (oracle/call :report-core 'status-header [])))
    (is (= (ir/execute live 'spaces [3])
           (oracle/call :report-core 'spaces [3])))
    (is (= (ir/execute live 'pad-right ["asher" 5])
           (oracle/call :report-core 'pad-right ["asher" 5])))
    (is (= (ir/execute live 'pad-to ["asher" 10])
           (oracle/call :report-core 'pad-to ["asher" 10])))
    (is (= (ir/execute live 'command-help [])
           (oracle/call :report-core 'command-help [])))
    (is (= (ir/execute live 'reconcile-title ["f" "t"])
           (oracle/call :report-core 'reconcile-title ["f" "t"])))
    (is (= (ir/execute live 'reconcile-col-header [])
           (oracle/call :report-core 'reconcile-col-header [])))
    (is (= (ir/execute live 'nodes-row ["x" "?" 0 0 "off"])
           (oracle/call :report-core 'nodes-row ["x" "?" 0 0 "off"])))
    (is (= (ir/execute live 'status-row ["asher" 0 "?" "-" 0])
           (oracle/call :report-core 'status-row ["asher" 0 "?" "-" 0])))))

(deftest report-precompiled-kir-does-not-drift
  (is (= (report-live-kir) (report-resource-kir))
      "report KIR drift — run: clojure -M:test -m murakumo.kotoba-oracle-gen"))

(def ^:private plan-source "kotoba/infer_plan_core.kotoba")
(def ^:private plan-resource "murakumo/oracle/infer_plan_core.kir.edn")

(defn- plan-live-kir []
  (:kir (compiler/compile-source (slurp plan-source) :wasm32-kotoba-v1 {})))

(defn- plan-resource-kir []
  (edn/read-string (slurp (io/resource plan-resource))))

(deftest product-shell-infer-plan-uses-oracle-results
  (testing "constants"
    (is (= 1073741824 plan/GiB))
    (is (= 3758096384 plan/default-os-reserve))
    (is (= 1342177280 plan/default-headroom)))
  (testing "usable-bytes via oracle"
    (let [n {:name "a" :mem-bytes (* 16 plan/GiB)}]
      (is (pos? (plan/usable-bytes n)))
      (is (= (plan/usable-bytes n)
             (oracle/call :infer-plan 'usable-bytes
                          [(* 16 plan/GiB) plan/default-os-reserve plan/default-headroom -1])))))
  (testing "choose-strategy name via oracle"
    (is (= :pipeline (:strategy (plan/choose-strategy
                                 {:link-gbps 1 :ranks 4
                                  :model {:model/experts 128 :model/kv-heads 8}}))))
    (is (= :tensor (:strategy (plan/choose-strategy
                               {:link-gbps 40 :ranks 4
                                :model {:model/experts 128 :model/kv-heads 8}}))))
    (is (= :expert (:strategy (plan/choose-strategy
                               {:link-gbps 40 :ranks 5
                                :model {:model/experts 128 :model/kv-heads 8}}))))))

(deftest infer-plan-precompiled-kir-does-not-drift
  (is (= (plan-live-kir) (plan-resource-kir))
      "infer_plan KIR drift — run oracle-gen"))

(def ^:private dash-source "kotoba/dash_state_core.kotoba")
(def ^:private dash-resource "murakumo/oracle/dash_state_core.kir.edn")

(defn- dash-live-kir []
  (:kir (compiler/compile-source (slurp dash-source) :wasm32-kotoba-v1 {})))

(defn- dash-resource-kir []
  (edn/read-string (slurp (io/resource dash-resource))))

(deftest product-shell-dash-state-uses-oracle-results
  (testing "short-hosted-cid + health-class via oracle"
    (is (= "bafy12345678901234"
           (dash/short-hosted-cid "bafy12345678901234567890")))
    (is (= "bafyA" (dash/short-hosted-cid "bafyA")))
    (is (= "ok" (dash/health-class {:health "ok"})))
    (is (= "down" (dash/health-class {:health "pending"})))
    (is (= "down" (dash/health-class {:health nil}))))
  (testing "interval-sleep-ms + clamp-at via oracle"
    (is (= 15000 (dash/interval-sleep-ms 15)))
    (is (= 0 (dash/clamp-at nil 3)))
    (is (= 2 (dash/clamp-at 99 3)))
    (is (= 0 (dash/clamp-at 0 0))))
  (testing "append-capped uses take-last-start oracle index"
    (let [v (dash/append-capped (vec (range 5)) 6 :x)]
      (is (= 6 (count v)))
      (is (= :x (last v))))
    (let [v (dash/append-capped (vec (range 10)) 6 :y)]
      (is (= 6 (count v)))
      (is (= (take-last 6 (conj (vec (range 10)) :y)) v))))
  (testing "recent-alerts cap via recent-take-n"
    (is (= 3 (count (dash/recent-alerts (range 10) 3))))
    (is (= 6 (count (dash/recent-alerts (range 10) -1))))))

(deftest dash-oracle-call-matches-live-compile
  (let [live (dash-live-kir)]
    (is (= (ir/execute live 'short-hosted-cid ["bafy12345678901234567890"])
           (oracle/call :dash-state 'short-hosted-cid ["bafy12345678901234567890"])))
    (is (= (ir/execute live 'health-class-of ["ok"])
           (oracle/call :dash-state 'health-class-of ["ok"])))
    (is (= (ir/execute live 'interval-sleep-ms [15])
           (oracle/call :dash-state 'interval-sleep-ms [15])))
    (is (= (ir/execute live 'clamp-at [99 3])
           (oracle/call :dash-state 'clamp-at [99 3])))
    (is (= (ir/execute live 'take-last-start [10 6])
           (oracle/call :dash-state 'take-last-start [10 6])))
    (is (= (ir/execute live 'recent-take-n [-1 6])
           (oracle/call :dash-state 'recent-take-n [-1 6])))))

(deftest dash-precompiled-kir-does-not-drift
  (is (= (dash-live-kir) (dash-resource-kir))
      "dash_state KIR drift — run oracle-gen"))
