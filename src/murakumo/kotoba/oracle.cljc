;; murakumo.kotoba.oracle — product-shell loader for precompiled pure-planner KIR.
;;
;; Authority dual-source pattern (W6 product-shell cutover):
;;   1. SSoT source:  kotoba/*_core.kotoba
;;   2. Ship artifact: resources/murakumo/oracle/*.kir.edn  (precompiled KIR)
;;   3. Host public API delegates here instead of re-implementing pure truth
;;
;; Catalog is the full product-shell set (all kotoba/*_core.kotoba artifacts).
;; Hosts may wire incrementally; unregistered hosts still reimplement pure.
;;
;; See docs/adr/ADR-260728-w6-product-shell-oracle-authority.md
;;      docs/adr/ADR-260728-w6-bulk-product-shell-catalog.md

(ns murakumo.kotoba.oracle
  "Load precompiled kotoba KIR pure-planner artifacts and execute exports.
  Kotoba source is the authority; this ns is the product-shell call path."
  (:require [clojure.edn :as edn]
            [kotoba.kir :as ir]
            #?(:clj [clojure.java.io :as io])))

(def ^:private catalog
  "Logical oracle id → classpath resource path under resources/."
  {;; high-traffic verticals (fully host-wired)
   :kekkai-gate "murakumo/oracle/kekkai_gate_core.kir.edn"
   :token "murakumo/oracle/token_core.kir.edn"
   :report-core "murakumo/oracle/report_core.kir.edn"
   :infer-plan "murakumo/oracle/infer_plan_core.kir.edn"
   :dash-state "murakumo/oracle/dash_state_core.kir.edn"
   :infer-schedule "murakumo/oracle/infer_schedule_core.kir.edn"
   :task-plan "murakumo/oracle/task_plan_core.kir.edn"
   :infer-engine "murakumo/oracle/infer_engine_core.kir.edn"
   :secret "murakumo/oracle/secret_core.kir.edn"
   :overlay-crypto "murakumo/oracle/overlay_crypto_core.kir.edn"
   ;; bulk catalog (artifacts shipped; host wiring incremental)
   :cloud-plan "murakumo/oracle/cloud_plan_core.kir.edn"
   :component-authority "murakumo/oracle/component_authority_core.kir.edn"
   :config "murakumo/oracle/config_core.kir.edn"
   :connect "murakumo/oracle/connect_core.kir.edn"
   :deploy-plan "murakumo/oracle/deploy_plan_core.kir.edn"
   :fleet-inventory "murakumo/oracle/fleet_inventory_core.kir.edn"
   :identity "murakumo/oracle/identity_core.kir.edn"
   :infer-credits "murakumo/oracle/infer_credits_core.kir.edn"
   :infer-gc "murakumo/oracle/infer_gc_core.kir.edn"
   :infer-join "murakumo/oracle/infer_join_core.kir.edn"
   :infer-moe "murakumo/oracle/infer_moe_core.kir.edn"
   :infer-rebalance "murakumo/oracle/infer_rebalance_core.kir.edn"
   :infer-relay "murakumo/oracle/infer_relay_core.kir.edn"
   :overlay-driver "murakumo/oracle/overlay_driver_core.kir.edn"
   :overlay-keyring "murakumo/oracle/overlay_keyring_core.kir.edn"
   :overlay-peer "murakumo/oracle/overlay_peer_core.kir.edn"
   :overlay-runtime "murakumo/oracle/overlay_runtime_core.kir.edn"
   :overlay-stream "murakumo/oracle/overlay_stream_core.kir.edn"
   :persist "murakumo/oracle/persist_core.kir.edn"
   :provision-plan "murakumo/oracle/provision_plan_core.kir.edn"
   :reconcile-plan "murakumo/oracle/reconcile_plan_core.kir.edn"
   :tunnel "murakumo/oracle/tunnel_core.kir.edn"})

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


(defn option-of
  "Host nil → option none; non-nil → option some (Product Value ABI v1).
  `type` examples: [:option :string], [:option :i64]."
  [type value]
  (if (nil? value)
    [type false]
    [type true value]))

(defn option-string
  "Optional string: nil → none; otherwise some (including empty string)."
  [s]
  (option-of [:option :string] (when (some? s) (str s))))

(defn option-i64
  "Optional i64: nil → none; otherwise some long."
  [n]
  (if (nil? n)
    [[:option :i64] false]
    [[:option :i64] true (long n)]))

(defn option-some?
  "True when opt is a some-tagged Product Value ABI option."
  [opt]
  (boolean (and (vector? opt) (true? (second opt)))))

(defn option-value
  "Payload of a some option, or nil if none/malformed."
  [opt]
  (when (option-some? opt)
    (nth opt 2)))

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

(defn catalog-count
  "Number of shipped product-shell oracle artifacts."
  []
  (count catalog))
