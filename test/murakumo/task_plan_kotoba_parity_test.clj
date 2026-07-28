;; W6 pure-planner oracle: murakumo.task.plan slots/failed?/defaults
;; vs kotoba/task_plan_core.kotoba.

(ns murakumo.task-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.task.plan :as plan]))

(def port-source (slurp "kotoba/task_plan_core.kotoba"))

(def export-prefix
  "default-max-slots default-max-attempts default-timeout-ms slots failed? can-retry?")

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- opt-i64 [v]
  (if (some? v) (long v) -1))

(defn- slots-call [node opts]
  (let [merged (merge plan/default-opts opts)
        budget (if (contains? (or (:slots-by-node opts) {}) (:name node))
                 (long (get (:slots-by-node opts) (:name node)))
                 -1)
        node-slots (opt-i64 (:slots node))
        slots-per (opt-i64 (:slots-per-node merged))
        max-slots (long (or (:max-slots merged) 8))
        cores (opt-i64 (:cores node))]
    (str "(slots " budget " " node-slots " " slots-per " " max-slots " " cores ")")))

(deftest defaults-match-task-plan-cljc
  (let [actual (compile-i64-cases
                {"ms" "(default-max-slots)"
                 "ma" "(default-max-attempts)"
                 "to" "(default-timeout-ms)"})]
    (is (= (:max-slots plan/default-opts) (get actual "ms")))
    (is (= (:max-attempts plan/default-opts) (get actual "ma")))
    (is (= (:timeout-ms plan/default-opts) (get actual "to")))))

(deftest slots-matches-task-plan-cljc
  (let [corpus [[{:cores 8} {}]
                [{:cores 16} {}]
                [{:cores 16} {:max-slots 32}]
                [{:cores 16} {:slots-per-node 2}]
                [{} {}]
                [{:name "a" :cores 8} {:slots-by-node {"a" 3}}]
                [{:name "a" :cores 8} {:slots-by-node {"b" 3}}]
                [{:slots 4 :cores 16} {}]
                [{:cores 1} {:max-slots 8}]]
        cases (into {} (map-indexed
                        (fn [i [n o]] [(str "sl_" i) (slots-call n o)])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [n o]] (map-indexed vector corpus)]
      (testing (pr-str [n o])
        (is (= (plan/slots n o)
               (get actual (str "sl_" i))))))))

(deftest failed-matches-task-plan-cljc
  (let [corpus [{:exit 1}
                {:exit nil :timeout? true}
                {:error "spawn ENOENT"}
                {:exit 0}
                {:exit 255}
                {:exit 0 :timeout? true}
                {:exit 0 :error "x"}
                {}]
        call (fn [{:keys [exit timeout? error]}]
               (let [present (if (some? exit) 1 0)
                     code (long (or exit 0))
                     to (if timeout? 1 0)
                     err (if (some? error) 1 0)]
                 (str "(failed? " present " " code " " to " " err ")")))
        cases (into {} (map-indexed (fn [i r] [(str "f_" i) (call r)]) corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i r] (map-indexed vector corpus)]
      (testing (pr-str r)
        (is (= (if (plan/failed? r) 1 0)
               (get actual (str "f_" i))))))))

(deftest can-retry-matches-retry-tasks-bound
  (let [corpus [[1 2] [2 2] [1 1] [3 5] [0 2]]
        cases (into {} (map-indexed
                        (fn [i [a m]]
                          [(str "r_" i) (str "(can-retry? " a " " m ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [a m]] (map-indexed vector corpus)]
      (testing (pr-str [a m])
        (is (= (if (< a m) 1 0)
               (get actual (str "r_" i))))))))
