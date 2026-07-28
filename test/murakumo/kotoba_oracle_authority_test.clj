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
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.kekkai.gate :as gate]
            [murakumo.token :as tok]
            [murakumo.report :as report]
            [murakumo.infer.plan :as plan]
            [murakumo.dash.state :as dash]
            [murakumo.infer.schedule :as sched]
            [murakumo.task.plan :as task]
            [murakumo.infer.engine :as eng]
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
  (is (oracle/ready? :infer-schedule))
  (is (oracle/ready? :task-plan))
  (is (oracle/ready? :infer-engine))
  (is (some #{:kekkai-gate} (oracle/catalog-ids)))
  (is (some #{:token} (oracle/catalog-ids)))
  (is (some #{:report-core} (oracle/catalog-ids)))
  (is (some #{:infer-plan} (oracle/catalog-ids)))
  (is (some #{:dash-state} (oracle/catalog-ids)))
  (is (some #{:infer-schedule} (oracle/catalog-ids)))
  (is (some #{:task-plan} (oracle/catalog-ids)))
  (is (some #{:infer-engine} (oracle/catalog-ids))))

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

(def ^:private sched-source "kotoba/infer_schedule_core.kotoba")
(def ^:private sched-resource "murakumo/oracle/infer_schedule_core.kir.edn")

(defn- sched-live-kir []
  (:kir (compiler/compile-source (slurp sched-source) :wasm32-kotoba-v1 {})))

(defn- sched-resource-kir []
  (edn/read-string (slurp (io/resource sched-resource))))

(deftest product-shell-infer-schedule-uses-oracle-results
  (let [model {:model/engine :comfyui
               :model/checkpoint "c.safetensors"
               :model/min-free-bytes (* 8 1024 1024 1024)}
        warm {:name "a" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
              :free-bytes (* 16 1024 1024 1024) :queue 0}
        cold {:name "b" :engines #{:comfyui} :checkpoints #{}
              :free-bytes (* 16 1024 1024 1024) :queue 0}]
    (testing "eligible? + score via oracle"
      (is (true? (sched/eligible? warm model)))
      (is (true? (sched/eligible? cold model)))
      (is (= [0 (- (* 16 1024 1024 1024))] (sched/score warm))))
    (testing "pick prefers warm"
      (is (= "a" (:name (sched/pick [cold warm] model)))))
    (testing "assign updates queue via queue-inc-if"
      (let [asg (sched/assign [warm cold] [{:model model} {:model model}])]
        (is (= "a" (:node (asg 0))))
        (is (= "a" (:node (asg 1))))))))

(deftest schedule-oracle-call-matches-live-compile
  (let [live (sched-live-kir)]
    (is (= (ir/execute live 'eligible? [15 (* 16 1024 1024 1024) 0])
           (oracle/call :infer-schedule 'eligible? [15 (* 16 1024 1024 1024) 0])))
    (is (= (ir/execute live 'score-queue [3])
           (oracle/call :infer-schedule 'score-queue [3])))
    (is (= (ir/execute live 'queue-inc-if [2 1])
           (oracle/call :infer-schedule 'queue-inc-if [2 1])))))

(deftest schedule-precompiled-kir-does-not-drift
  (is (= (sched-live-kir) (sched-resource-kir))
      "infer_schedule KIR drift — run oracle-gen"))

(def ^:private task-source "kotoba/task_plan_core.kotoba")
(def ^:private task-resource "murakumo/oracle/task_plan_core.kir.edn")

(defn- task-live-kir []
  (:kir (compiler/compile-source (slurp task-source) :wasm32-kotoba-v1 {})))

(defn- task-resource-kir []
  (edn/read-string (slurp (io/resource task-resource))))

(deftest product-shell-task-plan-uses-oracle-results
  (testing "defaults via oracle"
    (is (= 8 (:max-slots task/default-opts)))
    (is (= 2 (:max-attempts task/default-opts)))
    (is (= 120000 (:timeout-ms task/default-opts))))
  (testing "slots via oracle"
    (is (= 8 (task/slots {:cores 8} {})))
    (is (= 3 (task/slots {:name "a" :cores 8} {:slots-by-node {"a" 3}})))
    (is (= 4 (task/slots {:slots 4 :cores 16} {}))))
  (testing "failed? via oracle"
    (is (true? (task/failed? {:exit 1})))
    (is (true? (task/failed? {:exit nil :timeout? true})))
    (is (true? (task/failed? {:error "x"})))
    (is (false? (task/failed? {:exit 0}))))
  (testing "eligible? via oracle flags"
    (let [ok {:name "a" :online? true :labels {:tier "gpu"} :roles #{:worker}
              :mem-bytes (* 16 1024 1024 1024)}
          offline (assoc ok :online? false)
          task-spec {:placement {:labels {:tier "gpu"} :roles [:worker]}
                     :min-mem-bytes (* 8 1024 1024 1024)}]
      (is (true? (task/eligible? ok task-spec)))
      (is (false? (task/eligible? offline task-spec)))))
  (testing "expand task-id via oracle"
    (let [ts (task/expand 2 {:cmd ["echo"]})]
      (is (= "t-0000" (:id (first ts))))
      (is (= "t-0001" (:id (second ts))))
      (is (= 1 (:attempt (first ts))))))
  (testing "retry-tasks can-retry + attempt-next"
    (let [rs [{:task {:id "t-0000" :attempt 1} :node "a" :exit 1}
              {:task {:id "t-0001" :attempt 2} :node "b" :exit 1}]
          out (task/retry-tasks rs {})]
      (is (= 1 (count out)))
      (is (= 2 (:attempt (first out))))
      (is (= ["a"] (:exclude-nodes (first out))))))
  (testing "assign wave/slot + summary retried/speedup"
    (let [nodes [{:name "a" :host "a.ts" :cores 2 :online? true :roles #{:worker}
                  :mem-bytes (* 16 1024 1024 1024)}]
          tasks (task/expand 3 {:placement {:roles [:worker]}})
          asg (task/assign nodes tasks {})
          results (mapv (fn [a] {:task (:task a) :node (:node a) :exit 0
                                 :duration-ms 100})
                        (:assignments asg))
          sum (task/summary results 150)]
      (is (= 3 (count (:assignments asg))))
      (is (= 0 (:wave (first (:assignments asg)))))
      (is (= 3 (:tasks sum)))
      (is (= 0 (:retried sum)))
      (is (some? (:speedup sum))))))

(deftest task-oracle-call-matches-live-compile
  (let [live (task-live-kir)]
    (is (= (ir/execute live 'default-max-slots [])
           (oracle/call :task-plan 'default-max-slots [])))
    (is (= (ir/execute live 'slots [-1 4 -1 8 16])
           (oracle/call :task-plan 'slots [-1 4 -1 8 16])))
    (is (= (ir/execute live 'failed? [1 0 0 0])
           (oracle/call :task-plan 'failed? [1 0 0 0])))
    (is (= (ir/execute live 'task-eligible? [31 (* 16 1024 1024 1024) 0])
           (oracle/call :task-plan 'task-eligible? [31 (* 16 1024 1024 1024) 0])))
    (is (= (ir/execute live 'task-id [12])
           (oracle/call :task-plan 'task-id [12])))
    (is (= (ir/execute live 'wave-of [5 2])
           (oracle/call :task-plan 'wave-of [5 2])))
    (is (= (ir/execute live 'nearest-rank-idx [10 500])
           (oracle/call :task-plan 'nearest-rank-idx [10 500])))
    (is (= (ir/execute live 'speedup-milli [300 150])
           (oracle/call :task-plan 'speedup-milli [300 150])))))

(deftest task-precompiled-kir-does-not-drift
  (is (= (task-live-kir) (task-resource-kir))
      "task_plan KIR drift — run oracle-gen"))

(def ^:private eng-source "kotoba/infer_engine_core.kotoba")
(def ^:private eng-resource "murakumo/oracle/infer_engine_core.kir.edn")

(defn- eng-live-kir []
  (:kir (compiler/compile-source (slurp eng-source) :wasm32-kotoba-v1 {})))

(defn- eng-resource-kir []
  (edn/read-string (slurp (io/resource eng-resource))))

(deftest product-shell-infer-engine-uses-oracle-results
  (testing "default-rpc-port via oracle"
    (is (= 50052 eng/default-rpc-port)))
  (testing "rpc-worker-cmds :cmd via rpc-server-cmd"
    (let [plan {:assignments
                [{:span 1 :node {:name "w" :host "w.ts" :ip "10.0.0.1" :head? false}}
                 {:span 0 :node {:name "h" :host "h.ts" :head? true}}]}
          cmds (eng/rpc-worker-cmds plan {:bin-dir "/opt/llama" :port 50052 :device "MTL0"})]
      (is (= 1 (count cmds)))
      (is (= "/opt/llama/rpc-server -H 0.0.0.0 -p 50052 -d MTL0 -c"
             (:cmd (first cmds)))))
    (let [plan {:assignments
                [{:span 1 :node {:name "w" :host "w" :rpc-cache? false :head? false}}
                 {:span 0 :node {:name "h" :host "h" :head? true}}]}
          cmds (eng/rpc-worker-cmds plan {:bin-dir "/b" :port 9 :device "CUDA0"})]
      (is (= "/b/rpc-server -H 0.0.0.0 -p 9 -d CUDA0"
             (:cmd (first cmds))))))
  (testing "tensor-split + rpc-endpoints + head-cmd"
    (let [plan {:assignments
                [{:span 10 :node {:name "w0" :host "w0" :ip "10.0.0.1" :head? false}}
                 {:span 10 :node {:name "w1" :host "w1" :ip "10.0.0.2" :head? false}}
                 {:span 5 :node {:name "head" :host "h" :ip "10.0.0.3" :head? true}}]}
          opts {:bin-dir "/opt/llama" :model-path "/m.gguf" :port 8080
                :rpc-port 50052 :ctx 4096 :parallel 1 :strategy :pipeline}]
      (is (= "10,10,5" (eng/tensor-split plan)))
      (is (str/includes? (eng/head-cmd plan opts) "--split-mode layer"))
      (is (str/includes? (eng/head-cmd plan opts) "--tensor-split 10,10,5"))
      (is (str/includes? (eng/head-cmd plan (assoc opts :strategy :tensor))
                         "--split-mode row"))
      (is (str/starts-with? (eng/head-cmd plan opts)
                            "/opt/llama/llama-server -m /m.gguf"))))
  (testing "embed-head-cmd front+back"
    (is (= "/opt/llama/llama-server -m /m.gguf --embedding --pooling mean -ngl 999 -c 8192 --parallel 4 --host 0.0.0.0 --port 8091"
           (eng/embed-head-cmd {:bin-dir "/opt/llama" :model-path "/m.gguf"}))))
  (testing "mlx-moe-cmd front + opt flags"
    (is (= "/opt/mlx/bin/mlx-moe serve org/model --host 0.0.0.0 --port 8080"
           (eng/mlx-moe-cmd {:venv "/opt/mlx" :model-repo "org/model" :port 8080})))
    (is (= "mlx-moe serve m --host 0.0.0.0 --port 9000 --capacity 8"
           (eng/mlx-moe-cmd {:model-repo "m" :port 9000 :capacity 8}))))
  (testing "mlx-launch-cmd front + host prompt"
    (let [cmd (eng/mlx-launch-cmd
               {:assignments [{:span 1 :node {:name "n" :host "h" :head? true}}]}
               {:venv "/opt/mlx" :hosts-file "/tmp/hosts.json"
                :model-repo "org/m" :max-tokens 64 :prompt "hello"})]
      (is (str/starts-with? cmd "/opt/mlx/bin/mlx.launch --hosts /tmp/hosts.json"))
      (is (str/includes? cmd "--max-tokens 64"))
      (is (str/includes? cmd "--prompt \"hello\"")))))

(deftest engine-oracle-call-matches-live-compile
  (let [live (eng-live-kir)]
    (is (= (ir/execute live 'default-rpc-port [])
           (oracle/call :infer-engine 'default-rpc-port [])))
    (is (= (ir/execute live 'split-mode-name ["tensor"])
           (oracle/call :infer-engine 'split-mode-name ["tensor"])))
    (is (= (ir/execute live 'endpoint ["10.0.0.1" 50052])
           (oracle/call :infer-engine 'endpoint ["10.0.0.1" 50052])))
    (is (= (ir/execute live 'rpc-server-cmd ["/opt/llama" 50052 "MTL0" 1 ""])
           (oracle/call :infer-engine 'rpc-server-cmd ["/opt/llama" 50052 "MTL0" 1 ""])))
    (is (= (ir/execute live 'tensor-split-3 [10 10 5])
           (oracle/call :infer-engine 'tensor-split-3 [10 10 5])))
    (is (= (ir/execute live 'head-cmd-front ["/opt/llama" "/m.gguf"])
           (oracle/call :infer-engine 'head-cmd-front ["/opt/llama" "/m.gguf"])))
    (is (= (ir/execute live 'embed-head-front ["/b" "/m" "mean" 8192])
           (oracle/call :infer-engine 'embed-head-front ["/b" "/m" "mean" 8192])))
    (is (= (ir/execute live 'mlx-moe-front ["" "m" 9000])
           (oracle/call :infer-engine 'mlx-moe-front ["" "m" 9000])))))

(deftest engine-precompiled-kir-does-not-drift
  (is (= (eng-live-kir) (eng-resource-kir))
      "infer_engine KIR drift — run oracle-gen"))
