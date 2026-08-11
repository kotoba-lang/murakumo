;; Every shipped decision core compiles for both native ISAs.
;;
;; ── why this test exists ──
;;
;; compiler ADR-0221 recorded 33/33 for these modules on 2026-08-06 and was
;; correct when it was written. kotoba-native then added a record-boundary
;; check that read the WHOLE function body instead of tail position, and six
;; of the thirty-three stopped qualifying -- `infer_credits`, `infer_plan`,
;; `infer_rebalance`, `infer_schedule`, `reconcile_plan`, `task_plan`, every
;; one of them on `:result-schema-mismatch` and nothing else.
;;
;; Nobody noticed for five days. The parity tests stayed green the entire time,
;; because they run the cores through the KIR interpreter on the wasm32 target
;; -- semantics were never in question, ADMISSION was, and nothing in this
;; repository asked the native backends anything. The figure was re-verified
;; only when someone went looking, and the fix (kotoba-native ADR-0032) came
;; with a test in THAT repository, which protects the rule but not this
;; repository's standing in it.
;;
;; So: ask the backends directly, here, on every core, every run.
;;
;; ── the list is read from disk on purpose ──
;;
;; A hardcoded list of module names would have to be edited to cover a new
;; core, and the edit is exactly what gets forgotten -- a core added without it
;; would be unqualified and invisible in the same breath. `kotoba/*.kotoba` is
;; the shipped set by definition, so a new core is covered by existing.

(ns murakumo.kotoba-native-qualification-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]))

(def ^:private native-targets
  ;; The generic ISA profiles. The OS-suffixed ones (`:aarch64-macos-…`) resolve
  ;; to the same backend, so they would not ask a different question.
  [:x86_64-kotoba-v1 :aarch64-kotoba-v1])

(defn- core-sources []
  (->> (.listFiles (io/file "kotoba"))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".kotoba"))
       sort
       vec))

(defn- qualify [module target]
  (try
    (compiler/compile-source (slurp (io/file "kotoba" module)) target {})
    nil
    (catch Exception e
      ;; Report the reason, not just the name. "six modules fail" sends the
      ;; next reader back to the compiler to find out why; ":result-schema-
      ;; mismatch on all six" is already the diagnosis.
      {:module module
       :target target
       :problem (or (:problem (ex-data e)) (:error (ex-data e)) :unknown)
       :message (ex-message e)})))

(deftest there-is-at-least-one-core-to-check
  ;; Without this, a broken path or a moved directory turns the sweep below
  ;; into a vacuous pass -- zero modules, zero failures, green.
  (is (<= 35 (count (core-sources)))
      "kotoba/ holds the shipped decision cores"))

(deftest every-shipped-core-qualifies-on-both-native-isas
  (let [modules (core-sources)]
    (doseq [target native-targets]
      (testing (name target)
        (let [failures (keep #(qualify % target) modules)]
          (is (empty? failures)
              (str (count failures) " of " (count modules)
                   " cores do not qualify for " target ":\n"
                   (str/join "\n" (for [f failures]
                                    (str "  " (:module f) " — " (:problem f)
                                         " — " (:message f)))))))))))
