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
            [clojure.walk :as walk]
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
            [murakumo.config :as config]
            [murakumo.reconcile.plan :as rplan]
            [murakumo.fleet.inventory :as finv]
            [murakumo.identity :as id]
            [murakumo.infer.credits :as credits]
            [murakumo.infer.join :as join]
            [murakumo.infer.gc :as gc]
            [murakumo.infer.moe :as moe]
            [murakumo.infer.rebalance :as reb]
            [murakumo.infer.relay :as relay]
            [murakumo.deploy.plan :as dplan]
            [murakumo.connect :as conn]
            [murakumo.component-authority :as cauth]
            [murakumo.overlay.keyring :as okr]
            [murakumo.overlay.peer :as opeer]
            [murakumo.overlay.stream :as ostream]
            [murakumo.overlay.driver :as odriver]
            [murakumo.overlay.runtime :as ort]
            [murakumo.cloud.plan :as cplan]
            [murakumo.provision.plan :as pplan]
            [murakumo.persist :as persist]
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
           (gate/denial-line {:name "judah" :kekkai/status "pending"})))
    (is (= (str gate/denial-prefix "judah" gate/denial-mid "pending" gate/denial-suffix)
           (gate/denial-line {:name "judah" :kekkai/status "pending"}))))
  (testing "status/denial/cli tokens dual-sourced"
    (is (= "authorized" gate/status-authorized))
    (is (= "unknown" gate/status-unknown))
    (is (= "[kekkai] " gate/denial-prefix))
    (is (= "clojure" gate/cli-bin))
    (is (= "kekkai.cli" gate/cli-main-ns)))
  (testing "default-ledger-path + default-kekkai-dir from oracle"
    (is (= "kekkai-tailnet.edn" gate/default-ledger-path))
    (is (= (str "/home/jun" gate/kekkai-dir-suffix)
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
      ;; Profile 5: authorized? returns :bool (true/false or 0/1 word).
      (let [live-v (ir/execute live 'authorized? [st])
            ship-v (oracle/call :kekkai-gate 'authorized? [st])]
        (is (= live-v ship-v) (str "authorized? " st))
        (is (= (= st "authorized")
               (oracle/bool->host ship-v))
            (str "authorized? host bool " st))))
    (is (= (ir/execute live 'default-ledger-path [])
           (oracle/call :kekkai-gate 'default-ledger-path [])))
    (let [den (oracle/record [:record :kekkai/denial
                              [[:name :string] [:status :string]]]
                             {:name "x" :status "revoked"})]
      (is (= (ir/execute live 'denial-line-of [den])
             (oracle/call :kekkai-gate 'denial-line-of [den])))
      (is (= (oracle/call :kekkai-gate 'denial-line-of [den])
             (gate/denial-line {:name "x" :kekkai/status "revoked"}))))
    (is (= (ir/execute live 'default-kekkai-dir-under ["/h"])
           (oracle/call :kekkai-gate 'default-kekkai-dir-under ["/h"])))
    (is (= (ir/execute live 'status-authorized [])
           (oracle/call :kekkai-gate 'status-authorized [])))
    (is (= (oracle/call :kekkai-gate 'status-authorized []) gate/status-authorized))
    (is (= (ir/execute live 'denial-prefix [])
           (oracle/call :kekkai-gate 'denial-prefix [])))
    (is (= (oracle/call :kekkai-gate 'denial-suffix []) gate/denial-suffix))
    (is (= (ir/execute live 'cli-bin [])
           (oracle/call :kekkai-gate 'cli-bin [])))
    (is (= (oracle/call :kekkai-gate 'cli-bin []) gate/cli-bin))))

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



(deftest t62-catalog-artifacts-match-kotoba-sources
  "Every discovered core has a shipped KIR path (gen discover == resources)."
  (let [arts (gen/discover-artifacts)
        sources (set (map #(get % "source") arts))]
    (is (pos? (count arts)) "discover-artifacts empty")
    (is (= (count arts) (count sources)) "duplicate sources")
    (doseq [{:strs [source out]} arts]
      (is (.exists (io/file source)) (str "missing source " source))
      (is (.exists (io/file out)) (str "missing shipped KIR " out
                                       " — run: clojure -M:test -m murakumo.kotoba-oracle-gen"))
      (is (str/starts-with? out "resources/murakumo/oracle/"))
      (is (str/ends-with? out ".kir.edn")))))

(deftest t62-catalog-oracle-ids-cover-all-shipped-kir
  "Every catalog id is ready; every discovered KIR is on the classpath."
  (let [arts (gen/discover-artifacts)]
    (is (>= (oracle/catalog-count) 32)
        (str "expected full product-shell catalog, got " (oracle/catalog-count)))
    (is (= (oracle/catalog-count) (count arts))
        "catalog-count must match discover-artifacts (1:1 product shells)")
    (doseq [id (oracle/catalog-ids)]
      (is (oracle/ready? id) (str "catalog id not ready: " id)))
    (doseq [{:strs [out]} arts]
      (let [cp (str/replace out #"^resources/" "")]
        (is (some? (io/resource cp))
            (str "classpath missing " cp))))))

(deftest t62-all-product-shell-kir-do-not-drift
  "Catalog-wide drift gate (T6.2). Live compile each core; compare to shipped
   after gensym normalization. Fix: clojure -M:test -m murakumo.kotoba-oracle-gen"
  (doseq [{:strs [source out]} (gen/discover-artifacts)]
    (testing source
      ;; Compared raw. Every synthesized name the compiler emits is now
      ;; deterministic (compiler#453 named the and/or/comparison temps by chain
      ;; position, #454 put the other 30 on a per-compilation counter), so the
      ;; same source yields the same KIR byte for byte. The normalization this
      ;; replaces existed only to hide `or-tmp__N` / `binding-some__N` counter
      ;; values, and hid real structural drift in those positions along with
      ;; them.
      (let [live (gen/compile-kir source)
            cp (str/replace out #"^resources/" "")
            shipped (edn/read-string (slurp (io/resource cp)))]
        (is (= live shipped)
            (str "KIR drift: " source " ≠ " cp
                 " — run: clojure -M:test -m murakumo.kotoba-oracle-gen"))))))

(deftest t62-prod-deps-exclude-compiler
  "T6.2: compiler must not be on the production classpath (only :test alias)."
  (let [edn (edn/read-string (slurp "deps.edn"))
        prod-deps (:deps edn)
        test-extra (get-in edn [:aliases :test :extra-deps])]
    (is (not (contains? prod-deps 'io.github.kotoba-lang/compiler))
        "compiler leaked into :deps (prod)")
    (is (contains? test-extra 'io.github.kotoba-lang/compiler)
        "compiler missing from :test extra-deps (oracle-gen/parity need it)")
    (is (contains? prod-deps 'io.github.kotoba-lang/kotoba-kir)
        "kotoba-kir (KIR runner) must remain a prod dep")))


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
    (is (= "mk1.PAY.SIG" (tok/wire-token "PAY" "SIG")))
    (is (= "mk1" tok/version))
    (is (= 2592000 tok/default-ttl))
    (is (= "anonymous" tok/default-sub))
    (is (= "all" tok/default-scope))
    (is (= "all" tok/scope-all))
    (is (= "." tok/jwt-seg-sep))
    (is (= "." tok/wire-sep))
    (is (= "{\"sub\":\"" tok/json-sub-prefix))
    (is (= (str tok/version tok/jwt-seg-sep "PAY") (tok/signing-input "PAY")))
    (is (= (str tok/version tok/wire-sep "PAY" tok/wire-sep "SIG")
           (tok/wire-token "PAY" "SIG"))))
  (testing "constant-time= / version / scope via oracle"
    (is (true? (tok/constant-time= "abc" "abc")))
    (is (false? (tok/constant-time= "abc" "abd")))
    (is (true? (tok/version-ok? "mk1")))
    (is (true? (tok/version-ok? tok/version)))
    (is (false? (tok/version-ok? "mk0")))
    (is (true? (tok/scope-allows? "all" "chat")))
    (is (true? (tok/scope-allows? tok/scope-all "chat")))
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
  (let [live (token-live-kir)
        claims (oracle/record
                [:record :token/claims
                 [[:sub :string] [:scope :string] [:iat :i64] [:exp :i64]]]
                {:sub "shinshi" :scope "chat" :iat 1 :exp 2})
        wire (oracle/record
              [:record :token/wire [[:payload :string] [:sig :string]]]
              {:payload "P" :sig "S"})
        eq (oracle/record
            [:record :token/eq [[:a :string] [:b :string]]]
            {:a "ab" :b "ab"})
        scope (oracle/record
               [:record :token/scope-check
                [[:token-scope :string] [:required :string]]]
               {:token-scope "all" :required "x"})]
    ;; T5.2 native guest record wire: single-arg record exports
    (is (= (ir/execute live 'encode-claims-json [claims])
           (oracle/call :token 'encode-claims-json [claims])))
    (is (= (ir/execute live 'signing-input ["P"])
           (oracle/call :token 'signing-input ["P"])))
    (is (= (ir/execute live 'wire-token [wire])
           (oracle/call :token 'wire-token [wire])))
    (is (= (ir/execute live 'constant-time-eq [eq])
           (oracle/call :token 'constant-time-eq [eq])))
    (is (= (ir/execute live 'scope-allows? [scope])
           (oracle/call :token 'scope-allows? [scope])))
    (is (= (ir/execute live 'version [])
           (oracle/call :token 'version [])))
    (is (= (oracle/call :token 'version []) tok/version))
    (is (= (ir/execute live 'default-sub [])
           (oracle/call :token 'default-sub [])))
    (is (= (oracle/call :token 'default-sub []) tok/default-sub))
    (is (= (ir/execute live 'jwt-seg-sep [])
           (oracle/call :token 'jwt-seg-sep [])))
    (is (= (oracle/call :token 'jwt-seg-sep []) tok/jwt-seg-sep))
    (is (= (ir/execute live 'scope-all [])
           (oracle/call :token 'scope-all [])))
    (is (= (oracle/call :token 'scope-all []) tok/scope-all))
    (is (= (ir/execute live 'json-sub-prefix [])
           (oracle/call :token 'json-sub-prefix [])))
    (is (= (oracle/call :token 'json-sub-prefix []) tok/json-sub-prefix))))

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
      (is (re-find #"on n1" (nth lines 2)))))
  (testing "CSV join seps + fold steps + cid max dual-source"
    (is (= "," report/report-csv-sep))
    (is (= ", " report/report-csv-spaced-sep))
    (is (= "/" report/mesh-status-sep))
    (is (= 16 report/cid-display-max-len))
    (is (= "a" (report/join-append "" "," "a")))
    (is (= "a,b" (report/join-append "a" "," "b")))
    (is (= "t1,t2" (report/csv-append "t1" "t2")))
    (is (= "a, b" (report/csv-spaced-append "a" "b")))
    (is (= "t1,t2" (report/csv-join ["t1" "t2"])))
    (is (= "a, b" (report/csv-spaced-join ["a" "b"])))
    (is (str/includes?
         (report/deploy-observed-row ["a" "b"] {:name "pub"})
         "a, b"))))

(deftest report-oracle-call-matches-live-compile
  (let [live (report-live-kir)
        pad (oracle/record
             [:record :report/pad [[:s :string] [:pad :i64]]]
             {:s "asher" :pad 5})
        pad-to (oracle/record
                [:record :report/pad-to [[:s :string] [:width :i64]]]
                {:s "asher" :width 10})
        title (oracle/record
               [:record :report/title [[:fleet :string] [:ts :string]]]
               {:fleet "f" :ts "t"})
        nrow (oracle/record
              [:record :report/nodes-row
               [[:name :string] [:ip :string] [:online :bool]
                [:ssh-ok :bool] [:mesh :string]]]
              {:name "x" :ip "?" :online false :ssh-ok false :mesh "off"})
        ja (oracle/record
            [:record :report/join
             [[:acc :string] [:sep :string] [:next :string]]]
            {:acc "" :sep "," :next "a"})
        ca (oracle/record
            [:record :report/csv [[:acc :string] [:next :string]]]
            {:acc "t1" :next "t2"})
        sa (oracle/record
            [:record :report/csv [[:acc :string] [:next :string]]]
            {:acc "a" :next "b"})]
    (is (= (ir/execute live 'nodes-header [])
           (oracle/call :report-core 'nodes-header [])))
    (is (= (ir/execute live 'status-header [])
           (oracle/call :report-core 'status-header [])))
    (is (= (ir/execute live 'spaces [3])
           (oracle/call :report-core 'spaces [3])))
    (is (= (ir/execute live 'pad-right [pad])
           (oracle/call :report-core 'pad-right [pad])))
    (is (= (ir/execute live 'pad-to [pad-to])
           (oracle/call :report-core 'pad-to [pad-to])))
    (is (= (ir/execute live 'command-help [])
           (oracle/call :report-core 'command-help [])))
    (is (= (ir/execute live 'reconcile-title [title])
           (oracle/call :report-core 'reconcile-title [title])))
    (is (= (ir/execute live 'reconcile-col-header [])
           (oracle/call :report-core 'reconcile-col-header [])))
    (is (= (ir/execute live 'nodes-row [nrow])
           (oracle/call :report-core 'nodes-row [nrow])))
    (let [srow (oracle/record
                [:record :report/status-row
                 [[:name :string] [:health [:option :i64]]
                  [:wasm :string] [:links :string] [:p2p-port :i64]]]
                {:name "asher" :health nil :wasm "?" :links "-" :p2p-port 0})]
      (is (= (ir/execute live 'status-row [srow])
             (oracle/call :report-core 'status-row [srow]))))
    (is (= (ir/execute live 'report-csv-sep [])
           (oracle/call :report-core 'report-csv-sep [])))
    (is (= (ir/execute live 'report-csv-spaced-sep [])
           (oracle/call :report-core 'report-csv-spaced-sep [])))
    (is (= (ir/execute live 'mesh-status-sep [])
           (oracle/call :report-core 'mesh-status-sep [])))
    (is (= (ir/execute live 'cid-display-max-len [])
           (oracle/call :report-core 'cid-display-max-len [])))
    (is (= (oracle/call :report-core 'report-csv-sep []) report/report-csv-sep))
    (is (= (oracle/call :report-core 'cid-display-max-len [])
           report/cid-display-max-len))
    (is (= (ir/execute live 'join-append [ja])
           (oracle/call :report-core 'join-append [ja])))
    (is (= (ir/execute live 'csv-append [ca])
           (oracle/call :report-core 'csv-append [ca])))
    (is (= (ir/execute live 'csv-spaced-append [sa])
           (oracle/call :report-core 'csv-spaced-append [sa])))
    (is (= (oracle/call :report-core 'csv-append [ca])
           (report/csv-append "t1" "t2")))
    (is (= (oracle/call :report-core 'csv-spaced-append [sa])
           (report/csv-spaced-append "a" "b")))
    (is (= "t1,t2" (report/csv-join ["t1" "t2"])))
    (is (= "a, b" (report/csv-spaced-join ["a" "b"])))))

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
      (let [cap (oracle/record
                 [:record :plan/node-cap
                  [[:mem :i64] [:os :i64] [:head :i64] [:wired [:option :i64]]]]
                 {:mem (* 16 plan/GiB)
                  :os plan/default-os-reserve
                  :head plan/default-headroom
                  :wired nil})]
        (is (= (plan/usable-bytes n)
               (oracle/call :infer-plan 'usable-bytes [cap]))))))
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
    (is (= 6 (count (dash/recent-alerts (range 10) -1)))))
  (testing "parse-links + probe lines + response constants via oracle"
    (is (= 2 (dash/parse-links "2")))
    (is (= 0 (dash/parse-links "not-int")))
    (is (= {"H" "{\"a\":1}" "L" "3"}
           (dash/probe-lines "H:{\"a\":1}\nL:3\n")))
    (is (= 200 (:status (dash/json-response "{}"))))
    (is (= "application/json"
           (get-in (dash/json-response "{}") [:headers "content-type"])))
    (is (= "text/html; charset=utf-8"
           (get-in (dash/html-response "<x/>") [:headers "content-type"])))
    (is (= "ok" (dash/health-from-present true)))
    (is (= "down" (dash/health-from-present false)))
    (is (= "http://localhost:8077/health" (dash/health-url 8077)))
    (is (= "~/.murakumo/mesh.log" (dash/mesh-log-path)))
    (is (str/includes? (dash/probe-command 8077) "http://localhost:8077/health"))
    (is (str/includes? (dash/probe-command 8077) "peer connected"))
    (is (str/starts-with? (dash/probe-command 18099) "echo \"H:$(curl -s -m4 ")))
    (is (= " " dash/hosted-join-sep))
    (is (= 18 dash/short-hosted-cid-max-len))
    (is (= 14 dash/short-cid-max-len))
    (is (= 8899 dash/default-dashboard-port))
    (is (= 15 dash/default-dashboard-interval))
    (is (= "8899" dash/default-dashboard-port-str))
    (is (= "15" dash/default-dashboard-interval-str))
    (is (= "a" (dash/join-append "" " " "a")))
    (is (= "a b" (dash/join-append "a" " " "b")))
    (is (= "bafyA" (dash/hosted-append "" "bafyA")))
    (is (= "bafyA bafyB" (dash/hosted-append "bafyA" "bafyB")))
    (is (= {:port 8899 :interval 15} (dash/dashboard-options [])))
    (is (= "bafyA bafyB" (dash/hosted-summary {:hosted ["bafyA" "bafyB"]})))
    (is (nil? (dash/hosted-summary {:hosted []})))
    (is (= "com.murakumo.fleet.snapshot" dash/snapshot-record-type))
    (is (= "com.murakumo.fleet.snapshot"
           (:$type (dash/snapshot-record {:ts 1 :fleet "f" :nodes []} "{}")))))

(deftest dash-oracle-call-matches-live-compile
  (let [live (dash-live-kir)]
    (is (= (ir/execute live 'short-hosted-cid ["bafy12345678901234567890"])
           (oracle/call :dash-state 'short-hosted-cid ["bafy12345678901234567890"])))
    (is (= (ir/execute live 'health-class-of ["ok"])
           (oracle/call :dash-state 'health-class-of ["ok"])))
    (is (= (ir/execute live 'interval-sleep-ms [15])
           (oracle/call :dash-state 'interval-sleep-ms [15])))
    (let [cl (oracle/record [:record :dash/clamp
                             [[:requested-at :i64] [:history-count :i64]]]
                            {:requested-at 99 :history-count 3})
          tl (oracle/record [:record :dash/pair-i64 [[:a :i64] [:b :i64]]]
                            {:a 10 :b 6})
          rt (oracle/record [:record :dash/pair-i64 [[:a :i64] [:b :i64]]]
                            {:a -1 :b 6})]
      (is (= (ir/execute live 'clamp-at [cl])
             (oracle/call :dash-state 'clamp-at [cl])))
      (is (= (ir/execute live 'take-last-start [tl])
             (oracle/call :dash-state 'take-last-start [tl])))
      (is (= (ir/execute live 'recent-take-n [rt])
             (oracle/call :dash-state 'recent-take-n [rt]))))
    (is (= (ir/execute live 'parse-links ["2"])
           (oracle/call :dash-state 'parse-links ["2"])))
    (is (= (ir/execute live 'probe-line-key ["L:9"])
           (oracle/call :dash-state 'probe-line-key ["L:9"])))
    (is (= (ir/execute live 'content-type-html [])
           (oracle/call :dash-state 'content-type-html [])))
    (is (= (ir/execute live 'health-from-present [true])
           (oracle/call :dash-state 'health-from-present [true])))
    (is (= (ir/execute live 'health-url [8077])
           (oracle/call :dash-state 'health-url [8077])))
    (is (= (ir/execute live 'mesh-log-path [])
           (oracle/call :dash-state 'mesh-log-path [])))
    (is (= (ir/execute live 'probe-command [8077])
           (oracle/call :dash-state 'probe-command [8077])))
    (is (= (oracle/call :dash-state 'probe-command [8077])
           (dash/probe-command 8077)))
    (is (= (oracle/call :dash-state 'health-url [18099])
           (dash/health-url 18099)))
    (is (= (ir/execute live 'hosted-join-sep [])
           (oracle/call :dash-state 'hosted-join-sep [])))
    (is (= (ir/execute live 'default-dashboard-port [])
           (oracle/call :dash-state 'default-dashboard-port [])))
    (is (= (ir/execute live 'default-dashboard-port-str [])
           (oracle/call :dash-state 'default-dashboard-port-str [])))
    (is (= (oracle/call :dash-state 'hosted-join-sep []) dash/hosted-join-sep))
    (is (= (oracle/call :dash-state 'default-dashboard-port [])
           dash/default-dashboard-port))
    (is (= (oracle/call :dash-state 'short-hosted-cid-max-len [])
           dash/short-hosted-cid-max-len))
    (let [ja (oracle/record [:record :dash/join
                             [[:acc :string] [:sep :string] [:next :string]]]
                            {:acc "" :sep " " :next "a"})
          ha (oracle/record [:record :dash/hosted
                             [[:acc :string] [:next :string]]]
                            {:acc "bafyA" :next "bafyB"})]
      (is (= (ir/execute live 'join-append [ja])
             (oracle/call :dash-state 'join-append [ja])))
      (is (= (ir/execute live 'hosted-append [ha])
             (oracle/call :dash-state 'hosted-append [ha])))
      (is (= (oracle/call :dash-state 'hosted-append [ha])
             (dash/hosted-append "bafyA" "bafyB")))
      (is (= "bafyA bafyB" (dash/hosted-append "bafyA" "bafyB"))))
    (is (= (ir/execute live 'snapshot-record-type [])
           (oracle/call :dash-state 'snapshot-record-type [])))
    (is (= (oracle/call :dash-state 'snapshot-record-type [])
           dash/snapshot-record-type))))
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
  (let [live (sched-live-kir)
        elig (oracle/record [:record :schedule/eligibility
                             [[:has-engine :bool] [:has-checkpoint :bool]
                              [:holds-checkpoint :bool] [:can-fetch :bool]
                              [:free-bytes :i64] [:min-free :i64]]]
                            {:has-engine true :has-checkpoint true
                             :holds-checkpoint true :can-fetch true
                             :free-bytes (* 16 1024 1024 1024) :min-free 0})]
    ;; T5.2 native guest record: single-arg eligible? (free/min on record).
    ;; eligible? result is a :bool word (KIR 0/1 or host true/false).
    (is (= (ir/execute live 'eligible? [elig])
           (oracle/call :infer-schedule 'eligible? [elig])))
    (is (contains? #{true 1}
                   (oracle/call :infer-schedule 'eligible? [elig])))
    (is (= (ir/execute live 'score-queue [3])
           (oracle/call :infer-schedule 'score-queue [3])))
    (let [qs (oracle/record [:record :schedule/queue-step
                             [[:queue :i64] [:picked :i64]]]
                            {:queue 2 :picked 1})]
      (is (= (ir/execute live 'queue-inc-if [qs])
             (oracle/call :infer-schedule 'queue-inc-if [qs]))))))

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
      (is (some? (:speedup sum)))))
  (testing "unschedulable seps dual-source"
    (is (= "," task/exclude-join-sep))
    (is (= "no node satisfies placement=" task/unsched-placement-prefix))
    (is (= " excluding=" task/unsched-excluding-prefix))
    (is (= " min-mem-bytes=" task/unsched-min-mem-prefix))))

(deftest task-oracle-call-matches-live-compile
  (let [live (task-live-kir)]
    (is (= (ir/execute live 'default-max-slots [])
           (oracle/call :task-plan 'default-max-slots [])))
    (let [slots-in (oracle/record
                    [:record :task/slots
                     [[:budget :i64] [:node-slots :i64] [:slots-per :i64]
                      [:max-slots :i64] [:cores :i64]]]
                    {:budget -1 :node-slots 4 :slots-per -1 :max-slots 8 :cores 16})]
      (is (= (ir/execute live 'slots [slots-in])
             (oracle/call :task-plan 'slots [slots-in]))))
    (let [ok (oracle/record
              [:record :task/failed
               [[:exit [:option :i64]] [:timeout :bool] [:error [:option :string]]]]
              {:exit 0 :timeout false :error nil})
          bad-exit (oracle/record
                    [:record :task/failed
                     [[:exit [:option :i64]] [:timeout :bool] [:error [:option :string]]]]
                    {:exit nil :timeout false :error nil})
          bad-err (oracle/record
                   [:record :task/failed
                    [[:exit [:option :i64]] [:timeout :bool] [:error [:option :string]]]]
                   {:exit 0 :timeout false :error "x"})]
      (is (= (ir/execute live 'failed? [ok])
             (oracle/call :task-plan 'failed? [ok])))
      (is (contains? #{false 0}
                     (oracle/call :task-plan 'failed? [ok])))
      (is (contains? #{true 1}
                     (oracle/call :task-plan 'failed? [bad-exit])))
      (is (contains? #{true 1}
                     (oracle/call :task-plan 'failed? [bad-err]))))
    ;; T5.2 native guest record: single-arg task-eligible? (mem on record).
    (let [elig (oracle/record [:record :task/eligibility
                               [[:online :bool] [:labels-ok :bool] [:roles-ok :bool]
                                [:not-excluded :bool] [:allowlist-ok :bool]
                                [:mem-bytes :i64] [:min-mem :i64]]]
                              {:online true :labels-ok true :roles-ok true
                               :not-excluded true :allowlist-ok true
                               :mem-bytes (* 16 1024 1024 1024) :min-mem 0})
          retry (oracle/record [:record :task/retry
                                [[:attempt :i64] [:max-attempts :i64]]]
                               {:attempt 1 :max-attempts 3})]
      (is (= (ir/execute live 'task-eligible? [elig])
             (oracle/call :task-plan 'task-eligible? [elig])))
      (is (contains? #{true 1}
                     (oracle/call :task-plan 'task-eligible? [elig])))
      (is (= (ir/execute live 'can-retry? [retry])
             (oracle/call :task-plan 'can-retry? [retry])))
      (is (contains? #{true 1}
                     (oracle/call :task-plan 'can-retry? [retry]))))
    (is (= (ir/execute live 'task-id [12])
           (oracle/call :task-plan 'task-id [12])))
    (let [ud (oracle/record
              [:record :task/unsched
               [[:placement :string] [:excluding :string] [:min-mem-str :string]]]
              {:placement "nil" :excluding "a,b" :min-mem-str "64"})]
      (is (= (ir/execute live 'unschedulable-detail [ud])
             (oracle/call :task-plan 'unschedulable-detail [ud]))))
    (is (= (ir/execute live 'exclude-join-sep [])
           (oracle/call :task-plan 'exclude-join-sep [])))
    (is (= (ir/execute live 'unsched-placement-prefix [])
           (oracle/call :task-plan 'unsched-placement-prefix [])))
    (is (= (ir/execute live 'unsched-excluding-prefix [])
           (oracle/call :task-plan 'unsched-excluding-prefix [])))
    (is (= (ir/execute live 'unsched-min-mem-prefix [])
           (oracle/call :task-plan 'unsched-min-mem-prefix [])))
    (is (= (oracle/call :task-plan 'exclude-join-sep []) task/exclude-join-sep))
    (is (= (oracle/call :task-plan 'unsched-placement-prefix [])
           task/unsched-placement-prefix))
    (let [wave (oracle/record [:record :task/wave [[:used :i64] [:slots :i64]]]
                              {:used 5 :slots 2})
          pair-nr (oracle/record [:record :task/pair [[:a :i64] [:b :i64]]]
                                 {:a 10 :b 500})
          pair-sp (oracle/record [:record :task/pair [[:a :i64] [:b :i64]]]
                                 {:a 300 :b 150})
          fold0 (oracle/record
                 [:record :task/pick-fold
                  [[:champ [:option :i64]] [:ok-i :bool] [:better-c-i :bool]]]
                 {:champ nil :ok-i true :better-c-i false})
          fold1 (oracle/record
                 [:record :task/pick-fold
                  [[:champ [:option :i64]] [:ok-i :bool] [:better-c-i :bool]]]
                 {:champ 1 :ok-i true :better-c-i false})
          fold2 (oracle/record
                 [:record :task/pick-fold
                  [[:champ [:option :i64]] [:ok-i :bool] [:better-c-i :bool]]]
                 {:champ 1 :ok-i true :better-c-i true})]
      (is (= (ir/execute live 'wave-of [wave])
             (oracle/call :task-plan 'wave-of [wave])))
      (is (= (ir/execute live 'nearest-rank-idx [pair-nr])
             (oracle/call :task-plan 'nearest-rank-idx [pair-nr])))
      (is (= (ir/execute live 'speedup-milli [pair-sp])
             (oracle/call :task-plan 'speedup-milli [pair-sp])))
      (is (= (ir/execute live 'pick-task-fold-step [fold0])
             (oracle/call :task-plan 'pick-task-fold-step [fold0])))
      (is (= 1 (oracle/call :task-plan 'pick-task-fold-step [fold0])))
      (is (= 2 (oracle/call :task-plan 'pick-task-fold-step [fold1])))
      (is (= 1 (oracle/call :task-plan 'pick-task-fold-step [fold2]))))))

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
    (let [ep (oracle/record
              [:record :engine/endpoint [[:host :string] [:port :i64]]]
              {:host "h" :port 50052})
          rpc (oracle/record
               [:record :engine/rpc-server
                [[:bin-dir :string] [:port :i64] [:device :string]
                 [:cache :bool] [:cache-dir :string]]]
               {:bin-dir "bin" :port 50052 :device "MTL0" :cache true :cache-dir ""})]
      (is (= (ir/execute live 'endpoint [ep])
             (oracle/call :infer-engine 'endpoint [ep])))
      (is (= (ir/execute live 'rpc-server-cmd [rpc])
             (oracle/call :infer-engine 'rpc-server-cmd [rpc]))))
    (let [hf (oracle/record
              [:record :engine/head-front
               [[:bin-dir :string] [:model-path :string]]]
              {:bin-dir "bin" :model-path "m.gguf"})]
      (is (= (ir/execute live 'head-cmd-front [hf])
             (oracle/call :infer-engine 'head-cmd-front [hf]))))))

(deftest engine-precompiled-kir-does-not-drift
  (is (= (eng-live-kir) (eng-resource-kir))
      "infer_engine KIR drift — run oracle-gen"))

(deftest product-shell-secret-uses-oracle-results
  (testing "named secret constants from oracle"
    (is (= "murakumo-token" secret/token-secret-name))
    (is (= "MURAKUMO_TOKEN_SECRET" secret/token-secret-env))
    (is (= "murakumo-service-token" secret/service-token-name))
    (is (= (oracle/call :secret 'token-secret-name []) secret/token-secret-name)))
  (testing "reply class / error tokens dual-sourced"
    (is (= "value" secret/class-value))
    (is (= "not-found" secret/class-not-found))
    (is (= "empty" secret/class-empty))
    (is (= "fetch" secret/class-fetch))
    (is (= "unknown" secret/class-unknown))
    (is (= "secret/" secret/error-code-prefix))
    (is (= "empty" secret/msg-empty))
    (is (= "not found" secret/msg-not-found))
    (is (= "getter failed" secret/msg-fetch))
    (is (= "-----BEGIN" secret/pem-begin-marker))
    (is (= (str secret/error-code-prefix secret/class-empty)
           (oracle/call :secret 'secret-error-code ["empty"]))))
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
    (is (= (ir/execute live 'class-value [])
           (oracle/call :secret 'class-value [])))
    (is (= (oracle/call :secret 'class-value []) secret/class-value))
    (is (= (ir/execute live 'class-not-found [])
           (oracle/call :secret 'class-not-found [])))
    (is (= (oracle/call :secret 'pem-begin-marker []) secret/pem-begin-marker))
    (is (= (ir/execute live 'error-code-prefix [])
           (oracle/call :secret 'error-code-prefix [])))
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

(deftest product-shell-tunnel-uses-oracle-results
  (testing "constants from oracle"
    (is (= 8 tunnel/default-connect-timeout-s))
    (is (= 30 tunnel/default-control-persist-s))
    (is (= "__murakumo_rc=" tunnel/rc-marker))
    (is (= (oracle/call :tunnel 'rc-marker []) tunnel/rc-marker)))
  (testing "conn-opts pure fragments via oracle"
    (let [o (tunnel/conn-opts nil)]
      (is (some #{"BatchMode=yes"} o))
      (is (some #{"ConnectTimeout=8"} o))
      (is (some #{"StrictHostKeyChecking=accept-new"} o)))
    (let [o (tunnel/conn-opts {:control-path "/tmp/m/%C" :control-persist-s 45})]
      (is (some #{"ControlMaster=auto"} o))
      (is (some #{"ControlPath=/tmp/m/%C"} o))
      (is (some #{"ControlPersist=45s"} o))))
  (testing "wrap-cmd + parse-rc digits via oracle"
    (is (str/includes? (tunnel/wrap-cmd "exit 7") "__murakumo_rc=$__mrc"))
    (is (= ["hello" 0] (tunnel/parse-rc "hello\n__murakumo_rc=0")))
    (is (= ["" 7] (tunnel/parse-rc "__murakumo_rc=7")))
    (is (= ["partial" nil] (tunnel/parse-rc "partial"))))
  (testing "command shapes via oracle"
    (is (= "asher:.murakumo/bin/kotoba"
           (last (tunnel/scp-argv "asher" "bin/kotoba" ".murakumo/bin/kotoba"))))
    (is (= ["ssh" "-o" "ControlPath=/tmp/m/%C" "-O" "exit" "asher"]
           (tunnel/close-master-argv "asher" "/tmp/m/%C")))
    (is (= (oracle/call :tunnel 'ensure-forward-command
                        [(oracle/record [:record :tunnel/forward
                                         [[:local-port :i64] [:remote-port :i64] [:host :string]]]
                                        {:local-port 18099 :remote-port 8077 :host "asher"})])
           (tunnel/ensure-forward-command 18099 8077 "asher")))
    (is (= (oracle/call :tunnel 'remote-curl-command ["http://localhost:8077/health"])
           (tunnel/remote-curl-command "http://localhost:8077/health")))
    (is (= "ssh" tunnel/ssh-bin))
    (is (= "scp" tunnel/scp-bin))
    (is (= "-o" tunnel/o-flag))
    (is (= "-O" tunnel/O-flag))
    (is (= "exit" tunnel/exit-ctl))
    (is (= tunnel/ssh-bin (first (tunnel/ssh-argv "asher" "true" {:wrap? false}))))
    (is (= tunnel/scp-bin (first (tunnel/scp-argv "asher" "a" "b"))))
    (is (= tunnel/exit-ctl (nth (tunnel/close-master-argv "h" "/c") 4)))
    (is (= "pgrep" tunnel/pgrep-bin))
    (is (= "pkill" tunnel/pkill-bin))
    (is (= "-fN" tunnel/fN-flag))
    (is (= "-L" tunnel/L-flag))
    (is (str/starts-with?
         (tunnel/ensure-forward-command 18099 8077 "asher")
         "pgrep -f '18099:localhost:8077 asher'"))
    (is (str/includes?
         (tunnel/ensure-forward-command 18099 8077 "asher")
         "|| ssh -o BatchMode=yes -fN -L 18099:localhost:8077 asher"))
    (is (str/starts-with?
         (tunnel/replace-forward-command 1 2 "h")
         "pkill -f '1:localhost'"))
    (is (= "curl -s -m 5 http://x 2>/dev/null"
           (tunnel/remote-curl-command "http://x")))))

(deftest tunnel-oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/tunnel_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute live 'default-connect-timeout-s [])
           (oracle/call :tunnel 'default-connect-timeout-s [])))
    (is (= (ir/execute live 'wrap-cmd ["exit 7"])
           (oracle/call :tunnel 'wrap-cmd ["exit 7"])))
    (is (= (ir/execute live 'parse-digits ["42"])
           (oracle/call :tunnel 'parse-digits ["42"])))
    (is (= (ir/execute live 'connect-timeout-opt [8])
           (oracle/call :tunnel 'connect-timeout-opt [8])))
    (is (= (ir/execute live 'ssh-bin [])
           (oracle/call :tunnel 'ssh-bin [])))
    (is (= (ir/execute live 'scp-bin [])
           (oracle/call :tunnel 'scp-bin [])))
    (is (= (ir/execute live 'o-flag [])
           (oracle/call :tunnel 'o-flag [])))
    (is (= (ir/execute live 'O-flag [])
           (oracle/call :tunnel 'O-flag [])))
    (is (= (ir/execute live 'exit-ctl [])
           (oracle/call :tunnel 'exit-ctl [])))
    (is (= (oracle/call :tunnel 'ssh-bin []) tunnel/ssh-bin))
    (let [scp (oracle/record [:record :tunnel/scp [[:host :string] [:dest :string]]]
                             {:host "h" :dest "d"})
          fwd1 (oracle/record [:record :tunnel/forward
                               [[:local-port :i64] [:remote-port :i64] [:host :string]]]
                              {:local-port 1 :remote-port 2 :host "h"})
          ports (oracle/record [:record :tunnel/ports
                                [[:local-port :i64] [:remote-port :i64]]]
                               {:local-port 18099 :remote-port 8077})
          fwd-a (oracle/record [:record :tunnel/forward
                                [[:local-port :i64] [:remote-port :i64] [:host :string]]]
                               {:local-port 18099 :remote-port 8077 :host "asher"})]
      (is (= (ir/execute live 'scp-dest [scp])
             (oracle/call :tunnel 'scp-dest [scp])))
      (is (= (ir/execute live 'ensure-forward-command [fwd1])
             (oracle/call :tunnel 'ensure-forward-command [fwd1])))
      (is (= (ir/execute live 'forward-spec [ports])
             (oracle/call :tunnel 'forward-spec [ports])))
      (is (= (ir/execute live 'ssh-forward-prefix [])
             (oracle/call :tunnel 'ssh-forward-prefix [])))
      (is (= (ir/execute live 'pgrep-bin [])
             (oracle/call :tunnel 'pgrep-bin [])))
      (is (= (ir/execute live 'curl-prefix [])
             (oracle/call :tunnel 'curl-prefix [])))
      (is (= (oracle/call :tunnel 'ensure-forward-command [fwd-a])
             (tunnel/ensure-forward-command 18099 8077 "asher")))
      (is (= (oracle/call :tunnel 'replace-forward-command [fwd1])
             (tunnel/replace-forward-command 1 2 "h"))))))

(deftest tunnel-precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/tunnel_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string (slurp (io/resource "murakumo/oracle/tunnel_core.kir.edn")))]
    (is (= live shipped) "tunnel_core KIR drift — run oracle-gen")))

(deftest product-shell-config-uses-oracle-results
  (testing "default path constants from oracle"
    (is (= "fleet.edn" config/default-fleet-path))
    (is (= "connect.edn" config/default-connect-path))
    (is (= "cloud.edn" config/default-cloud-path))
    (is (= (oracle/call :config 'default-fleet-path []) config/default-fleet-path)))
  (testing "path builders via oracle"
    (is (= (str "/home/ops" config/kotoba-dir-suffix)
           (config/default-kotoba-dir "/home/ops")))
    (is (= "/custom/kotoba"
           (config/kotoba-dir {"MURAKUMO_KOTOBA_DIR" "/custom/kotoba" "HOME" "/h"})))
    (is (= (config/default-kotoba-dir "/h")
           (config/kotoba-dir {"HOME" "/h"})))
    (is (= (str "/work" config/bin-suffix) (config/pinned-bin-dir "/work")))
    (is (= (str "/k" config/release-bin-suffix) (config/release-bin-dir "/k")))
    (is (= (str "/work" config/bin-suffix config/kotoba-cli-suffix)
           (config/kotoba-bin "/work" true)))
    (is (= config/default-kotoba-cli-bin (config/kotoba-bin "/work" false)))
    (is (= (str "/work" config/bin-suffix)
           (config/resolve-local-bin {} "/work" "/k" true)))
    (is (= "/custom" (config/resolve-local-bin {"MURAKUMO_BIN" "/custom"} "/work" "/k" false)))
    (is (= (str "/k" config/release-bin-suffix)
           (config/resolve-local-bin {} "/work" "/k" false)))
    (is (= ".murakumo-peers.edn" (config/peers-path "/x")))
    (is (= "deploy/com.murakumo.kotoba-mesh.plist.tmpl"
           (config/launchd-template-path "/x"))))
  (testing "path suffix tokens dual-sourced"
    (is (= "/bin" config/bin-suffix))
    (is (= "/wit" config/wit-suffix))
    (is (= "/kotoba-server" config/kotoba-server-suffix))
    (is (= "/kotoba" config/kotoba-cli-suffix))
    (is (= "/BUILD.edn" config/build-edn-suffix))
    (is (= "/target/aarch64-apple-darwin/release" config/release-bin-suffix))
    (is (= "/crates/kotoba-runtime/wit" config/runtime-wit-suffix))
    (is (str/includes? config/kotoba-dir-suffix "com-junkawasaki/kotoba"))))

(deftest config-oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/config_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute live 'default-fleet-path [])
           (oracle/call :config 'default-fleet-path [])))
    (is (= (ir/execute live 'bin-suffix [])
           (oracle/call :config 'bin-suffix [])))
    (is (= (oracle/call :config 'bin-suffix []) config/bin-suffix))
    (is (= (ir/execute live 'kotoba-dir-suffix [])
           (oracle/call :config 'kotoba-dir-suffix [])))
    (is (= (oracle/call :config 'kotoba-cli-suffix []) config/kotoba-cli-suffix))
    (is (= (ir/execute live 'release-bin-suffix [])
           (oracle/call :config 'release-bin-suffix [])))
    (is (= (ir/execute live 'default-kotoba-dir ["/h"])
           (oracle/call :config 'default-kotoba-dir ["/h"])))
    (let [kd (oracle/record
              [:record :config/kotoba-dir [[:override :string] [:home :string]]]
              {:override "" :home "/h"})
          local (oracle/record
                 [:record :config/local-bin
                  [[:user-dir :string] [:kotoba-dir :string]
                   [:pinned-exists :bool] [:murakumo-bin :string]]]
                 {:user-dir "/u" :kotoba-dir "/k"
                  :pinned-exists false :murakumo-bin "/b"})
          bin (oracle/record
               [:record :config/kotoba-bin
                [[:user-dir :string] [:pinned-exists :bool]]]
               {:user-dir "/u" :pinned-exists true})]
      (is (= (ir/execute live 'kotoba-dir-from [kd])
             (oracle/call :config 'kotoba-dir-from [kd])))
      (is (= (ir/execute live 'resolve-local-bin [local])
             (oracle/call :config 'resolve-local-bin [local])))
      (is (= (ir/execute live 'kotoba-bin [bin])
             (oracle/call :config 'kotoba-bin [bin]))))
    (is (= (ir/execute live 'default-cloud-url [])
           (oracle/call :config 'default-cloud-url [])))
    (is (= (ir/execute live 'default-api-url [])
           (oracle/call :config 'default-api-url [])))
    (is (= (ir/execute live 'default-text-backend-url [])
           (oracle/call :config 'default-text-backend-url [])))
    (is (= (ir/execute live 'default-image-checkpoint [])
           (oracle/call :config 'default-image-checkpoint [])))
    (is (= (ir/execute live 'default-infer-local-url [])
           (oracle/call :config 'default-infer-local-url [])))
    (is (= (ir/execute live 'default-kotoba-cli-bin [])
           (oracle/call :config 'default-kotoba-cli-bin [])))
    (is (= (oracle/call :config 'default-cloud-url [])
           config/default-cloud-url))
    (is (= (oracle/call :config 'default-kotoba-cli-bin [])
           config/default-kotoba-cli-bin))))

(deftest config-precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/config_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string (slurp (io/resource "murakumo/oracle/config_core.kir.edn")))]
    (is (= live shipped) "config_core KIR drift — run oracle-gen")))

(deftest product-shell-reconcile-plan-uses-oracle-results
  (testing "watch-sleep-ms via oracle"
    (is (= 15000 (rplan/watch-sleep-ms 15)))
    (is (= (oracle/call :reconcile-plan 'watch-sleep-ms [30])
           (rplan/watch-sleep-ms 30))))
  (testing "reconcile-app actions via action-name oracle"
    (let [fleet {:fleet/name "t"
                 :nodes [{:name "a" :roles ["compute"] :labels {:z "jp"}}
                         {:name "b" :roles ["compute"] :labels {:z "jp"}}
                         {:name "c" :roles ["compute"] :labels {:z "jp"}}]}
          snap {:nodes [{:name "a" :hosted ["cid1"]}
                        {:name "b" :hosted []}
                        {:name "c" :hosted []}]}
          place (rplan/reconcile-app fleet snap nil
                                     {:name "app" :cid "cid1" :replicas 3
                                      :placement {:labels {:z "jp"} :roles ["compute"]}})
          sat (rplan/reconcile-app fleet snap nil
                                   {:name "app" :cid "cid1" :replicas 1
                                    :placement {:labels {:z "jp"} :roles ["compute"]}})
          needs (rplan/reconcile-app fleet snap nil
                                     {:name "app" :replicas 1 :placement {}})]
      (is (= :place (:action place)))
      (is (= 2 (:deficit place)))
      (is (seq (:targets place)))
      (is (= :satisfied (:action sat)))
      (is (= :needs-build (:action needs)))
      (is (= 1 (:desired needs)))
      (is (true? (rplan/plan-converged?
                 {:apps [sat]})))
      (is (false? (rplan/plan-converged?
                   {:apps [place sat]})))
      (is (= [place] (rplan/apply-apps {:apps [place sat needs]})))))
  (testing "CLI flags + missing-manifest via oracle"
    (is (= :missing-manifest (rplan/reconcile-command-error {})))
    (is (nil? (rplan/reconcile-command-error {:manifest "murakumo.app.edn"})))
    (is (= {:manifest "m.edn" :dry-run true :watch 5}
           (rplan/parse-flags ["m.edn" "--dry-run" "--watch=5"])))
    (is (= {:apply true :watch 30 :manifest "m.edn"}
           (rplan/parse-flags ["--apply" "--watch" "m.edn"])))
    (is (= "--dry-run" rplan/flag-dry-run))
    (is (= "--apply" rplan/flag-apply))
    (is (= "--watch" rplan/flag-watch))
    (is (= "--watch=" rplan/flag-watch-eq-prefix))
    (is (= "--snapshot=" rplan/flag-snapshot-prefix))
    (is (= "--" rplan/flag-dash-prefix))
    (is (= "satisfied" rplan/action-satisfied))
    (is (= "place" rplan/action-place))
    (is (= "over" rplan/action-over))
    (is (= "blocked" rplan/action-blocked))
    (is (= "needs-build" rplan/action-needs-build))
    (is (= "com.murakumo.fleet.reconcile" rplan/reconcile-record-type))
    (is (= "com.murakumo.fleet.reconcile"
           (:$type (rplan/reconcile-record {:ts 1 :fleet "f" :apps []} "{}"))))))

(deftest reconcile-oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/reconcile_plan_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        none-i64 (oracle/option-i64 nil)
        some-3 (oracle/option-i64 3)
        none-s (oracle/option-string nil)
        some-cid (oracle/option-string "bafy")]
    (is (= (ir/execute live 'default-replicas [])
           (oracle/call :reconcile-plan 'default-replicas [])))
    (is (= (ir/execute live 'desired [none-i64])
           (oracle/call :reconcile-plan 'desired [none-i64])))
    (is (= 1 (oracle/call :reconcile-plan 'desired [none-i64])))
    (is (= (ir/execute live 'desired [some-3])
           (oracle/call :reconcile-plan 'desired [some-3])))
    (is (= 3 (oracle/call :reconcile-plan 'desired [some-3])))
    (let [def-in (oracle/record
                  [:record :reconcile/deficit [[:desired :i64] [:running :i64]]]
                  {:desired 3 :running 1})]
      (is (= (ir/execute live 'deficit [def-in])
             (oracle/call :reconcile-plan 'deficit [def-in]))))
    (is (= (ir/execute live 'watch-sleep-ms [15])
           (oracle/call :reconcile-plan 'watch-sleep-ms [15])))
    (let [place (oracle/record
                 [:record :reconcile/action-in
                  [[:cid [:option :string]] [:running :i64]
                   [:desired-n :i64] [:free-candidates :i64]]]
                 {:cid "bafy" :running 1 :desired-n 3 :free-candidates 2})
          needs (oracle/record
                 [:record :reconcile/action-in
                  [[:cid [:option :string]] [:running :i64]
                   [:desired-n :i64] [:free-candidates :i64]]]
                 {:cid nil :running 0 :desired-n 1 :free-candidates 0})]
      (is (= (ir/execute live 'action-name [place])
             (oracle/call :reconcile-plan 'action-name [place])))
      (is (= "place" (oracle/call :reconcile-plan 'action-name [place])))
      (is (= (ir/execute live 'action-name [needs])
             (oracle/call :reconcile-plan 'action-name [needs])))
      (is (= "needs-build" (oracle/call :reconcile-plan 'action-name [needs]))))
    (is (= (ir/execute live 'missing-manifest? [""])
           (oracle/call :reconcile-plan 'missing-manifest? [""])))
    (is (= (ir/execute live 'action-is-satisfied? ["satisfied"])
           (oracle/call :reconcile-plan 'action-is-satisfied? ["satisfied"])))
    (is (= (ir/execute live 'watch-seconds ["--watch=7"])
           (oracle/call :reconcile-plan 'watch-seconds ["--watch=7"])))
    (is (= (ir/execute live 'snapshot-value ["--snapshot=x.edn"])
           (oracle/call :reconcile-plan 'snapshot-value ["--snapshot=x.edn"])))
    (is (= (ir/execute live 'flag-dry-run [])
           (oracle/call :reconcile-plan 'flag-dry-run [])))
    (is (= (oracle/call :reconcile-plan 'flag-dry-run []) rplan/flag-dry-run))
    (is (= "com.murakumo.fleet.reconcile" rplan/reconcile-record-type))
    (is (= "com.murakumo.fleet.reconcile"
           (:$type (rplan/reconcile-record {:ts 1 :fleet "f" :apps []} "{}"))))
    (is (= (oracle/call :reconcile-plan 'reconcile-record-type [])
           rplan/reconcile-record-type))
    (is (= (ir/execute live 'reconcile-record-type [])
           (oracle/call :reconcile-plan 'reconcile-record-type [])))
    (is (= (ir/execute live 'flag-watch-eq-prefix [])
           (oracle/call :reconcile-plan 'flag-watch-eq-prefix [])))
    (is (= (oracle/call :reconcile-plan 'flag-watch-eq-prefix [])
           rplan/flag-watch-eq-prefix))
    (is (= (ir/execute live 'action-satisfied [])
           (oracle/call :reconcile-plan 'action-satisfied [])))
    (is (= (oracle/call :reconcile-plan 'action-satisfied [])
           rplan/action-satisfied))
    (is (= (ir/execute live 'action-needs-build [])
           (oracle/call :reconcile-plan 'action-needs-build [])))
    (is (= "needs-build" (oracle/call :reconcile-plan 'action-needs-build [])))))

(deftest reconcile-precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/reconcile_plan_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string
                 (slurp (io/resource "murakumo/oracle/reconcile_plan_core.kir.edn")))]
    (is (= live shipped) "reconcile_plan_core KIR drift — run oracle-gen")))

(deftest bulk-gen-discovers-all-cores
  (let [arts (gen/discover-artifacts)]
    (is (>= (count arts) 32))
    (is (every? #(re-find #"_core\.kotoba$" (get % "source")) arts))
    (is (every? #(re-find #"resources/murakumo/oracle/.*\.kir\.edn$" (get % "out")) arts))))

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
        (is (false? (get-in m ["b" :online?])))))
    (testing "residual tokens dual-sourced"
      (is (= 8077 finv/default-control-port))
      (is (= "all" finv/selector-all))
      (is (= "offline" finv/offline-token))
      (is (= "http://localhost:" finv/health-url-prefix))
      (is (= "/health" finv/health-url-path))
      (is (= "," finv/selector-join-sep))
      (is (= (str finv/health-url-prefix 1 finv/health-url-path)
             (finv/node-health-url fleet {:port 1}))))))

(deftest fleet-inventory-oracle-call-matches-live
  (let [live (:kir (compiler/compile-source (slurp "kotoba/fleet_inventory_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (let [ports0 (oracle/record
                  [:record :fleet/ports
                   [[:node-port [:option :i64]] [:fleet-port [:option :i64]]]]
                  {:node-port nil :fleet-port nil})
          ports1 (oracle/record
                  [:record :fleet/ports
                   [[:node-port [:option :i64]] [:fleet-port [:option :i64]]]]
                  {:node-port 9010 :fleet-port 9000})]
      (is (= (ir/execute live 'resolve-port [ports0])
             (oracle/call :fleet-inventory 'resolve-port [ports0])))
      (is (= (ir/execute live 'resolve-port [ports1])
             (oracle/call :fleet-inventory 'resolve-port [ports1]))))
    (is (= (ir/execute live 'health-url [8077])
           (oracle/call :fleet-inventory 'health-url [8077])))
    (is (= (ir/execute live 'selector-is-all? [""])
           (oracle/call :fleet-inventory 'selector-is-all? [""])))
    (let [sw (oracle/record [:record :fleet/selector-name
                             [[:sel :string] [:name :string]]]
                            {:sel "a,c" :name "a"})]
      (is (= (ir/execute live 'selector-wants-name? [sw])
             (oracle/call :fleet-inventory 'selector-wants-name? [sw]))))
    (is (= (ir/execute live 'line-has-offline? ["x offline y"])
           (oracle/call :fleet-inventory 'line-has-offline? ["x offline y"])))
    (is (= (ir/execute live 'selector-all [])
           (oracle/call :fleet-inventory 'selector-all [])))
    (is (= (oracle/call :fleet-inventory 'selector-all []) finv/selector-all))
    (is (= (ir/execute live 'offline-token [])
           (oracle/call :fleet-inventory 'offline-token [])))
    (is (= (oracle/call :fleet-inventory 'offline-token []) finv/offline-token))
    (is (= (ir/execute live 'health-url-prefix [])
           (oracle/call :fleet-inventory 'health-url-prefix [])))
    (is (= (oracle/call :fleet-inventory 'health-url-path []) finv/health-url-path))
    (is (= (ir/execute live 'selector-join-sep [])
           (oracle/call :fleet-inventory 'selector-join-sep [])))
    (is (= (oracle/call :fleet-inventory 'default-control-port [])
           finv/default-control-port))))

(deftest product-shell-identity-uses-oracle-results
  (testing "seed preimages via oracle then host sha"
    (let [node (oracle/record [:record :identity/node-seed
                               [[:operator-seed :string] [:node-name :string]]]
                              {:operator-seed "op" :node-name "asher"})
          ov (oracle/record [:record :identity/overlay-seed
                             [[:operator-seed :string] [:overlay-id :string]]]
                            {:operator-seed "op" :overlay-id "ov1"})]
      (is (= (id/sha256-hex (oracle/call :identity 'seed-node [node]))
             (id/node-seed "op" {:name "asher"})))
      (is (= (id/sha256-hex (oracle/call :identity 'seed-p2p [node]))
             (id/node-p2p-seed "op" {:name "asher"})))
      (is (= (id/sha256-hex (oracle/call :identity 'seed-x25519 ["op"]))
             (id/x25519-seed "op")))
      (is (= (id/sha256-hex (oracle/call :identity 'seed-overlay [ov]))
             (id/overlay-auth-key "op" "ov1")))))
  (testing "did-from-output + argv via oracle"
    (is (= "did:key:z" (id/did-from-output " did:key:z\n")))
    (is (= ["/bin/kotoba" "did-derive" "seedhex"]
           (id/did-derive-argv "/bin/kotoba" "seedhex"))))
  (testing "op-token templates via oracle"
    (let [did "did:key:z-test"
          tok (id/op-token did)
          parts (str/split tok #"\.")]
      (is (= 3 (count parts)))
      (is (= (id/b64url id/jwt-header-json) (first parts)))
      (is (= (id/b64url (oracle/call :identity 'jwt-payload-json [did])) (second parts)))
      (is (= id/op-token-sig-seg (last parts)))
      (is (= "9999999999" id/jwt-payload-exp-val))
      (is (= id/jwt-payload-close "}"))))
  (testing "graph-cid b-prefix + fleet name"
    (is (= "b" id/cid-b-prefix))
    (is (str/starts-with? (id/graph-cid (id/graph-name-fleet)) id/cid-b-prefix))
    (is (= "murakumo-fleet" (id/graph-name-fleet))))
  (testing "seed/jwt seps dual-source"
    (is (= ":" id/seed-sep))
    (is (= ":p2p" id/seed-p2p-suffix))
    (is (= ":x25519" id/seed-x25519-suffix))
    (is (= ":murakumo-overlay-auth" id/seed-overlay-suffix))
    (is (= "did-derive" id/did-derive-subcmd))
    (is (= "." id/jwt-seg-sep))
    (is (= " " id/argv-join-sep))))

(deftest identity-oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/identity_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (let [node (oracle/record [:record :identity/node-seed
                               [[:operator-seed :string] [:node-name :string]]]
                              {:operator-seed "a" :node-name "b"})]
      (is (= (ir/execute live 'seed-node [node])
             (oracle/call :identity 'seed-node [node]))))
    (is (= (ir/execute live 'did-from-output [" x\n"])
           (oracle/call :identity 'did-from-output [" x\n"])))
    (is (= (ir/execute live 'jwt-header-json [])
           (oracle/call :identity 'jwt-header-json [])))
    (is (= (oracle/call :identity 'jwt-header-json []) id/jwt-header-json))
    (is (= (ir/execute live 'jwt-payload-sub-prefix [])
           (oracle/call :identity 'jwt-payload-sub-prefix [])))
    (is (= (oracle/call :identity 'jwt-payload-exp-val []) id/jwt-payload-exp-val))
    (is (= (ir/execute live 'jwt-payload-json ["did:key:z"])
           (oracle/call :identity 'jwt-payload-json ["did:key:z"])))
    (is (= (oracle/call :identity 'op-token-sig-seg []) id/op-token-sig-seg))
    (is (= (ir/execute live 'graph-name-fleet [])
           (oracle/call :identity 'graph-name-fleet [])))
    (is (= (ir/execute live 'seed-sep [])
           (oracle/call :identity 'seed-sep [])))
    (is (= (ir/execute live 'did-derive-subcmd [])
           (oracle/call :identity 'did-derive-subcmd [])))
    (is (= (ir/execute live 'jwt-seg-sep [])
           (oracle/call :identity 'jwt-seg-sep [])))
    (is (= (oracle/call :identity 'seed-sep []) id/seed-sep))
    (is (= (oracle/call :identity 'did-derive-subcmd []) id/did-derive-subcmd))))

(deftest identity-precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/identity_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string
                 (slurp (io/resource "murakumo/oracle/identity_core.kir.edn")))]
    (is (= live shipped) "identity_core KIR drift — run oracle-gen")))

(deftest product-shell-credits-uses-oracle-results
  (testing "defaults from oracle"
    (is (= 1 credits/default-per-token))
    (is (= (double (/ 1 10)) (double credits/default-head-frac)))
    (is (= (double (/ 1 20)) (double credits/default-protocol-frac)))
    (is (= (oracle/call :infer-credits 'default-per-token [])
           credits/default-per-token)))
  (testing "settle uses memory-time-weight"
    (let [s (credits/settle
             {:model {:credit/per-token 2}
              :tokens 100
              :duration-ms 1000
              :plan {:assignments
                     [{:node {:name "a" :head? true} :span 1 :est-bytes 10}
                      {:node {:name "b"} :span 2 :est-bytes 20}]}})]
      (is (= 200.0 (double (:run/total s))))
      (is (pos? (get-in s [:run/shares "a"] 0)))
      (is (pos? (get-in s [:run/shares "b"] 0)))))
  (testing "charge-allow via oracle for whole balances"
    (is (true? (:allow? (credits/charge {"alice" 100} "alice"
                                        {:model {:credit/per-token 1} :tokens 50}))))
    (is (false? (:allow? (credits/charge {"alice" 10} "alice"
                                         {:model {:credit/per-token 1} :tokens 50}))))))

(deftest credits-oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/infer_credits_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute live 'default-per-token [])
           (oracle/call :infer-credits 'default-per-token [])))
    (is (= (ir/execute live 'head-num [])
           (oracle/call :infer-credits 'head-num [])))
    (let [mt (oracle/record
              [:record :credits/mt-work
               [[:est-bytes :i64] [:duration-ms :i64] [:span :i64]]]
              {:est-bytes 10 :duration-ms 1000 :span 2})
          ch (oracle/record
              [:record :credits/charge
               [[:balance :i64] [:cost :i64]]]
              {:balance 100 :cost 50})]
      (is (= (ir/execute live 'memory-time-weight [mt])
             (oracle/call :infer-credits 'memory-time-weight [mt])))
      (is (= (ir/execute live 'charge-allow? [ch])
             (oracle/call :infer-credits 'charge-allow? [ch]))))
    (let [mul (oracle/record
               [:record :credits/mul [[:price :i64] [:n :i64]]]
               {:price 2 :n 100})]
      (is (= (ir/execute live 'token-cost [mul])
             (oracle/call :infer-credits 'token-cost [mul]))))))

(deftest credits-precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/infer_credits_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string
                 (slurp (io/resource "murakumo/oracle/infer_credits_core.kir.edn")))]
    (is (= live shipped) "infer_credits_core KIR drift — run oracle-gen")))

(deftest product-shell-join-uses-oracle-results
  (testing "tier max-resident from oracle"
    (is (= 2147483648 (get-in join/tiers [:browser :max-resident-bytes])))
    (is (= 4294967296 (get-in join/tiers [:wasm :max-resident-bytes])))
    (is (= 13958643712 (get-in join/tiers [:native :max-resident-bytes]))))
  (testing "can? + needs-relay?"
    (is (true? (join/can? {:tier :browser} :media-postproc)))
    (is (false? (join/can? {:tier :browser} :host-large-model)))
    (is (true? (join/needs-relay? {:tier :browser})))
    (is (true? (join/needs-relay? {:tier :native})))
    (is (false? (join/needs-relay? {:tier :native :inbound-reachable? true}))))
  (testing "enrollment clamp + eligible"
    (let [e (join/enrollment {:name "t" :did "did:x" :tier :browser :mem-bytes (* 8 1024 1024 1024)})]
      (is (= :browser (:node/tier e)))
      (is (= 2147483648 (get-in e [:node/caps :max-resident-bytes])))
      (is (true? (join/eligible-for-work? e {:work-kind :media-postproc :resident-bytes 1000})))
      (is (false? (join/eligible-for-work? e {:work-kind :host-large-model :resident-bytes 1000}))))))

(deftest product-shell-gc-uses-oracle-results
  (testing "defaults"
    (is (= 1073741824 gc/GiB))
    (is (= (* 20 gc/GiB) (:target-free-bytes gc/default-policy)))
    (is (= 7 (:comfy-keep-days gc/default-policy)))
    (is (= 2 (:hf-keep gc/default-policy))))
  (testing "plan pure math via oracle"
    (let [entries [{:path "/a" :class :rpc-cache :bytes (* 25 gc/GiB) :atime-days 30}
                   {:path "/b" :class :protected :bytes 100 :atime-days 1}
                   {:path "/c" :class :comfy-temp :bytes 1000 :atime-days 10}]
          p (gc/plan entries (* 1 gc/GiB) {})]
      (is (pos? (:reclaim-bytes p)))
      (is (true? (:target-met? p)))
      (is (= 1 (:kept-protected p)))
      (is (some #(= "/a" (:path %)) (:evict p))))))
(deftest join-gc-oracle-call-matches-live
  (let [j (:kir (compiler/compile-source (slurp "kotoba/infer_join_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        g (:kir (compiler/compile-source (slurp "kotoba/infer_gc_core.kotoba")
                                         :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute j 'max-resident-bytes [0])
           (oracle/call :infer-join 'max-resident-bytes [0])))
    (let [can (oracle/record [:record :join/can [[:tier :i64] [:kind :string]]]
                             {:tier 0 :kind "media-postproc"})
          relay (oracle/record [:record :join/relay [[:tier :i64] [:inbound :bool]]]
                               {:tier 2 :inbound true})]
      (is (= (ir/execute j 'can? [can])
             (oracle/call :infer-join 'can? [can])))
      (is (= (ir/execute j 'needs-relay? [relay])
             (oracle/call :infer-join 'needs-relay? [relay]))))
    (is (= (ir/execute g 'gib [])
           (oracle/call :infer-gc 'gib [])))
    ;; T5.2 native guest record wire for gc pure inputs
    (let [need (oracle/record [:record :gc/need [[:target :i64] [:free :i64]]]
                              {:target 100 :free 40})
          comfy (oracle/record [:record :gc/comfy
                                [[:atime-days :i64] [:keep-days :i64]]]
                               {:atime-days 10 :keep-days 7})]
      (is (= (ir/execute g 'need-bytes [need])
             (oracle/call :infer-gc 'need-bytes [need])))
      (is (= (ir/execute g 'comfy-evictable? [comfy])
             (oracle/call :infer-gc 'comfy-evictable? [comfy]))))))

(deftest product-shell-moe-uses-oracle-results
  (testing "capacity-default via oracle"
    (is (nil? (moe/capacity-for-usable (* 16 plan/GiB))))
    (is (= 208 (moe/capacity-for-usable (* 32 plan/GiB))))
    (is (= 512 (moe/capacity-for-usable (* 128 plan/GiB)))))
  (testing "expert-ratio + verdict"
    (is (= 16.0 (moe/expert-ratio {:model/experts 128 :model/active-experts 8})))
    (is (= :recommended (:verdict (moe/verdict {:model/experts 128 :model/active-experts 8
                                                :model/moe-shared-expert? true}))))
    (is (= :unknown (:verdict (moe/verdict {})))))
  (testing "resident-est"
    (is (= 1000 (moe/resident-bytes-estimate {:model/weight-bytes 4000 :model/experts 4} 1)))))

(deftest product-shell-rebalance-uses-oracle-results
  (testing "usable-gb + seats-of-* (T5.3)"
    (is (= 10 reb/shard-ceiling-gb))
    (is (= 10 (:usable-gb (reb/node-capacity {:id "a" :ram-gb 16 :status "up"}))))
    (is (= 4 (:usable-gb (reb/node-capacity {:id "a" :ram-gb 10 :status "up"}))))
    (let [seats (#'reb/largest-remainder 5 {:text-pool 3 :media-pool 1 :postproc-pool 1} 1)]
      (is (= 5 (reduce + 0 (vals seats))))
      (is (every? pos? (vals seats))))))

(deftest product-shell-relay-uses-oracle-results
  (testing "make-id + lease-expired? + msg kinds"
    (let [[id st] (#'relay/gen-id (relay/init) "job")]
      (is (= "job-0" id))
      (is (= 1 (:next st))))
    (let [st0 (relay/init)
          [jid st1] (relay/enqueue st0 {:kind :x :input 1 :price 2})
          [wid st2] (relay/on-hello st1 {:did "d" :tier :browser :caps {}})
          [rep st3] (relay/on-ready st2 wid 1000)]
      (is (= :job (:msg rep)))
      (let [st4 (relay/expire-leases st3 2000 100)]
        (is (empty? (:assigned st4)))
        (is (= 1 (count (:queue st4))))))))

(deftest moe-rebalance-relay-oracle-call-matches-live
  (let [m (:kir (compiler/compile-source (slurp "kotoba/infer_moe_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        r (:kir (compiler/compile-source (slurp "kotoba/infer_rebalance_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        y (:kir (compiler/compile-source (slurp "kotoba/infer_relay_core.kotoba")
                                         :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute m 'capacity-default [(* 64 1024 1024 1024)])
           (oracle/call :infer-moe 'capacity-default [(* 64 1024 1024 1024)])))
    (let [v (oracle/record [:record :moe/verdict
                            [[:experts :i64] [:active :i64] [:shared :bool]]]
                           {:experts 128 :active 8 :shared true})]
      (is (= (ir/execute m 'verdict-name [v])
             (oracle/call :infer-moe 'verdict-name [v]))))
    (is (= (ir/execute r 'usable-gb [16])
           (oracle/call :infer-rebalance 'usable-gb [16])))
    (let [sin (oracle/record
               [:record :rebalance/seats-in
                [[:total :i64] [:text-w :i64] [:media-w :i64]
                 [:postproc-w :i64] [:floor :i64]]]
               {:total 5 :text-w 3 :media-w 1 :postproc-w 1 :floor 1})]
      (doseq [ex '[seats-of-text seats-of-media seats-of-postproc seats-total]]
        (is (= (ir/execute r ex [sin])
               (oracle/call :infer-rebalance ex [sin])))))
    (let [run-schema [:record :rebalance/run-flags
                      [[:images [:option :string]]
                       [:video [:option :string]]
                       [:audio [:option :string]]
                       [:swarm [:option :string]]
                       [:tokens [:option :string]]]]
          img-run (oracle/record run-schema
                                 {:images "images" :video nil :audio nil
                                  :swarm nil :tokens nil})
          none-run (oracle/record run-schema
                                  {:images nil :video nil :audio nil
                                   :swarm nil :tokens nil})]
      (is (= (ir/execute r 'classify-run-flags [img-run])
             (oracle/call :infer-rebalance 'classify-run-flags [img-run])))
      (is (= 2 (oracle/call :infer-rebalance 'classify-run-flags [img-run])))
      (is (= 0 (oracle/call :infer-rebalance 'classify-run-flags [none-run]))))
    (let [id (oracle/record [:record :relay/id [[:prefix :string] [:n :i64]]]
                            {:prefix "job" :n 0})
          lease (oracle/record [:record :relay/lease
                                [[:now-ms :i64] [:at-ms :i64] [:ttl-ms :i64]]]
                               {:now-ms 2000 :at-ms 1000 :ttl-ms 100})]
      (is (= (ir/execute y 'make-id [id])
             (oracle/call :infer-relay 'make-id [id])))
      (is (= (ir/execute y 'lease-expired? [lease])
             (oracle/call :infer-relay 'lease-expired? [lease]))))))

(deftest product-shell-persist-uses-oracle-results
  (is (= "did:web:etzhayyim.com:murakumo" persist/repo-authority))
  (is (= "murakumo-fleet" persist/fleet-graph-name))
  (is (= "com.murakumo.fleet.snapshot" persist/snapshot-collection))
  (is (= 18099 persist/snapshot-local-port))
  (is (= 18098 persist/reconcile-local-port))
  (is (= 400 persist/forward-settle-ms))
  (is (= "snap-1-2" (persist/snapshot-rkey 1 2)))
  (is (= "rec-3-4" (persist/reconcile-rkey 3 4)))
  (let [uri (oracle/record [:record :persist/uri
                            [[:collection :string] [:rkey :string]]]
                           {:collection "com.murakumo.fleet.snapshot"
                            :rkey "snap-1-0"})]
    (is (= (oracle/call :persist 'repo-uri [uri])
           (persist/repo-uri "com.murakumo.fleet.snapshot" "snap-1-0"))))
  (is (str/includes? (persist/repo-write-url 18099) "localhost:18099"))
  (is (true? (persist/write-ok? "{\"status\":\"ok\"}")))
  (is (false? (persist/write-ok? "error")))
  (is (= "create" persist/operation-create))
  (is (= "\"status\":\"ok\"" persist/write-ok-marker))
  (is (= "Authorization: Bearer tok" (persist/auth-header "tok")))
  (is (= "content-type: application/json" persist/content-type-json-header))
  (is (= 6 persist/curl-timeout-s))
  (is (= "POST" persist/curl-method-post))
  (is (str/includes? persist/xrpc-repo-write-path "repo.write"))
  (is (= ["curl" "-s" "-m" "6" "-X" "POST"
          (persist/repo-write-url 18099)
          "-H" "Authorization: Bearer tok"
          "-H" "content-type: application/json"
          "-d" "{}"]
         (persist/repo-write-curl-argv 18099 "tok" "{}"))))

(deftest persist-oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/persist_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute live 'repo-authority [])
           (oracle/call :persist 'repo-authority [])))
    (let [rk1 (oracle/record [:record :persist/rkey [[:millis :i64] [:seq-n :i64]]]
                             {:millis 1 :seq-n 2})
          rk2 (oracle/record [:record :persist/rkey [[:millis :i64] [:seq-n :i64]]]
                             {:millis 3 :seq-n 4})
          uri (oracle/record [:record :persist/uri
                              [[:collection :string] [:rkey :string]]]
                             {:collection "c" :rkey "k"})]
      (is (= (ir/execute live 'snapshot-rkey [rk1])
             (oracle/call :persist 'snapshot-rkey [rk1])))
      (is (= (ir/execute live 'reconcile-rkey [rk2])
             (oracle/call :persist 'reconcile-rkey [rk2])))
      (is (= (ir/execute live 'repo-uri [uri])
             (oracle/call :persist 'repo-uri [uri]))))
    (is (= (ir/execute live 'repo-write-url [18099])
           (oracle/call :persist 'repo-write-url [18099])))
    ;; Profile 5: write-ok? is :bool.
    (let [ok (oracle/call :persist 'write-ok? ["{\"status\":\"ok\"}"])
          bad (oracle/call :persist 'write-ok? ["error"])]
      (is (= (ir/execute live 'write-ok? ["{\"status\":\"ok\"}"]) ok))
      (is (contains? #{true 1} ok))
      (is (contains? #{false 0} bad)))
    (is (= (ir/execute live 'operation-create [])
           (oracle/call :persist 'operation-create [])))
    (is (= (ir/execute live 'auth-header ["tok"])
           (oracle/call :persist 'auth-header ["tok"])))
    (is (= (ir/execute live 'curl-timeout-s [])
           (oracle/call :persist 'curl-timeout-s [])))
    (is (= (ir/execute live 'xrpc-repo-write-path [])
           (oracle/call :persist 'xrpc-repo-write-path [])))))

(deftest persist-precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/persist_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string
                 (slurp (io/resource "murakumo/oracle/persist_core.kir.edn")))]
    (is (= live shipped) "persist_core KIR drift — run oracle-gen")))

(deftest product-shell-deploy-uses-oracle-results
  (is (= "/tmp/murakumo-deploy.wasm" dplan/default-wasm))
  (is (= "asher" dplan/default-publish-node))
  (is (= 18900 dplan/artifact-forward-port))
  (is (= 18077 dplan/publish-forward-port))
  (is (= "." (dplan/manifest-dir "murakumo.app.edn")))
  (is (= "apps" (dplan/manifest-dir "apps/murakumo.app.edn")))
  (is (= "apps/foo.edn" (dplan/app-manifest-path "apps" {:manifest "foo.edn"})))
  (is (= "asher" (dplan/publish-selector nil)))
  (is (= "judah" (dplan/publish-selector "judah")))
  (is (= "http://localhost:8080"
         (last (dplan/app-deploy-argv "k" "m" "w" 8080))))
  (is (= "ok" (dplan/command-output "  ok\n")))
  (is (true? (dplan/execution-observed? "1\n")))
  (is (false? (dplan/execution-observed? "0\n")))
  (is (re-find #"bafyCID" (dplan/execution-count-command "bafyCID")))
  (is (= "/" dplan/path-sep))
  (is (= (str dplan/exec-count-prefix "bafyCID" dplan/exec-count-suffix)
         (dplan/execution-count-command "bafyCID")))
  (is (= "release/../../../crates/kotoba-runtime/wit"
         (dplan/release-wit-path "release")))
  (is (re-find #"18900:localhost" (dplan/stop-forward-command 18900)))
  (is (= (str dplan/pkill-f-prefix "18900" dplan/stop-forward-suffix)
         (dplan/stop-forward-command 18900)))
  (is (true? (dplan/absolute-git-bin? "/usr/bin/git")))
  (is (false? (dplan/absolute-git-bin? "git")))
  (is (= "cp" dplan/cp-bin))
  (is (= "rm" dplan/rm-bin))
  (is (= "-rf" dplan/rm-rf-flag))
  (is (= "-R" dplan/cp-recursive-flag))
  (is (= "p2p,realtime-wasm,webrtc" dplan/build-features))
  (is (= "bin/kotoba" (dplan/version-bin-path "bin")))
  (is (= ["cp" "a" "b"] (dplan/copy-argv "a" "b")))
  (is (= ["rm" "-rf" "x"] (dplan/remove-tree-argv "x")))
  (is (= ["cp" "-R" "s" "d"] (dplan/copy-tree-argv "s" "d")))
  (is (= ["/usr/bin/git" "-C" "src" "rev-parse" "--short" "HEAD"]
         (dplan/git-short-sha-argv "src" "/usr/bin/git")))
  (is (= ["bin/kotoba" "--version"] (dplan/version-argv "bin")))
  (is (= :missing-manifest (dplan/deploy-command-error nil "seed")))
  (is (= :missing-operator-seed (dplan/deploy-command-error "m.edn" "")))
  (is (nil? (dplan/deploy-command-error "m.edn" "seed")))
  (is (= "kotoba" dplan/pin-bin-kotoba))
  (is (= "kotoba-server" dplan/pin-bin-server))
  (is (= "wit" dplan/pin-wit-dirname))
  (is (= "a/b" (dplan/join-path "a" "b")))
  (is (= (str "a" dplan/path-sep "b") (dplan/join-path "a" "b")))
  (is (= "bin/wit" (dplan/pin-wit-dest "bin")))
  (is (= (str "bin" dplan/path-sep dplan/pin-wit-dirname)
         (dplan/pin-wit-dest "bin")))
  (is (= ["kotoba" "kotoba-server"] dplan/pinned-binaries))
  (let [pin (dplan/pin-copy-plan "/rel" "bin")]
    (is (= "/rel/kotoba" (-> pin :binaries first :src)))
    (is (= "bin/wit" (get-in pin [:wit :dest]))))
  (is (= "component" dplan/component-subcmd))
  (is (= "build" dplan/build-subcmd))
  (is (= "app" dplan/app-subcmd))
  (is (= "deploy" dplan/deploy-subcmd))
  (is (= "--wit-dir" dplan/wit-dir-flag))
  (is (= "-o" dplan/output-flag))
  (is (= "--publish" dplan/publish-flag))
  (is (= "--url" dplan/url-flag))
  (is (= "--token" dplan/token-flag))
  (is (= "block" dplan/block-subcmd))
  (is (= "put" dplan/put-subcmd))
  (is (= "--file" dplan/file-flag))
  (is (= " " dplan/argv-join-sep))
  (is (= "http://localhost:" dplan/localhost-url-prefix))
  (is (= (str dplan/localhost-url-prefix "8080")
         (last (dplan/app-deploy-argv "k" "m" "w" 8080))))
  (is (= "component" (second (dplan/component-build-argv "k" "s" "w" "o"))))
  (is (= "--wit-dir" (nth (dplan/app-deploy-argv "k" "m" "w" 1) 4)))
  (is (= "block" (nth (dplan/block-put-argv "k" "t" "w" 1) 5))))
(deftest product-shell-connect-uses-oracle-results
  (let [connect {:default-class :wasm
                 :classes {:wasm {:read [:http] :live [:webrtc]}
                           :browser {:read [:http] :live [:webrtc]}
                           :native {:read [:http] :live [:quic]}}}]
    (is (= :wasm (conn/default-class connect)))
    (is (= :native (conn/default-class {})))
    (is (= (keyword conn/class-native) (conn/default-class {})))
    (is (= :wasm (conn/node-class connect {})))
    (is (true? (conn/serves-reach? connect {:class :wasm} :browser/read)))
    (is (true? (conn/serves-reach? connect {:class :wasm} :browser/live)))
    (is (false? (conn/serves-reach? connect {:class :native} :browser/live)))
    (is (= "native" conn/class-native))
    (is (= "read" conn/plane-read))
    (is (= "live" conn/plane-live))))

(deftest product-shell-component-authority-uses-oracle-results
  (is (= 1 cauth/event-version))
  (is (= "murakumo.component-authority/v1" cauth/format-v1))
  (is (= "ed25519" cauth/algorithm-ed25519))
  (is (= "place" cauth/op-place))
  (is (= "revoke" cauth/op-revoke))
  (is (= "unknown" cauth/op-unknown))
  (is (= "placed" cauth/event-placed))
  (is (= "revoked" cauth/event-revoked))
  (let [[st1 e1] (cauth/place (cauth/initial-state) "cid1" "node-a")]
    (is (= 1 (get-in st1 [:epochs "cid1"])))
    (is (= 1 (:sequence st1)))
    (is (= :placed (:murakumo.component/event e1)))
    (is (= (keyword cauth/event-placed) (:murakumo.component/event e1)))
    (let [[st2 e2] (cauth/revoke st1 "cid1")]
      (is (= 2 (get-in st2 [:epochs "cid1"])))
      (is (= 2 (:sequence st2)))
      (is (= :revoked (:murakumo.component/event e2)))
      (is (= (keyword cauth/event-revoked) (:murakumo.component/event e2))))))

(deftest deploy-connect-cauth-oracle-call-matches-live
  (let [d (:kir (compiler/compile-source (slurp "kotoba/deploy_plan_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        c (:kir (compiler/compile-source (slurp "kotoba/connect_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        a (:kir (compiler/compile-source (slurp "kotoba/component_authority_core.kotoba")
                                         :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute d 'default-wasm [])
           (oracle/call :deploy-plan 'default-wasm [])))
    (is (= (ir/execute d 'manifest-dir ["apps/x.edn"])
           (oracle/call :deploy-plan 'manifest-dir ["apps/x.edn"])))
    (is (= (ir/execute d 'execution-observed? ["2"])
           (oracle/call :deploy-plan 'execution-observed? ["2"])))
    (is (= (ir/execute d 'release-wit-path ["rel"])
           (oracle/call :deploy-plan 'release-wit-path ["rel"])))
    (is (= (ir/execute d 'absolute-unix-git-bin? ["/bin/git"])
           (oracle/call :deploy-plan 'absolute-unix-git-bin? ["/bin/git"])))
    (is (= (ir/execute d 'cp-bin [])
           (oracle/call :deploy-plan 'cp-bin [])))
    (is (= (ir/execute d 'version-bin-path ["bin"])
           (oracle/call :deploy-plan 'version-bin-path ["bin"])))
    (is (= (ir/execute d 'build-features [])
           (oracle/call :deploy-plan 'build-features [])))
    (is (= (ir/execute d 'missing-manifest? [""])
           (oracle/call :deploy-plan 'missing-manifest? [""])))
    (is (= (ir/execute d 'missing-operator-seed? ["seed"])
           (oracle/call :deploy-plan 'missing-operator-seed? ["seed"])))
    (is (= (oracle/call :deploy-plan 'cp-bin []) dplan/cp-bin))
    (is (= (oracle/call :deploy-plan 'build-features []) dplan/build-features))
    (let [jp (oracle/record [:record :deploy/join-path [[:a :string] [:b :string]]]
                            {:a "a" :b "b"})
          am (oracle/record [:record :deploy/app-manifest
                             [[:manifest-dir :string] [:manifest :string]]]
                            {:manifest-dir "apps" :manifest "foo.edn"})
          cb (oracle/record [:record :deploy/component-build
                             [[:kotoba :string] [:src-path :string]
                              [:wit :string] [:wasm :string]]]
                            {:kotoba "k" :src-path "s" :wit "w" :wasm "o"})
          ad (oracle/record [:record :deploy/app-deploy
                             [[:kotoba :string] [:manifest :string]
                              [:wit :string] [:port :i64]]]
                            {:kotoba "k" :manifest "m" :wit "w" :port 18077})]
      (is (= (ir/execute d 'join-path [jp])
             (oracle/call :deploy-plan 'join-path [jp])))
      (is (= (oracle/call :deploy-plan 'join-path [jp])
             (dplan/join-path "a" "b")))
      (is (= (ir/execute d 'app-manifest-path [am])
             (oracle/call :deploy-plan 'app-manifest-path [am])))
      (is (= (oracle/call :deploy-plan 'app-manifest-path [am])
             (dplan/app-manifest-path "apps" {:manifest "foo.edn"})))
      (is (= (ir/execute d 'component-build-cmd [cb])
             (oracle/call :deploy-plan 'component-build-cmd [cb])))
      (is (= (ir/execute d 'app-deploy-cmd [ad])
             (oracle/call :deploy-plan 'app-deploy-cmd [ad]))))
    (is (= (ir/execute d 'pin-wit-dirname [])
           (oracle/call :deploy-plan 'pin-wit-dirname [])))
    (is (= (ir/execute d 'pin-wit-dest ["bin"])
           (oracle/call :deploy-plan 'pin-wit-dest ["bin"])))
    (is (= (oracle/call :deploy-plan 'pin-wit-dest ["bin"])
           (dplan/pin-wit-dest "bin")))
    (is (= (oracle/call :deploy-plan 'pin-bin-kotoba []) dplan/pin-bin-kotoba))
    (is (= (ir/execute d 'component-subcmd [])
           (oracle/call :deploy-plan 'component-subcmd [])))
    (is (= (ir/execute d 'wit-dir-flag [])
           (oracle/call :deploy-plan 'wit-dir-flag [])))
    (is (= (ir/execute d 'file-flag [])
           (oracle/call :deploy-plan 'file-flag [])))
    (is (= (oracle/call :deploy-plan 'component-subcmd []) dplan/component-subcmd))
    (is (= (oracle/call :deploy-plan 'block-subcmd []) dplan/block-subcmd))
    (is (= (ir/execute d 'argv-join-sep [])
           (oracle/call :deploy-plan 'argv-join-sep [])))
    (is (= (oracle/call :deploy-plan 'argv-join-sep []) dplan/argv-join-sep))
    (is (= (ir/execute d 'localhost-url-prefix [])
           (oracle/call :deploy-plan 'localhost-url-prefix [])))
    (is (= (oracle/call :deploy-plan 'localhost-url-prefix [])
           dplan/localhost-url-prefix))
    (is (= (ir/execute d 'localhost-url [18077])
           (oracle/call :deploy-plan 'localhost-url [18077])))
    (is (= (str dplan/localhost-url-prefix "18077")
           (oracle/call :deploy-plan 'localhost-url [18077])))
    (is (= (ir/execute d 'path-sep [])
           (oracle/call :deploy-plan 'path-sep [])))
    (is (= (oracle/call :deploy-plan 'path-sep []) dplan/path-sep))
    (is (= (ir/execute d 'exec-count-prefix [])
           (oracle/call :deploy-plan 'exec-count-prefix [])))
    (is (= (oracle/call :deploy-plan 'exec-count-prefix [])
           dplan/exec-count-prefix))
    (is (= (ir/execute d 'pkill-f-prefix [])
           (oracle/call :deploy-plan 'pkill-f-prefix [])))
    (is (= (oracle/call :deploy-plan 'pkill-f-prefix []) dplan/pkill-f-prefix))
    (is (= (ir/execute d 'stop-forward-suffix [])
           (oracle/call :deploy-plan 'stop-forward-suffix [])))
    (is (= (oracle/call :deploy-plan 'stop-forward-suffix [])
           dplan/stop-forward-suffix))
    (is (= (ir/execute c 'default-class-name [""])
           (oracle/call :connect 'default-class-name [""])))
    (is (= (ir/execute c 'class-native [])
           (oracle/call :connect 'class-native [])))
    (is (= (oracle/call :connect 'class-native []) conn/class-native))
    (is (= (ir/execute c 'plane-read [])
           (oracle/call :connect 'plane-read [])))
    (is (= (oracle/call :connect 'plane-live []) conn/plane-live))
    (let [plane (oracle/record
                 [:record :connect/plane
                  [[:plane :string] [:http? :bool] [:common? :bool]]]
                 {:plane "read" :http? true :common? false})]
      (is (= (ir/execute c 'serves-plane? [plane])
             (oracle/call :connect 'serves-plane? [plane]))))
    (is (= (ir/execute a 'place-epoch [0])
           (oracle/call :component-authority 'place-epoch [0])))
    (is (= (ir/execute a 'revoke-epoch [1])
           (oracle/call :component-authority 'revoke-epoch [1])))
    (is (= (ir/execute a 'op-place [])
           (oracle/call :component-authority 'op-place [])))
    (is (= (oracle/call :component-authority 'op-place []) cauth/op-place))
    (is (= (ir/execute a 'event-placed [])
           (oracle/call :component-authority 'event-placed [])))
    (is (= (oracle/call :component-authority 'event-revoked []) cauth/event-revoked))
    (is (= (ir/execute a 'format-v1 [])
           (oracle/call :component-authority 'format-v1 [])))
    (is (= (oracle/call :component-authority 'format-v1 []) cauth/format-v1))
    (is (= (ir/execute a 'event-kind ["place"])
           (oracle/call :component-authority 'event-kind [cauth/op-place])))
    (is (= cauth/event-placed
           (oracle/call :component-authority 'event-kind [cauth/op-place])))))

(deftest product-shell-overlay-keyring-stream-peer-uses-oracle
  (testing "keyring"
    (is (= 86400 okr/default-rotation-seconds))
    (is (= 1 (okr/epoch 90000 86400)))
    (is (= 16 (count (okr/key-id "ov" 1))))
    (let [k (okr/derive-key "seed" "ov" 1)]
      (is (= 1 (:epoch k)))
      (is (string? (:key k)))))
  (testing "stream"
    (is (= 64 ostream/default-window-size))
    (is (= 0 ostream/initial-next-seq))
    (is (= "murakumo.overlay.stream" ostream/type-stream))
    (is (= "murakumo.overlay.stream-frame" ostream/type-frame))
    (is (= "murakumo.overlay.stream-ack" ostream/type-ack))
    (let [s (ostream/open-stream {:overlay "o" :node "n" :name "x" :principal "p"} :svc)
          s2 (ostream/advance s)
          fr (ostream/frame s "p")
          ac (ostream/ack {:stream "s" :seq 0} true)]
      (is (= ostream/type-stream (:type s)))
      (is (= ostream/initial-next-seq (:next-seq s)))
      (is (= 0 (:next-seq s)))
      (is (= 1 (:next-seq s2)))
      (is (= ostream/type-frame (:type fr)))
      (is (= ostream/type-ack (:type ac)))
      (is (true? (:accepted? ac)))
      (is (false? (:accepted? (ostream/ack {:stream "s" :seq 0} false))))))
  (testing "peer choose-path"
    (let [p {:direct [{:endpoint "quic://a"}] :relay {:endpoint "r"} :health :seen}
          path (opeer/choose-path p)]
      (is (= :direct (:via path))))
    (let [p {:direct [{:endpoint "quic://a"}] :relay {:endpoint "r"} :health :down}
          path (opeer/choose-path p)]
      (is (= :relay (:via path))))
    (is (= "unknown" opeer/health-unknown))
    (is (= "seen" opeer/health-seen))
    (is (= "down" opeer/health-down))
    (is (= "direct" opeer/via-direct))
    (is (= "relay" opeer/via-relay))
    (is (= (keyword opeer/via-direct)
           (:via (opeer/choose-path
                  {:direct [{:endpoint "q"}] :relay {:endpoint "r"}
                   :health (keyword opeer/health-seen)}))))
    (is (= (keyword opeer/via-relay)
           (:via (opeer/choose-path
                  {:direct [{:endpoint "q"}] :relay {:endpoint "r"}
                   :health (keyword opeer/health-down)}))))))

(deftest product-shell-cloud-provision-uses-oracle
  (testing "provision constants + multiaddr + shell pure"
    (is (= "com.murakumo.kotoba-mesh" pplan/plist-label))
    (is (= 8000 pplan/peer-advertise-wait-ms))
    (is (true? (pplan/operator-seed-missing? "")))
    (is (false? (pplan/operator-seed-missing? "abc")))
    (is (= 4001 (pplan/node-p2p-port {} {})))
    (is (= 5001 (pplan/node-p2p-port {} {:p2p-port 5001})))
    (is (= "/ip4/" pplan/multiaddr-ip4-prefix))
    (is (= "/udp/" pplan/multiaddr-udp-mid))
    (is (= "/quic-v1" pplan/multiaddr-quic-suffix))
    (is (= "/ip4/1.2.3.4/udp/4001/quic-v1" (pplan/multiaddr "1.2.3.4" 4001)))
    (is (= (str pplan/multiaddr-ip4-prefix "1.2.3.4"
                pplan/multiaddr-udp-mid "4001" pplan/multiaddr-quic-suffix)
           (pplan/multiaddr "1.2.3.4" 4001)))
    (is (= "sudo launchctl print system/" pplan/launchctl-print-prefix))
    (is (= "sudo launchctl bootout system/" pplan/launchctl-bootout-prefix))
    (is (= "sudo launchctl bootstrap system " pplan/launchctl-bootstrap-sys))
    (is (= "/Library/LaunchDaemons/" pplan/launchd-daemons-dir))
    (is (= (str pplan/launchctl-bootstrap-sys pplan/launchd-daemons-dir)
           pplan/launchctl-bootstrap-prefix))
    (is (= "sudo launchctl kickstart -k system/" pplan/launchctl-kickstart-prefix))
    (is (= ".plist" pplan/plist-ext))
    (is (re-find #"installed" (pplan/mesh-binary-status-command)))
    (is (re-find #"launchctl print" (pplan/launch-status-command)))
    (is (= (str pplan/launchctl-print-prefix pplan/plist-label
                pplan/launchctl-status-suffix)
           (pplan/launch-status-command)))
    (is (= (str pplan/launchctl-bootout-prefix pplan/plist-label)
           (pplan/launch-command :down)))
    (is (= (str pplan/launchd-daemons-dir pplan/plist-label pplan/plist-ext)
           (pplan/launchd-daemon-path pplan/plist-label)))
    (is (re-find #"did:key:12D3" (pplan/peer-id-log-command)))
    (is (re-find #"peer connected" (pplan/live-link-count-command)))
    (is (= "3" (pplan/live-link-count-output " 3\n")))
    (is (re-find #"kickstart" (pplan/launch-command :up)))
    (is (re-find #"bootout" (pplan/launch-command :down)))
    (is (= "com.murakumo.kotoba-mesh-watchdog" pplan/watchdog-label))
    (is (re-find #"watchdog" (pplan/watchdog-reprovision-command)))
    (is (= "rsync" pplan/rsync-bin))
    (is (= "-az" pplan/rsync-az-flag))
    (is (= "-e" pplan/rsync-e-flag))
    (is (= "/local/bin/kotoba" (pplan/local-bin-path "/local/bin" "kotoba")))
    (is (= "asher:.murakumo/bin/kotoba" (pplan/remote-bin-dest "asher" "kotoba")))
    (is (= "/Library/LaunchDaemons/com.murakumo.kotoba-mesh.plist"
           (pplan/launchd-daemon-path "com.murakumo.kotoba-mesh")))
    (is (str/starts-with? (pplan/tee-plist-prefix "com.murakumo.kotoba-mesh")
                          "sudo tee /Library/LaunchDaemons/"))
    (is (= "zone=jp" (pplan/label-kv "zone" "jp")))
    (is (= ["rsync" "-az" "-e" pplan/ssh-rsync-options
            "/local/bin/kotoba" "asher:.murakumo/bin/kotoba"]
           (pplan/rsync-binary-argv "/local/bin" "asher" "kotoba")))
    (is (str/includes? (pplan/write-plist-command "<x/>") "<<'PLIST'"))
    (is (str/ends-with? (pplan/write-plist-command "<x/>") "\nPLIST"))
    (is (= "@" pplan/peer-at-sep))
    (is (= "," pplan/peer-join-sep))
    (is (= "did:key:" pplan/did-key-prefix))
    (is (= "12D3x@/ip4/1.2.3.4/udp/4001/quic-v1"
           (pplan/peer-entry "12D3x" (pplan/multiaddr "1.2.3.4" 4001))))
    (is (= "12D3" pplan/peer-id-body-prefix))
    (is (= "12D3[A-Za-z0-9]*" pplan/peer-id-body-pattern))
    (is (= "did:key:12D3[A-Za-z0-9]*" pplan/peer-id-did-pattern))
    (is (= "did:key:12D3x" (pplan/did-peer-id "12D3x")))
    (is (str/includes? (pplan/peer-id-log-command) pplan/peer-id-did-pattern))
    (is (str/includes? (pplan/live-link-count-command) pplan/peer-id-body-pattern))
    (is (= "12D3KooWPeerId123"
           (pplan/peer-id-from-log "did:key:12D3KooWPeerId123")))
    (is (= "/.murakumo/bin" pplan/home-bin-suffix))
    (is (= "/Users/mesh/.murakumo/bin" (pplan/home-bin-path "/Users/mesh")))
    (is (= "," pplan/label-join-sep))
    (is (= "," pplan/roles-join-sep))
    (is (= "zone=jp,role=compute"
           (pplan/labels-env {:zone "jp" :role "compute"})))
    (is (= "{{USER}}" pplan/plist-ph-user))
    (is (= "{{BIN}}" pplan/plist-ph-bin))
    (is (= "{{PORT}}" pplan/plist-ph-port))
    (is (= "{{ROLES}}" pplan/plist-ph-roles))
    (is (= "{{LABELS}}" pplan/plist-ph-labels))
    (is (= "{{HOME}}" pplan/plist-ph-home))
    (is (= "{{ED25519}}" pplan/plist-ph-ed25519))
    (is (= "{{X25519}}" pplan/plist-ph-x25519))
    (is (= "{{DID}}" pplan/plist-ph-did))
    (is (= "{{P2PPORT}}" pplan/plist-ph-p2pport))
    (is (= "{{P2PSEED}}" pplan/plist-ph-p2pseed))
    (is (= "{{EXTADDR}}" pplan/plist-ph-extaddr))
    (is (= "{{BOOTSTRAP}}" pplan/plist-ph-bootstrap))
    (is (= "{{WEBRTC}}" pplan/plist-ph-webrtc))
    (is (= "a" (pplan/join-append "" "," "a")))
    (is (= "a,b" (pplan/join-append "a" "," "b")))
    (is (= "p@/m" (pplan/bootstrap-append "" "p@/m")))
    (is (= "p@/m,q@/n" (pplan/bootstrap-append "p@/m" "q@/n")))
    (is (= "zone=jp,role=c" (pplan/labels-append "zone=jp" "role=c")))
    (is (= "compute,pin" (pplan/roles-append "compute" "pin")))
    (is (= "xopsy" (pplan/plist-replace "x{{U}}y" "{{U}}" "ops")))
    (is (= "12D3KooWPeerId123"
           (pplan/peer-id-from-log "node_did=did:key:12D3KooWPeerId123\n")))
    (is (= "12D3KooWPeerId123"
           (pplan/peer-id-from-log "noise\ndid:key:12D3KooWPeerId123 trailing\n")))
    (is (nil? (pplan/peer-id-from-log "did:key:zOther")))
    (is (str/includes? (pplan/write-plist-shell "com.murakumo.kotoba-mesh" "<x/>")
                       "<<'PLIST'"))
    (is (= (pplan/write-plist-command "<x/>")
           (pplan/write-plist-shell pplan/plist-label "<x/>")))
    (is (= (pplan/write-watchdog-plist-command "<w/>")
           (pplan/write-plist-shell pplan/watchdog-label "<w/>"))))
  (testing "cloud defaults + region/endpoints"
    (is (= "murakumo-overlay" cplan/default-driver))
    (is (= "cloud.murakumo.node" cplan/node-record-type))
    (is (= "cloud.murakumo.route" cplan/route-record-type))
    (is (= "cloud.murakumo.relay" cplan/relay-record-type))
    (is (= "cloud.murakumo.policy" cplan/policy-record-type))
    (is (= "cloud.murakumo.bootstrap" cplan/bootstrap-record-type))
    (is (= "ssh" cplan/cap-ssh))
    (is (= "http" cplan/cap-http))
    (is (= "gossip" cplan/cap-gossip))
    (is (= "deploy" cplan/cap-deploy))
    (is (= "reconcile" cplan/cap-reconcile))
    (is (= [:ssh :http :gossip :deploy :reconcile] cplan/default-node-capabilities))
    (is (= "murakumo.cloud" (:cloud/name cplan/default-cloud)))
    (is (= 1 (:overlay/version cplan/default-cloud)))
    (is (= "global" (cplan/node-region {})))
    (is (= "z1" (cplan/node-region {:labels {:zone "z1"}})))
    (is (= 0 (cplan/relay-score {:labels {:zone "a"}} {:region "a"})))
    (is (= 1 (cplan/relay-score {:labels {:zone "a"}} {:region "b"})))
    (let [ep (cplan/direct-endpoint cplan/default-cloud
                                    {:fleet/name "f"}
                                    {:name "n" :host "h" :p2p-port 4001}
                                    :quic)]
      (is (= "quic://h:4001" (:endpoint ep))))
    (is (= "-" cplan/dash-placeholder))
    (is (str/includes? cplan/summary-nodes-header "NODE"))
    (is (str/includes? cplan/routes-header "DIRECT"))
    (is (= "unknown murakumo.cloud node: asher"
           (cplan/unknown-node-line "asher")))
    (is (= "murakumo.cloud dial asher denied by policy"
           (cplan/dial-denied-line "asher")))
    (is (= "murakumo.cloud connect asher"
           (cplan/connect-ok-title "asher")))
    (is (= "  reason=unknown" (cplan/reason-line "unknown")))
    (is (= "  murakumo-overlay x" (cplan/indent-argv-line "murakumo-overlay x")))
    (is (= "  address-family identity ; nodes 2 ; relays 1"
           (cplan/address-family-line "identity" 2 1)))
    (is (= "  policy default=deny allow=3" (cplan/policy-line "deny" 3)))
    (is (= " skipped reason=down" (cplan/skipped-reason-suffix "down")))
    (is (= :records
           (:command (cplan/parse-flags ["records" "--cloud=p.edn"]))))
    (is (= "p.edn"
           (:cloud-path (cplan/parse-flags ["records" "--cloud=p.edn"]))))
    (is (= :browser
           (:from (cplan/parse-flags ["dial" "n" "--from=browser"]))))
    (is (= :edn
           (:format (cplan/parse-flags ["bootstrap" "--format=edn"]))))
    (is (= "plan" cplan/cmd-plan))
    (is (= "records" cplan/cmd-records))
    (is (= "dial" cplan/cmd-dial))
    (is (= "bootstrap" cplan/cmd-bootstrap))
    (is (= "plan" cplan/default-command-token))
    (is (= "dial" (cplan/command-token "dial")))
    (is (= "" (cplan/command-token "--cloud=x")))
    (is (= "--cloud=" cplan/flag-cloud-prefix))
    (is (= "--" cplan/flag-dash-prefix))
    (is (= "--capability=" cplan/flag-capability-prefix))
    (is (= "--auth-key=" cplan/flag-auth-key-prefix))
    (is (= :plan (:command (cplan/parse-flags []))))))

(deftest overlay-cloud-prov-oracle-call-matches-live
  (let [k (:kir (compiler/compile-source (slurp "kotoba/overlay_keyring_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        pe (:kir (compiler/compile-source (slurp "kotoba/overlay_peer_core.kotoba")
                                          :wasm32-kotoba-v1 {}))
        s (:kir (compiler/compile-source (slurp "kotoba/overlay_stream_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        c (:kir (compiler/compile-source (slurp "kotoba/cloud_plan_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        pr (:kir (compiler/compile-source (slurp "kotoba/provision_plan_core.kotoba")
                                          :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute k 'default-rotation-seconds [])
           (oracle/call :overlay-keyring 'default-rotation-seconds [])))
    (is (= (ir/execute k 'key-id-mid [])
           (oracle/call :overlay-keyring 'key-id-mid [])))
    (is (= (oracle/call :overlay-keyring 'key-id-mid []) okr/key-id-mid))
    (is (= (ir/execute k 'derive-key-mid [])
           (oracle/call :overlay-keyring 'derive-key-mid [])))
    (is (= (oracle/call :overlay-keyring 'seed-sep []) okr/seed-sep))
    (is (= (ir/execute k 'type-key [])
           (oracle/call :overlay-keyring 'type-key [])))
    (is (= (oracle/call :overlay-keyring 'type-rotation []) okr/type-rotation))
    (is (= (ir/execute k 'key-id-hex-len [])
           (oracle/call :overlay-keyring 'key-id-hex-len [])))
    (is (= (oracle/call :overlay-keyring 'key-id-hex-len []) okr/key-id-hex-len))
    (let [via-schema [:record :peer/via
                      [[:direct [:option :string]]
                       [:health :string]
                       [:relay [:option :string]]]]
          direct-ok (oracle/record via-schema
                                   {:direct "direct" :health "unknown" :relay "relay"})
          direct-down (oracle/record via-schema
                                     {:direct "direct" :health "down" :relay "relay"})
          none (oracle/record via-schema
                              {:direct nil :health "unknown" :relay nil})
          down-relay (oracle/record via-schema
                                    {:direct "direct" :health opeer/health-down :relay "relay"})
          seen (oracle/record via-schema
                              {:direct "direct" :health opeer/health-seen :relay "relay"})]
      (is (= (ir/execute pe 'choose-via [direct-ok])
             (oracle/call :overlay-peer 'choose-via [direct-ok])))
      (is (= "direct" (oracle/call :overlay-peer 'choose-via [direct-ok])))
      (is (= "relay" (oracle/call :overlay-peer 'choose-via [direct-down])))
      (is (= "" (oracle/call :overlay-peer 'choose-via [none])))
      (is (= (ir/execute pe 'health-down [])
             (oracle/call :overlay-peer 'health-down [])))
      (is (= (oracle/call :overlay-peer 'health-down []) opeer/health-down))
      (is (= (ir/execute pe 'via-direct [])
             (oracle/call :overlay-peer 'via-direct [])))
      (is (= (oracle/call :overlay-peer 'via-direct []) opeer/via-direct))
      (is (= (ir/execute pe 'via-relay [])
             (oracle/call :overlay-peer 'via-relay [])))
      (is (= (oracle/call :overlay-peer 'via-relay []) opeer/via-relay))
      (is (= (oracle/call :overlay-peer 'choose-via [down-relay])
             opeer/via-relay))
      (is (= (oracle/call :overlay-peer 'choose-via [seen])
             opeer/via-direct)))
    (is (= (ir/execute s 'advance-seq [3])
           (oracle/call :overlay-stream 'advance-seq [3])))
    (is (= (ir/execute s 'type-stream [])
           (oracle/call :overlay-stream 'type-stream [])))
    (is (= (oracle/call :overlay-stream 'type-stream []) ostream/type-stream))
    (is (= (ir/execute s 'type-frame [])
           (oracle/call :overlay-stream 'type-frame [])))
    (is (= (oracle/call :overlay-stream 'type-ack []) ostream/type-ack))
    (is (= (ir/execute s 'initial-next-seq [])
           (oracle/call :overlay-stream 'initial-next-seq [])))
    (is (= (oracle/call :overlay-stream 'initial-next-seq []) ostream/initial-next-seq))
    (let [reg (oracle/record [:record :cloud/region-in
                              [[:zone :string] [:region-label :string] [:region :string]]]
                             {:zone "" :region-label "" :region ""})
          hp (oracle/record [:record :cloud/host-port [[:host :string] [:port :i64]]]
                            {:host "h" :port 4001})
          wt (oracle/record [:record :cloud/host-port [[:host :string] [:port :i64]]]
                            {:host "h" :port 8077})
          tr (oracle/record [:record :cloud/transport [[:scheme :string] [:host :string]]]
                            {:scheme "custom" :host "h"})
          ftc (oracle/record [:record :cloud/from-to-cap
                              [[:from :string] [:to :string]
                               [:capability :string] [:reason :string]]]
                             {:from "a" :to "b" :capability "c" :reason "d"})
          st (oracle/record [:record :cloud/summary-title
                             [[:domain :string] [:overlay :string]]]
                            {:domain "dom" :overlay "ov"})
          af (oracle/record [:record :cloud/address-family
                             [[:af :string] [:nodes :i64] [:relays :i64]]]
                            {:af "identity" :nodes 2 :relays 1})
          pol (oracle/record [:record :cloud/policy
                              [[:default :string] [:allow-n :i64]]]
                             {:default "deny" :allow-n 3})
          af0 (oracle/record [:record :cloud/address-family
                              [[:af :string] [:nodes :i64] [:relays :i64]]]
                             {:af "identity" :nodes 0 :relays 0})]
      (is (= (ir/execute c 'node-region [reg])
             (oracle/call :cloud-plan 'node-region [reg])))
      (is (= (ir/execute c 'quic-endpoint [hp])
             (oracle/call :cloud-plan 'quic-endpoint [hp])))
      (is (= (ir/execute c 'webtransport-endpoint [wt])
             (oracle/call :cloud-plan 'webtransport-endpoint [wt])))
      (is (= (ir/execute c 'transport-endpoint [tr])
             (oracle/call :cloud-plan 'transport-endpoint [tr])))
      (is (= (ir/execute c 'dash-placeholder [])
             (oracle/call :cloud-plan 'dash-placeholder [])))
      (is (= (ir/execute c 'unknown-node-line ["asher"])
             (oracle/call :cloud-plan 'unknown-node-line ["asher"])))
      (is (= (ir/execute c 'from-to-cap-reason [ftc])
             (oracle/call :cloud-plan 'from-to-cap-reason [ftc])))
      (is (= (ir/execute c 'summary-title [st])
             (oracle/call :cloud-plan 'summary-title [st])))
      (is (= (ir/execute c 'address-family-line [af])
             (oracle/call :cloud-plan 'address-family-line [af])))
      (is (= (ir/execute c 'policy-line [pol])
             (oracle/call :cloud-plan 'policy-line [pol])))
      (is (= (ir/execute c 'skipped-reason-suffix ["x"])
             (oracle/call :cloud-plan 'skipped-reason-suffix ["x"])))
      (is (= (oracle/call :cloud-plan 'dash-placeholder []) cplan/dash-placeholder))
      (is (= (oracle/call :cloud-plan 'unknown-node-line ["x"])
             (cplan/unknown-node-line "x")))
      (is (= (oracle/call :cloud-plan 'address-family-line [af0])
             (cplan/address-family-line "identity" 0 0))))
    (is (= (ir/execute c 'node-record-type [])
           (oracle/call :cloud-plan 'node-record-type [])))
    (is (= (ir/execute c 'bootstrap-record-type [])
           (oracle/call :cloud-plan 'bootstrap-record-type [])))
    (is (= (ir/execute c 'cap-ssh [])
           (oracle/call :cloud-plan 'cap-ssh [])))
    (is (= (oracle/call :cloud-plan 'node-record-type []) cplan/node-record-type))
    (is (= (oracle/call :cloud-plan 'cap-reconcile []) cplan/cap-reconcile))
    (is (= (ir/execute c 'is-cmd-plan? ["plan"])
           (oracle/call :cloud-plan 'is-cmd-plan? ["plan"])))
    (is (= (ir/execute c 'is-flag-cloud? ["--cloud=x"])
           (oracle/call :cloud-plan 'is-flag-cloud? ["--cloud=x"])))
    (is (= (ir/execute c 'flag-cloud-value ["--cloud=prod.edn"])
           (oracle/call :cloud-plan 'flag-cloud-value ["--cloud=prod.edn"])))
    (is (= (ir/execute c 'is-positional-target? ["asher"])
           (oracle/call :cloud-plan 'is-positional-target? ["asher"])))
    ;; Profile 5: is-cmd-dial? is :bool (true/false or 0/1 word).
    (is (contains? #{true 1} (oracle/call :cloud-plan 'is-cmd-dial? ["dial"])))
    (is (= "prod.edn" (oracle/call :cloud-plan 'flag-cloud-value ["--cloud=prod.edn"])))
    (is (= (ir/execute c 'cmd-plan [])
           (oracle/call :cloud-plan 'cmd-plan [])))
    (is (= (ir/execute c 'command-token ["bootstrap"])
           (oracle/call :cloud-plan 'command-token ["bootstrap"])))
    (is (= (oracle/call :cloud-plan 'command-token ["bootstrap"])
           (cplan/command-token "bootstrap")))
    (is (= "bootstrap" (cplan/command-token "bootstrap")))
    (is (= (ir/execute c 'flag-cloud-prefix [])
           (oracle/call :cloud-plan 'flag-cloud-prefix [])))
    (is (= (oracle/call :cloud-plan 'flag-cloud-prefix []) cplan/flag-cloud-prefix))
    (is (= (ir/execute c 'default-command-token [])
           (oracle/call :cloud-plan 'default-command-token [])))
    (is (= (oracle/call :cloud-plan 'default-command-token [])
           cplan/default-command-token))
    (let [ma (oracle/record
              [:record :provision/multiaddr [[:ip :string] [:port :i64]]]
              {:ip "1.2.3.4" :port 4001})
          lp (oracle/record
              [:record :provision/bin-path [[:local-bin :string] [:bin :string]]]
              {:local-bin "/b" :bin "kotoba"})
          lp-x (oracle/record
                [:record :provision/bin-path [[:local-bin :string] [:bin :string]]]
                {:local-bin "/b" :bin "x"})
          rd (oracle/record
              [:record :provision/remote-dest [[:host :string] [:bin :string]]]
              {:host "h" :bin "kotoba"})
          kv (oracle/record
              [:record :provision/label-kv [[:k :string] [:v :string]]]
              {:k "k" :v "v"})
          pe (oracle/record
              [:record :provision/peer-entry
               [[:peer-id :string] [:multiaddr :string]]]
              {:peer-id "p" :multiaddr "/ip4/1.1.1.1/udp/1/quic-v1"})
          pe-m (oracle/record
                [:record :provision/peer-entry
                 [[:peer-id :string] [:multiaddr :string]]]
                {:peer-id "p" :multiaddr "m"})]
      (is (= (ir/execute pr 'multiaddr [ma])
             (oracle/call :provision-plan 'multiaddr [ma])))
      (is (= (ir/execute pr 'local-bin-path [lp])
             (oracle/call :provision-plan 'local-bin-path [lp])))
      (is (= (ir/execute pr 'remote-bin-dest [rd])
             (oracle/call :provision-plan 'remote-bin-dest [rd])))
      (is (= (ir/execute pr 'label-kv [kv])
             (oracle/call :provision-plan 'label-kv [kv])))
      (is (= (oracle/call :provision-plan 'local-bin-path [lp-x])
             (pplan/local-bin-path "/b" "x")))
      (is (= (ir/execute pr 'peer-entry [pe])
             (oracle/call :provision-plan 'peer-entry [pe])))
      (is (= (oracle/call :provision-plan 'peer-entry [pe-m])
             (pplan/peer-entry "p" "m"))))
    (is (= (ir/execute pr 'multiaddr-ip4-prefix [])
           (oracle/call :provision-plan 'multiaddr-ip4-prefix [])))
    (is (= (oracle/call :provision-plan 'multiaddr-ip4-prefix [])
           pplan/multiaddr-ip4-prefix))
    (is (= (ir/execute pr 'multiaddr-udp-mid [])
           (oracle/call :provision-plan 'multiaddr-udp-mid [])))
    (is (= (oracle/call :provision-plan 'multiaddr-udp-mid [])
           pplan/multiaddr-udp-mid))
    (is (= (ir/execute pr 'multiaddr-quic-suffix [])
           (oracle/call :provision-plan 'multiaddr-quic-suffix [])))
    (is (= (oracle/call :provision-plan 'multiaddr-quic-suffix [])
           pplan/multiaddr-quic-suffix))
    (is (= (ir/execute pr 'launch-status-command [])
           (oracle/call :provision-plan 'launch-status-command [])))
    (is (= (ir/execute pr 'launchctl-print-prefix [])
           (oracle/call :provision-plan 'launchctl-print-prefix [])))
    (is (= (oracle/call :provision-plan 'launchctl-print-prefix [])
           pplan/launchctl-print-prefix))
    (is (= (ir/execute pr 'launchctl-bootout-prefix [])
           (oracle/call :provision-plan 'launchctl-bootout-prefix [])))
    (is (= (oracle/call :provision-plan 'launchctl-bootout-prefix [])
           pplan/launchctl-bootout-prefix))
    (is (= (ir/execute pr 'launchctl-bootstrap-prefix [])
           (oracle/call :provision-plan 'launchctl-bootstrap-prefix [])))
    (is (= (oracle/call :provision-plan 'launchctl-bootstrap-prefix [])
           pplan/launchctl-bootstrap-prefix))
    (is (= (ir/execute pr 'launchctl-kickstart-prefix [])
           (oracle/call :provision-plan 'launchctl-kickstart-prefix [])))
    (is (= (oracle/call :provision-plan 'launchctl-kickstart-prefix [])
           pplan/launchctl-kickstart-prefix))
    (is (= (ir/execute pr 'launchd-daemons-dir [])
           (oracle/call :provision-plan 'launchd-daemons-dir [])))
    (is (= (oracle/call :provision-plan 'launchd-daemons-dir [])
           pplan/launchd-daemons-dir))
    (is (= (ir/execute pr 'plist-ext [])
           (oracle/call :provision-plan 'plist-ext [])))
    (is (= (oracle/call :provision-plan 'plist-ext []) pplan/plist-ext))
    (is (= (ir/execute pr 'watchdog-label [])
           (oracle/call :provision-plan 'watchdog-label [])))
    (is (= (ir/execute pr 'rsync-bin [])
           (oracle/call :provision-plan 'rsync-bin [])))
    (is (= (ir/execute pr 'tee-plist-prefix ["lab"])
           (oracle/call :provision-plan 'tee-plist-prefix ["lab"])))
    (is (= (oracle/call :provision-plan 'rsync-bin []) pplan/rsync-bin))
    (is (= (ir/execute pr 'peer-at-sep [])
           (oracle/call :provision-plan 'peer-at-sep [])))
    (is (= (ir/execute pr 'did-key-prefix [])
           (oracle/call :provision-plan 'did-key-prefix [])))
    (is (= (ir/execute pr 'peer-id-body-prefix [])
           (oracle/call :provision-plan 'peer-id-body-prefix [])))
    (is (= (ir/execute pr 'peer-id-body-pattern [])
           (oracle/call :provision-plan 'peer-id-body-pattern [])))
    (is (= (ir/execute pr 'peer-id-did-pattern [])
           (oracle/call :provision-plan 'peer-id-did-pattern [])))
    (is (= (ir/execute pr 'did-peer-id ["12D3x"])
           (oracle/call :provision-plan 'did-peer-id ["12D3x"])))
    (is (= (oracle/call :provision-plan 'did-peer-id ["12D3x"])
           (pplan/did-peer-id "12D3x")))
    (is (= (ir/execute pr 'peer-id-log-command [])
           (oracle/call :provision-plan 'peer-id-log-command [])))
    (is (= (oracle/call :provision-plan 'peer-id-log-command [])
           (pplan/peer-id-log-command)))
    (is (= (ir/execute pr 'home-bin-suffix [])
           (oracle/call :provision-plan 'home-bin-suffix [])))
    (is (= (ir/execute pr 'home-bin-path ["/h"])
           (oracle/call :provision-plan 'home-bin-path ["/h"])))
    (is (= (oracle/call :provision-plan 'home-bin-path ["/h"])
           (pplan/home-bin-path "/h")))
    (is (= (ir/execute pr 'label-join-sep [])
           (oracle/call :provision-plan 'label-join-sep [])))
    (is (= (ir/execute pr 'roles-join-sep [])
           (oracle/call :provision-plan 'roles-join-sep [])))
    (is (= (oracle/call :provision-plan 'label-join-sep [])
           pplan/label-join-sep))
    (is (= (ir/execute pr 'plist-ph-user [])
           (oracle/call :provision-plan 'plist-ph-user [])))
    (is (= (ir/execute pr 'plist-ph-bin [])
           (oracle/call :provision-plan 'plist-ph-bin [])))
    (is (= (ir/execute pr 'plist-ph-webrtc [])
           (oracle/call :provision-plan 'plist-ph-webrtc [])))
    (is (= (oracle/call :provision-plan 'plist-ph-user []) pplan/plist-ph-user))
    (is (= (oracle/call :provision-plan 'plist-ph-bootstrap [])
           pplan/plist-ph-bootstrap))
    (let [ja (oracle/record
              [:record :provision/join
               [[:acc :string] [:sep :string] [:next :string]]]
              {:acc "" :sep "," :next "a"})
          ba0 (oracle/record
               [:record :provision/bootstrap [[:acc :string] [:entry :string]]]
               {:acc "" :entry "p@/m"})
          ba1 (oracle/record
               [:record :provision/bootstrap [[:acc :string] [:entry :string]]]
               {:acc "p@/m" :entry "q@/n"})
          la (oracle/record
              [:record :provision/labels [[:acc :string] [:pair :string]]]
              {:acc "zone=jp" :pair "role=c"})
          ra (oracle/record
              [:record :provision/roles [[:acc :string] [:role :string]]]
              {:acc "compute" :role "pin"})
          plr (oracle/record
               [:record :provision/plist-replace
                [[:tmpl :string] [:ph :string] [:val :string]]]
               {:tmpl "x{{U}}y" :ph "{{U}}" :val "ops"})
          ws (oracle/record
              [:record :provision/write-plist
               [[:label :string] [:body :string]]]
              {:label "lab" :body "<b/>"})]
      (is (= (ir/execute pr 'join-append [ja])
             (oracle/call :provision-plan 'join-append [ja])))
      (is (= (ir/execute pr 'bootstrap-append [ba0])
             (oracle/call :provision-plan 'bootstrap-append [ba0])))
      (is (= (ir/execute pr 'bootstrap-append [ba1])
             (oracle/call :provision-plan 'bootstrap-append [ba1])))
      (is (= (oracle/call :provision-plan 'bootstrap-append [ba0])
             (pplan/bootstrap-append "" "p@/m")))
      (is (= (oracle/call :provision-plan 'bootstrap-append [ba1])
             (pplan/bootstrap-append "p@/m" "q@/n")))
      (is (= "p@/m,q@/n" (pplan/bootstrap-append "p@/m" "q@/n")))
      (is (= (ir/execute pr 'labels-append [la])
             (oracle/call :provision-plan 'labels-append [la])))
      (is (= (oracle/call :provision-plan 'labels-append [la])
             (pplan/labels-append "zone=jp" "role=c")))
      (is (= (ir/execute pr 'roles-append [ra])
             (oracle/call :provision-plan 'roles-append [ra])))
      (is (= (oracle/call :provision-plan 'roles-append [ra])
             (pplan/roles-append "compute" "pin")))
      (is (= (ir/execute pr 'plist-replace [plr])
             (oracle/call :provision-plan 'plist-replace [plr])))
      (is (= (oracle/call :provision-plan 'plist-replace [plr])
             (pplan/plist-replace "x{{U}}y" "{{U}}" "ops")))
      (is (= "xopsy" (pplan/plist-replace "x{{U}}y" "{{U}}" "ops")))
      (is (= (ir/execute pr 'write-plist-shell [ws])
             (oracle/call :provision-plan 'write-plist-shell [ws]))))
    (is (= (ir/execute pr 'peer-id-from-log ["did:key:12D3KooWPeerId123"])
           (oracle/call :provision-plan 'peer-id-from-log ["did:key:12D3KooWPeerId123"])))
    (is (= "12D3KooWPeerId123"
           (oracle/call :provision-plan 'peer-id-from-log ["did:key:12D3KooWPeerId123"])))
    (is (= "" (oracle/call :provision-plan 'peer-id-from-log ["did:key:zOther"])))
    (is (= (oracle/call :provision-plan 'peer-id-from-log ["did:key:12D3abc"])
           (pplan/peer-id-from-log "did:key:12D3abc")))
    (let [ws-cmd (oracle/record
                  [:record :provision/write-plist
                   [[:label :string] [:body :string]]]
                  {:label pplan/plist-label :body "<b/>"})]
      (is (= (oracle/call :provision-plan 'write-plist-shell [ws-cmd])
             (pplan/write-plist-command "<b/>"))))))

(deftest product-shell-overlay-driver-runtime-uses-oracle
  (testing "driver endpoint-kind + dial-result + option-name"
    (is (= :quic (odriver/endpoint-kind "quic://asher:4001")))
    (is (= :webrtc (odriver/endpoint-kind "webrtc://h:1")))
    (is (= :webtransport (odriver/endpoint-kind "https://x/.well-known")))
    (is (= :relay (odriver/endpoint-kind "relay://jp/n")))
    (is (= :unknown (odriver/endpoint-kind "ftp://x")))
    (is (= :overlay (odriver/keyword-option "--overlay")))
    (is (= {:ok? false :reason :unknown-command :command :listen}
           (odriver/dial-result {:command :listen})))
    (is (= :missing-options
           (:reason (odriver/dial-result {:command :dial :overlay "o"}))))
    (let [r (odriver/dial-result
             {:command :dial
              :overlay "bafyOverlay" :node "bafyNode" :name "asher"
              :from "operator" :to "fleet" :capability "ssh"
              :direct "quic://asher:4001" :transport "quic"})]
      (is (true? (:ok? r)))
      (is (= :quic (get-in r [:session :direct :kind]))))
    (is (= "quic://" odriver/scheme-quic))
    (is (= "webrtc://" odriver/scheme-webrtc))
    (is (= "https://" odriver/scheme-https))
    (is (= "relay://" odriver/scheme-relay))
    (is (= "quic" odriver/kind-quic))
    (is (= "webtransport" odriver/kind-webtransport))
    (is (= "unknown" odriver/kind-unknown))
    (is (= "--" odriver/flag-dash-prefix))
    (is (= "dial" odriver/cmd-dial))
    (is (= "ok" odriver/reason-ok))
    (is (= "unknown-command" odriver/reason-unknown-command))
    (is (= "missing-options" odriver/reason-missing-options)))
  (testing "runtime ports + adapters + scheme-host"
    (is (= 4701 ort/default-relay-port))
    (is (= 443 ort/default-web-port))
    (is (= 4001 ort/default-quic-port))
    (is (= 4001 (get ort/default-port-by-kind :quic)))
    (is (= 4701 (get ort/default-port-by-kind :relay)))
    (is (true? (ort/known-adapter? "murakumo.runtime.quic")))
    (is (false? (ort/known-adapter? "nope")))
    (is (= :quic (:kind (ort/adapter "murakumo.runtime.quic"))))
    (is (= :relay-runtime (:kind (ort/adapter "murakumo.runtime.relay"))))
    (is (= "asher" (ort/scheme-host "quic://asher:4001")))
    (is (= "jp" (ort/scheme-host "relay://jp/bafy")))
    (is (= "jp-tyo-1.murakumo.cloud"
           (ort/scheme-host "relay://jp-tyo-1.murakumo.cloud")))
    (is (= "jp-tyo-1.murakumo.cloud"
           (:host (ort/relay-url-parts "relay://jp-tyo-1.murakumo.cloud"))))
    (is (= "quic://" ort/scheme-quic))
    (is (= "relay://" ort/scheme-relay))
    (is (= "webtransport://" ort/scheme-webtransport))
    (is (= "other" ort/kind-other))
    (is (= "murakumo.runtime.quic" ort/adapter-quic))
    (is (= "murakumo.runtime.relay" ort/adapter-relay))
    (is (= "relay-runtime" ort/adapter-kind-relay-runtime))
    (is (true? (ort/known-adapter? ort/adapter-quic)))
    (is (= :quic (:kind (ort/adapter ort/adapter-quic))))))

(deftest overlay-driver-runtime-oracle-call-matches-live
  (let [d (:kir (compiler/compile-source (slurp "kotoba/overlay_driver_core.kotoba")
                                         :wasm32-kotoba-v1 {}))
        r (:kir (compiler/compile-source (slurp "kotoba/overlay_runtime_core.kotoba")
                                         :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute d 'endpoint-kind ["quic://a:1"])
           (oracle/call :overlay-driver 'endpoint-kind ["quic://a:1"])))
    (is (= (ir/execute d 'option-name ["--overlay"])
           (oracle/call :overlay-driver 'option-name ["--overlay"])))
    (let [dr (oracle/record [:record :driver/dial-reason
                             [[:is-dial :bool] [:missing-n :i64]]]
                            {:is-dial true :missing-n 0})]
      (is (= (ir/execute d 'dial-ok-reason [dr])
             (oracle/call :overlay-driver 'dial-ok-reason [dr]))))
    (is (= (ir/execute d 'blank? [""])
           (oracle/call :overlay-driver 'blank? [""])))
    (is (contains? #{true 1} (oracle/call :overlay-driver 'blank? [""])))
    (is (= (ir/execute d 'scheme-quic [])
           (oracle/call :overlay-driver 'scheme-quic [])))
    (is (= (oracle/call :overlay-driver 'scheme-quic []) odriver/scheme-quic))
    (is (= (ir/execute d 'kind-webtransport [])
           (oracle/call :overlay-driver 'kind-webtransport [])))
    (is (= (oracle/call :overlay-driver 'kind-webtransport [])
           odriver/kind-webtransport))
    (is (= (ir/execute d 'cmd-dial [])
           (oracle/call :overlay-driver 'cmd-dial [])))
    (is (= (oracle/call :overlay-driver 'cmd-dial []) odriver/cmd-dial))
    (is (= (ir/execute d 'reason-missing-options [])
           (oracle/call :overlay-driver 'reason-missing-options [])))
    (is (= (oracle/call :overlay-driver 'reason-missing-options [])
           odriver/reason-missing-options))
    (is (= (ir/execute r 'default-relay-port [])
           (oracle/call :overlay-runtime 'default-relay-port [])))
    (is (= (ir/execute r 'known-adapter? ["murakumo.runtime.quic"])
           (oracle/call :overlay-runtime 'known-adapter? ["murakumo.runtime.quic"])))
    (is (= (ir/execute r 'adapter-kind ["murakumo.runtime.relay"])
           (oracle/call :overlay-runtime 'adapter-kind ["murakumo.runtime.relay"])))
    (is (= (ir/execute r 'scheme-prefix-host ["quic://asher:4001"])
           (oracle/call :overlay-runtime 'scheme-prefix-host ["quic://asher:4001"])))
    (is (= (ir/execute r 'scheme-quic [])
           (oracle/call :overlay-runtime 'scheme-quic [])))
    (is (= (oracle/call :overlay-runtime 'scheme-quic []) ort/scheme-quic))
    (is (= (ir/execute r 'adapter-quic [])
           (oracle/call :overlay-runtime 'adapter-quic [])))
    (is (= (oracle/call :overlay-runtime 'adapter-quic []) ort/adapter-quic))
    (is (= (ir/execute r 'kind-other [])
           (oracle/call :overlay-runtime 'kind-other [])))
    (is (= (oracle/call :overlay-runtime 'kind-other []) ort/kind-other))
    (is (= (ir/execute r 'adapter-kind-relay-runtime [])
           (oracle/call :overlay-runtime 'adapter-kind-relay-runtime [])))
    (is (= (oracle/call :overlay-runtime 'adapter-kind-relay-runtime [])
           ort/adapter-kind-relay-runtime))))
