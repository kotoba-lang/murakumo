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
  (str "default-max-slots default-max-attempts default-timeout-ms slots failed? can-retry? "
       "task-eligible? fill-milli better-fill? better-task-score? better-mem? "
       "wave-of slot-of load-after-assign "
       "digit-char nat-str i64-str pad4 task-id "
       "pick-task-idx-2 assign-task-step-2 attempt-next exclude-append-marker "
       "pick-task-fold-step load-inc-if challenger-wins? "
       "assign-task-pick-3 apply-task-pick-3 assign-task-step-3 "
       "nearest-rank-idx summary-retried speedup-milli max2 min2 clamp-nonneg"))


(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

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

(defn- opt-i64-form [n]
  (if (nil? n)
    "(option-none-of [:option :i64])"
    (str "(option-some-of [:option :i64] " (long n) ")")))

(defn- opt-str-form [s]
  (if (nil? s)
    "(option-none-of [:option :string])"
    (str "(option-some-of [:option :string] "
         \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"
         ")")))

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
               (let [to (if timeout? 1 0)]
                 (str "(failed? " (opt-i64-form exit) " " to " "
                      (opt-str-form error) ")")))
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

(defn- task-flags
  "1 online | 2 labels | 4 roles | 8 not-excluded | 16 allowlist"
  [node task]
  (let [online (if (false? (:online? node true)) 0 1)
        labels (if (every? (fn [[k v]] (= v (get (:labels node) k)))
                           (or (get-in task [:placement :labels]) {}))
                 1 0)
        roles (if (every? (set (or (:roles node) #{}))
                          (or (get-in task [:placement :roles]) []))
                1 0)
        not-ex (if (contains? (set (or (:exclude-nodes task) [])) (:name node)) 0 1)
        allow (if (or (empty? (or (:nodes task) []))
                      (contains? (set (:nodes task)) (:name node)))
                1 0)]
    (+ online (* 2 labels) (* 4 roles) (* 8 not-ex) (* 16 allow))))

(deftest task-eligible-matches-cljc
  (let [nodes [{:name "a" :online? true :labels {:tier "gpu"} :roles #{:worker}
                :mem-bytes (* 16 1024 1024 1024)}
               {:name "b" :online? false :labels {:tier "gpu"} :roles #{:worker}
                :mem-bytes (* 16 1024 1024 1024)}
               {:name "c" :online? true :labels {:tier "cpu"} :roles #{:worker}
                :mem-bytes (* 16 1024 1024 1024)}
               {:name "d" :online? true :labels {:tier "gpu"} :roles #{:worker}
                :mem-bytes (* 1 1024 1024 1024)}]
        tasks [{:placement {:labels {:tier "gpu"} :roles [:worker]} :min-mem-bytes (* 8 1024 1024 1024)}
               {:placement {:labels {:tier "gpu"} :roles [:worker]} :min-mem-bytes (* 8 1024 1024 1024)
                :exclude-nodes ["a"]}
               {:placement {:labels {:tier "gpu"} :roles [:worker]} :min-mem-bytes 0
                :nodes ["d"]}]
        corpus (for [n nodes t tasks] [n t])
        cases (into {}
                    (map-indexed
                     (fn [i [n t]]
                       [(str "e_" i)
                        (str "(task-eligible? " (task-flags n t) " "
                             (long (or (:mem-bytes n) 0)) " "
                             (long (or (:min-mem-bytes t) 0)) ")")])
                     corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [n t]] (map-indexed vector corpus)]
      (testing (str (:name n) " " (pr-str t))
        (is (= (if (plan/eligible? n t) 1 0)
               (get actual (str "e_" i))))))))

(deftest wave-slot-and-score-helpers
  (let [actual (compile-i64-cases
                {"f0" "(fill-milli 0 4)"
                 "f1" "(fill-milli 2 4)"
                 "f2" "(fill-milli 4 4)"
                 "w0" "(wave-of 0 4)"
                 "w1" "(wave-of 5 4)"
                 "s0" "(slot-of 0 4)"
                 "s1" "(slot-of 5 4)"
                 "la" "(load-after-assign 3)"
                 "bf" "(better-fill? 250 500)"
                 "bs" "(better-task-score? 250 1 0 500 0)"
                 "tie" "(better-task-score? 250 1 0 250 1)"
                 "bm" "(better-mem? -100 -50 0 1)"
                 "bn" "(better-mem? -50 -50 0 1)"})]
    (is (= 0 (get actual "f0")))
    (is (= 500 (get actual "f1")))
    (is (= 1000 (get actual "f2")))
    (is (= 0 (get actual "w0")))
    (is (= 1 (get actual "w1")))
    (is (= 0 (get actual "s0")))
    (is (= 1 (get actual "s1")))
    (is (= 4 (get actual "la")))
    (is (= 1 (get actual "bf")))
    (is (= 1 (get actual "bs")))
    (is (= 2 (get actual "tie")))
    (is (= 1 (get actual "bm")))
    (is (= 1 (get actual "bn")))))

(deftest task-id-and-expand-parity
  (let [ids (compile-string-cases
             (into {} (map (fn [i] [(str "id_" i) (str "(task-id " i ")")])
                           [0 1 9 10 99 100 999 1000 12345])))
        attempts (compile-i64-cases
                  {"a1" "(attempt-next 1)"
                   "a2" "(attempt-next 2)"})]
    (doseq [i [0 1 9 10 99 100 999 1000 12345]]
      (let [want (:id (nth (plan/expand (inc i) {}) i))]
        (is (= want (get ids (str "id_" i))) (str "i=" i))))
    (is (= 2 (get attempts "a1")))
    (is (= 3 (get attempts "a2")))))

(deftest assign-task-step-2-matches-assign-1
  (let [;; two identical eligible nodes, empty load → pick lower index 0
        fill-pack (+ 0 (* 0 65536))
        s0 "(assign-task-step-2 0 0 1 1 0)"
        ;; after load0=1, fill would be higher on 0 if slots equal — use fill pack
        ;; score-pack fill0=500 fill1=0 → prefer 1
        s1 (str "(assign-task-step-2 1 0 1 1 " (+ 500 (* 0 65536)) ")")
        actual (compile-i64-cases
                {"s0" s0
                 "s1" s1
                 "p0" "(pick-task-idx-2 1 1 0 0 0)"
                 "p1" (str "(pick-task-idx-2 1 1 500 0 " (+ 1 (* 0 65536)) ")")
                 "pn" "(pick-task-idx-2 0 0 0 0 0)"})]
    (let [r0 (get actual "s0")
          code (mod r0 65536)
          n0 (mod (quot r0 65536) 65536)
          n1 (quot r0 (* 65536 65536))]
      (is (= 1 code))
      (is (= 1 n0))
      (is (= 0 n1)))
    (let [r1 (get actual "s1")
          code (mod r1 65536)]
      (is (= 2 code) "higher fill on node0 → pick node1"))
    (is (= 0 (get actual "p0")))
    (is (= 1 (get actual "p1")) "fill0=500 > fill1=0 → pick node1")
    (is (= -1 (get actual "pn")))))

(deftest assign-task-step-3-and-summary
  (let [B 65536
        pack3 (fn [a b c] (+ a (* b B) (* c B B)))
        ;; three equal empty nodes
        ok (pack3 1 1 1)
        fill (pack3 0 0 0)
        load0 (pack3 0 0 0)
        actual (compile-i64-cases
                {"p3" (str "(assign-task-pick-3 " ok " " fill " " load0 ")")
                 "s3" (str "(assign-task-step-3 " load0 " " ok " " fill ")")
                 "fold0" (str "(pick-task-fold-step "
                              (opt-i64-form nil) " 1 0)")
                 "fold1" (str "(pick-task-fold-step "
                              (opt-i64-form 1) " 1 1)")
                 "fold2" (str "(pick-task-fold-step "
                              (opt-i64-form 1) " 1 0)")
                 "foldn" (str "(pick-task-fold-step "
                              (opt-i64-form nil) " 0 0)")
                 "nr50" "(nearest-rank-idx 10 500)"
                 "nr95" "(nearest-rank-idx 10 950)"
                 "nr0" "(nearest-rank-idx 0 500)"
                 "ret" "(summary-retried 5 3)"
                 "sp" "(speedup-milli 4000 1000)"
                 "sp0" "(speedup-milli 0 1000)"
                 "cw" "(challenger-wins? 500 1 0 0)"
                 "cw2" "(challenger-wins? 0 0 500 1)"})]
    (is (= 0 (get actual "p3")) "tie → lower index 0")
    (let [r (get actual "s3")
          code (mod r 4)
          nload (quot r 4)
          n0 (mod nload B)
          n1 (mod (quot nload B) B)
          n2 (quot nload (* B B))]
      (is (= 1 code))
      (is (= 1 n0))
      (is (= 0 n1))
      (is (= 0 n2)))
    (is (= 1 (get actual "fold0")))
    (is (= 1 (get actual "fold1")))
    (is (= 2 (get actual "fold2")))
    (is (= 0 (get actual "foldn")))
    (is (= 5 (get actual "nr50")))
    (is (= 9 (get actual "nr95")))
    (is (= -1 (get actual "nr0")))
    (is (= 2 (get actual "ret")))
    (is (= 4000 (get actual "sp")))
    (is (= 0 (get actual "sp0")))
    (is (= 1 (get actual "cw")))
    (is (= 0 (get actual "cw2")))
    (testing "cljc percentile nearest-rank"
      (let [xs (range 10)]
        (is (= 5 (plan/percentile xs 0.5)))
        (is (= (nth (vec (sort xs)) 9) (plan/percentile xs 0.95)))))))

