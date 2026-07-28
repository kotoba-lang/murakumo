;; murakumo.kotoba.oracle — product-shell loader for precompiled pure-planner KIR.
;;
;; Authority dual-source pattern (W6 product-shell cutover):
;;   1. SSoT source:  kotoba/*_core.kotoba
;;   2. Ship artifact: resources/murakumo/oracle/*.kir.edn  (precompiled KIR)
;;   3. Host public API delegates here instead of re-implementing pure truth
;;
;; Compile path is the existing kotoba.compiler.core → :kir map used by
;; parity tests; we do NOT invent a new runtime. Production only needs
;; kotoba-kir (ir/execute) + the checked-in EDN artifact — not the full
;; compiler (which remains :test-only).
;;
;; Regenerate artifacts:
;;   clojure -M:test -m murakumo.kotoba-oracle-gen   (or the parity drift test)
;;
;; See docs/adr/ADR-260728-w6-product-shell-oracle-authority.md

(ns murakumo.kotoba.oracle
  "Load precompiled kotoba KIR pure-planner artifacts and execute exports.
  Kotoba source is the authority; this ns is the product-shell call path."
  (:require [clojure.edn :as edn]
            [kotoba.kir :as ir]
            #?(:clj [clojure.java.io :as io])))

(def ^:private catalog
  "Logical oracle id → classpath resource path under resources/."
  {:kekkai-gate "murakumo/oracle/kekkai_gate_core.kir.edn"
   :token "murakumo/oracle/token_core.kir.edn"
   :report-core "murakumo/oracle/report_core.kir.edn"
   :infer-plan "murakumo/oracle/infer_plan_core.kir.edn"
   :dash-state "murakumo/oracle/dash_state_core.kir.edn"
   :infer-schedule "murakumo/oracle/infer_schedule_core.kir.edn"
   :task-plan "murakumo/oracle/task_plan_core.kir.edn"
   :infer-engine "murakumo/oracle/infer_engine_core.kir.edn"})

(def ^:private kir-cache
  "Atom map of oracle-id → loaded KIR document."
  (atom {}))

(defn- read-resource
  "Read a classpath resource as a string. Throws if missing."
  [path]
  #?(:clj
     (if-let [url (io/resource path)]
       (slurp url)
       (throw (ex-info "kotoba oracle KIR resource missing"
                       {:path path
                        :hint "regenerate via :test oracle-gen or parity drift check"})))
     :cljs
     (throw (ex-info "kotoba oracle resource load is JVM/bb-only in this slice"
                     {:path path}))))

(defn load-kir
  "Load (and cache) the precompiled KIR for `oracle-id` (keyword in catalog)."
  [oracle-id]
  (if-let [hit (get @kir-cache oracle-id)]
    hit
    (let [path (or (get catalog oracle-id)
                   (throw (ex-info "unknown kotoba oracle id"
                                   {:oracle-id oracle-id
                                    :known (keys catalog)})))
          kir (edn/read-string (read-resource path))]
      (swap! kir-cache assoc oracle-id kir)
      kir)))

(defn ready?
  "True when the oracle artifact is on the classpath and parseable."
  [oracle-id]
  (try
    (boolean (load-kir oracle-id))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn call
  "Execute a pure export on the precompiled oracle.

  `oracle-id`  — keyword in catalog (e.g. :kekkai-gate)
  `export`     — symbol matching a kotoba (:export …) name
  `args`       — vector of host values (strings / i64 longs) matching guest ABI"
  [oracle-id export args]
  (let [kir (load-kir oracle-id)
        fn-name (if (symbol? export) export (symbol (name export)))]
    (ir/execute kir fn-name (vec args))))

(defn catalog-ids
  "Known oracle ids shipped as product-shell artifacts."
  []
  (keys catalog))
