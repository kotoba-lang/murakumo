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
            [murakumo.infer.schedule :as sched]
            [murakumo.task.plan :as task]
            [murakumo.infer.engine :as eng]
            [murakumo.secret :as secret]
            [murakumo.overlay.crypto :as crypto]
            [murakumo.tunnel :as tunnel]
            [murakumo.fleet.inventory :as finv]
            [murakumo.config :as config]
            [murakumo.kotoba.oracle :as oracle]
            [murakumo.kotoba-oracle-gen :as gen]))

(def ^:private source-path "kotoba/kekkai_gate_core.kotoba")
(def ^:private resource-path "murakumo/oracle/kekkai_gate_core.kir.edn")

(defn- live-kir []
  (:kir (compiler/compile-source (slurp source-path) :wasm32-kotoba-v1 {})))

(defn- resource-kir []
  (edn/read-string (slurp (io/resource resource-path))))

(deftest oracle-catalog-ready
  (is (>= (oracle/catalog-count) 32)
      "full product-shell catalog ships all kotoba/*_core artifacts")
  (doseq [id (oracle/catalog-ids)]
    (is (oracle/ready? id) (str "not ready: " id)))
  (is (some #{:secret} (oracle/catalog-ids)))
  (is (some #{:overlay-crypto} (oracle/catalog-ids)))
  (is (some #{:tunnel} (oracle/catalog-ids)))
  (is (some #{:reconcile-plan} (oracle/catalog-ids)))
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
  (testing "default-rpc-port + pure cmd pieces"
    (is (= 50052 eng/default-rpc-port))
    (is (= (oracle/call :infer-engine 'default-rpc-port []) eng/default-rpc-port)))
  (testing "embed-head-cmd via oracle front/back"
    (let [cmd (eng/embed-head-cmd
               {:bin-dir "bin" :model-path "m.gguf" :port 8091
                :ctx 8192 :pooling "mean" :parallel 4})]
      (is (re-find #"bin/llama-server -m m.gguf --embedding --pooling mean" cmd))
      (is (re-find #"--port 8091" cmd))))
  (testing "mlx-moe-cmd via oracle"
    (let [cmd (eng/mlx-moe-cmd
               {:venv "/v" :model-repo "repo" :port 8080 :capacity 2})]
      (is (re-find #"/v/bin/mlx-moe serve repo" cmd))
      (is (re-find #"--capacity 2" cmd))))
  (testing "head-cmd composition for 2-worker plan"
    (let [plan {:assignments
                [{:node {:name "w0" :host "h0" :ip "1.1.1.1"} :span 10}
                 {:node {:name "w1" :host "h1" :ip "1.1.1.2"} :span 10}
                 {:node {:name "head" :host "localhost" :head? true} :span 5}]}
          cmd (eng/head-cmd plan
                            {:bin-dir "bin" :model-path "m.gguf"
                             :port 8080 :rpc-port 50052 :ctx 4096
                             :parallel 1 :strategy :pipeline})]
      (is (re-find #"bin/llama-server -m m.gguf" cmd))
      (is (re-find #"--rpc 1\.1\.1\.1:50052,1\.1\.1\.2:50052" cmd))
      (is (re-find #"--split-mode layer" cmd))
      (is (re-find #"--tensor-split 10,10,5" cmd)))))

(deftest engine-oracle-call-matches-live-compile
  (let [live (eng-live-kir)]
    (is (= (ir/execute live 'split-mode-name ["tensor"])
           (oracle/call :infer-engine 'split-mode-name ["tensor"])))
    (is (= (ir/execute live 'endpoint ["h" 50052])
           (oracle/call :infer-engine 'endpoint ["h" 50052])))
    (is (= (ir/execute live 'rpc-server-cmd ["bin" 50052 "MTL0" 1 ""])
           (oracle/call :infer-engine 'rpc-server-cmd ["bin" 50052 "MTL0" 1 ""])))
    (is (= (ir/execute live 'head-cmd-front ["bin" "m.gguf"])
           (oracle/call :infer-engine 'head-cmd-front ["bin" "m.gguf"])))))

(deftest engine-precompiled-kir-does-not-drift
  (is (= (eng-live-kir) (eng-resource-kir))
      "infer_engine KIR drift — run oracle-gen"))

(deftest product-shell-secret-uses-oracle-results
  (testing "named secret constants from oracle"
    (is (= "murakumo-token" secret/token-secret-name))
    (is (= "MURAKUMO_TOKEN_SECRET" secret/token-secret-env))
    (is (= "murakumo-service-token" secret/service-token-name))
    (is (= (oracle/call :secret 'token-secret-name []) secret/token-secret-name)))
  (testing "validators via oracle"
    (is (true? (secret/valid-env-var-name? "FOO_BAR")))
    (is (false? (secret/valid-env-var-name? "FOO*")))
    (is (true? (secret/valid-path-ref? "/tmp/cert.pem")))
    (is (false? (secret/valid-path-ref? "relative")))
    (is (false? (secret/valid-path-ref? "-----BEGIN CERT-----")))))

(deftest secret-oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/secret_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute live 'token-secret-name [])
           (oracle/call :secret 'token-secret-name [])))
    (is (= (ir/execute live 'valid-env-var-name? ["OK_ENV"])
           (oracle/call :secret 'valid-env-var-name? ["OK_ENV"])))
    (is (= (ir/execute live 'valid-path-ref-unix? ["/abs/path"])
           (oracle/call :secret 'valid-path-ref-unix? ["/abs/path"])))))

(deftest secret-precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/secret_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string (slurp (io/resource "murakumo/oracle/secret_core.kir.edn")))]
    (is (= live shipped) "secret_core KIR drift — run oracle-gen")))

(deftest product-shell-overlay-crypto-uses-oracle-results
  (is (= "aes-256-gcm" crypto/alg-name))
  (is (= "AES/GCM/NoPadding" crypto/cipher-transform))
  (is (= 12 crypto/nonce-bytes))
  (is (= 128 crypto/gcm-tag-bits))
  (is (= "abc" (crypto/strip-b64-pad "abc==")))
  (let [sealed (crypto/seal "k" "payload")]
    (is (crypto/sealed-map-ok? sealed))
    (is (= "payload" (crypto/open "k" sealed)))))

(deftest bulk-gen-discovers-all-cores
  (let [arts (gen/discover-artifacts)]
    (is (>= (count arts) 32))
    (is (every? #(re-find #"_core\.kotoba$" (get % "source")) arts))
    (is (every? #(re-find #"resources/murakumo/oracle/.*\.kir\.edn$" (get % "out")) arts))))

(deftest product-shell-tunnel-uses-oracle-results
  (testing "defaults + wrap-cmd via oracle"
    (is (= 8 tunnel/default-connect-timeout-s))
    (is (= 30 tunnel/default-control-persist-s))
    (is (= "__murakumo_rc=" tunnel/rc-marker))
    (is (= "( true\n); __mrc=$?; echo \"__murakumo_rc=$__mrc\""
           (tunnel/wrap-cmd "true"))))
  (testing "conn-opts + scp-dest + forwards"
    (is (= ["-o" "BatchMode=yes"
            "-o" "ConnectTimeout=8"
            "-o" "StrictHostKeyChecking=accept-new"]
           (tunnel/conn-opts {})))
    (let [o (tunnel/conn-opts {:control-path "/tmp/cm" :connect-timeout-s 5})]
      (is (some #{"ControlPath=/tmp/cm"} o))
      (is (some #{"ConnectTimeout=5"} o)))
    (is (= "h:/dest" (last (tunnel/scp-argv "h" "local" "/dest"))))
    (is (re-find #"pgrep -f '8080:localhost:80 host'"
                 (tunnel/ensure-forward-command 8080 80 "host")))
    (is (re-find #"curl -s -m 5 http://x 2>/dev/null"
                 (tunnel/remote-curl-command "http://x"))))
  (testing "parse-rc marker + digits via oracle"
    (let [[clean rc] (tunnel/parse-rc "hello\n__murakumo_rc=7\n")]
      (is (= "hello" clean))
      (is (= 7 rc)))
    (let [[clean rc] (tunnel/parse-rc "no marker\n")]
      (is (= "no marker" clean))
      (is (nil? rc)))))

(deftest product-shell-fleet-inventory-uses-oracle-results
  (let [fleet {:fleet/port 9000
               :nodes [{:name "a" :port 1} {:name "b"} {:name "c" :port 3}]}]
    (testing "resolve-port + health-url"
      (is (= 1 (finv/node-port fleet {:port 1})))
      (is (= 9000 (finv/node-port fleet {})))
      (is (= 8077 (finv/node-port {} {})))
      (is (= "http://localhost:1/health" (finv/node-health-url fleet {:port 1}))))
    (testing "select via selector predicates"
      (is (= 3 (count (finv/select fleet nil))))
      (is (= 3 (count (finv/select fleet "all"))))
      (is (= ["a" "c"] (mapv :name (finv/select fleet "a,c")))))
    (testing "offline line detection"
      (let [m (finv/parse-tailscale-status
               "100.1.1.1 a linux - \n100.2.2.2 b macos offline\n")]
        (is (true? (get-in m ["a" :online?])))
        (is (false? (get-in m ["b" :online?])))))))

(deftest product-shell-config-uses-oracle-results
  (testing "default paths"
    (is (= "fleet.edn" config/default-fleet-path))
    (is (= "connect.edn" config/default-connect-path))
    (is (= "cloud.edn" config/default-cloud-path)))
  (testing "kotoba-dir + pure path builders"
    (is (= "/h/github/com-junkawasaki/orgs/com-junkawasaki/kotoba"
           (config/default-kotoba-dir "/h")))
    (is (= "/override"
           (config/kotoba-dir {"MURAKUMO_KOTOBA_DIR" "/override" "HOME" "/h"})))
    (is (= "/h/github/com-junkawasaki/orgs/com-junkawasaki/kotoba"
           (config/kotoba-dir {"HOME" "/h"})))
    (is (= "/u/bin" (config/pinned-bin-dir "/u")))
    (is (= "/k/target/aarch64-apple-darwin/release" (config/release-bin-dir "/k")))
    (is (= "/u/bin" (config/resolve-local-bin {} "/u" "/k" true)))
    (is (= "/custom" (config/resolve-local-bin {"MURAKUMO_BIN" "/custom"} "/u" "/k" false)))
    (is (= "kotoba" (config/kotoba-bin "/u" false)))
    (is (= "/u/bin/kotoba" (config/kotoba-bin "/u" true)))
    (is (= "/b/kotoba-server" (config/kotoba-server-bin "/b")))
    (is (= ".murakumo-peers.edn" (config/peers-path "/u")))
    (is (= "deploy/com.murakumo.kotoba-mesh.plist.tmpl"
           (config/launchd-template-path "/u")))))

(deftest tunnel-fleet-config-oracle-call-matches-live
  (let [t-live (:kir (compiler/compile-source (slurp "kotoba/tunnel_core.kotoba")
                                              :wasm32-kotoba-v1 {}))
        f-live (:kir (compiler/compile-source (slurp "kotoba/fleet_inventory_core.kotoba")
                                              :wasm32-kotoba-v1 {}))
        c-live (:kir (compiler/compile-source (slurp "kotoba/config_core.kotoba")
                                              :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute t-live 'wrap-cmd ["x"])
           (oracle/call :tunnel 'wrap-cmd ["x"])))
    (is (= (ir/execute t-live 'parse-digits ["42"])
           (oracle/call :tunnel 'parse-digits ["42"])))
    (is (= (ir/execute f-live 'resolve-port [0 0 0 0])
           (oracle/call :fleet-inventory 'resolve-port [0 0 0 0])))
    (is (= (ir/execute f-live 'selector-is-all? [""])
           (oracle/call :fleet-inventory 'selector-is-all? [""])))
    (is (= (ir/execute c-live 'default-fleet-path [])
           (oracle/call :config 'default-fleet-path [])))
    (is (= (ir/execute c-live 'pinned-bin-dir ["/u"])
           (oracle/call :config 'pinned-bin-dir ["/u"])))))
