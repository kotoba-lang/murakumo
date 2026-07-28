;; murakumo.kotoba-oracle-gen — regenerate precompiled KIR product-shell artifacts.
;;
;; Run under :test (needs kotoba.compiler):
;;   clojure -M:test -m murakumo.kotoba-oracle-gen
;;
;; Writes resources/murakumo/oracle/<name>.kir.edn from kotoba/<name>.kotoba
;; using the same compile-source path as parity tests. No new runtime.

(ns murakumo.kotoba-oracle-gen
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [kotoba.compiler.core :as compiler])
  (:gen-class))

(def ^:private artifacts
  "Source relative to repo root → resource output path."
  [{"source" "kotoba/kekkai_gate_core.kotoba"
    "out" "resources/murakumo/oracle/kekkai_gate_core.kir.edn"}
   {"source" "kotoba/token_core.kotoba"
    "out" "resources/murakumo/oracle/token_core.kir.edn"}
   {"source" "kotoba/report_core.kotoba"
    "out" "resources/murakumo/oracle/report_core.kir.edn"}
   {"source" "kotoba/infer_plan_core.kotoba"
    "out" "resources/murakumo/oracle/infer_plan_core.kir.edn"}
   {"source" "kotoba/dash_state_core.kotoba"
    "out" "resources/murakumo/oracle/dash_state_core.kir.edn"}])

(defn compile-kir
  "Compile one .kotoba file to a KIR map (same path as parity tests)."
  [source-path]
  (let [src (slurp source-path)
        r (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (or (:kir r)
        (throw (ex-info "compile-source returned no :kir" {:source source-path})))))

(defn write-artifact!
  "Compile source and pretty-print KIR EDN to out-path. Returns out-path."
  [{:strs [source out]}]
  (let [kir (compile-kir source)
        f (io/file out)]
    (io/make-parents f)
    (spit f (with-out-str (pp/pprint kir)))
    out))

(defn regenerate-all!
  "Regenerate every product-shell oracle artifact. Returns written paths."
  []
  (mapv write-artifact! artifacts))

(defn -main [& _]
  (doseq [p (regenerate-all!)]
    (println "wrote" p))
  (System/exit 0))
