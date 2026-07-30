;; W6 pure-planner oracle: murakumo.infer.schedule eligible?/score
;; vs kotoba/infer_schedule_core.kotoba.

(ns murakumo.infer-schedule-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.schedule :as sched]))

(def port-source (slurp "kotoba/infer_schedule_core.kotoba"))

(def export-prefix (str "eligible? score-queue score-free better-score? holds-warm? "
                        "prefer-warm-then-score pick-idx-2-full pick-idx-3-tournament "
                        "queue-after-assign pick-code "
                        "better2-record assign-step-2 "
                        "assign-result-pick assign-result-q0 assign-result-q1 "
                        "better-from-queues triple-record triple-v0 triple-v1 triple-v2 "
                        "better3-record pick-code-3 "
                        "assign-pick-3 apply-pick-3 "
                        "assign-step-3 assign-step-3-code "
                        "assign-step-3-q0 assign-step-3-q1 assign-step-3-q2 better-pair "
                        "pick-fold-step queue-inc-if"))

(def GiB (* 1024 1024 1024))


(defn- opt-i64-form [n]
  (if (nil? n)
    "(option-none-of [:option :i64])"
    (str "(option-some-of [:option :i64] " (long n) ")")))

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

(def ^:private eligibility-literal
  "T5.3 + profile 5: guest eligibility record with :bool flags."
  (str "[:record :schedule/eligibility "
       "[[:has-engine :bool] [:has-checkpoint :bool] "
       "[:holds-checkpoint :bool] [:can-fetch :bool]]]"))

(defn- eligibility-record-literal
  "Set-membership as a bool record literal (profile 5)."
  [node model]
  (let [engine (:model/engine model)
        ckpt (:model/checkpoint model)
        engines (or (:engines node) #{})
        checkpoints (or (:checkpoints node) #{})
        has-engine (if (contains? engines engine) "true" "false")
        has-ckpt (if (nil? ckpt) "false" "true")
        holds (if (and ckpt (contains? checkpoints ckpt)) "true" "false")
        can-fetch (if (false? (:node/can-fetch? node)) "false" "true")]
    (str "(record-new " eligibility-literal " "
         has-engine " " has-ckpt " " holds " " can-fetch ")")))

(defn- eligible-call [node model]
  ;; Profile 5: eligible? is :bool; wrap as 0/1 so compile-i64-cases still works.
  (str "(if (eligible? " (eligibility-record-literal node model) " "
       (long (or (:free-bytes node) 0)) " "
       (long (or (:model/min-free-bytes model) 0)) ") 1 0)"))

(defn- node
  ([name] (node name {}))
  ([name {:keys [engines checkpoints free-bytes queue can-fetch?]
          :or {engines #{:comfyui}
               checkpoints #{}
               free-bytes (* 16 GiB)
               queue 0}}]
   (cond-> {:name name
            :engines engines
            :checkpoints checkpoints
            :free-bytes free-bytes
            :queue queue}
     (some? can-fetch?) (assoc :node/can-fetch? can-fetch?))))

(def sdxl {:model/engine :comfyui
           :model/checkpoint "animagine-xl-4.0.safetensors"
           :model/min-free-bytes (* 8 GiB)})

(def wan {:model/engine :comfyui
          :model/checkpoint "wan2.1.safetensors"
          :model/min-free-bytes (* 12 GiB)})

(def any-engine {:model/engine :comfyui
                 :model/min-free-bytes 0})

(deftest eligible-matches-schedule-cljc
  (let [corpus [[(node "a" {:checkpoints #{"animagine-xl-4.0.safetensors"}}) sdxl]
                [(node "a" {:engines #{:llamacpp}}) sdxl]
                [(node "a" {:free-bytes (* 4 GiB)}) wan]
                [(node "cold") sdxl]
                [(node "no-fetch" {:can-fetch? false}) sdxl]
                [(node "warm" {:checkpoints #{"animagine-xl-4.0.safetensors"}
                               :can-fetch? false}) sdxl]
                [(node "mem" {:free-bytes (* 20 GiB)
                              :checkpoints #{"wan2.1.safetensors"}}) wan]
                [(node "any") any-engine]]
        cases (into {} (map-indexed
                        (fn [i [n m]] [(str "e_" i) (eligible-call n m)])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [n m]] (map-indexed vector corpus)]
      (testing (str (:name n) " / " (or (:model/checkpoint m) :no-ckpt))
        (is (= (if (sched/eligible? n m) 1 0)
               (get actual (str "e_" i))))))))

(deftest score-keys-match-schedule-cljc
  (let [nodes [(node "a" {:queue 0 :free-bytes (* 16 GiB)})
               (node "b" {:queue 3 :free-bytes (* 8 GiB)})
               {:name "c" :queue nil :free-bytes nil}
               (node "d" {:queue 1 :free-bytes 0})]
        q-cases (into {} (map-indexed
                          (fn [i n]
                            [(str "q_" i)
                             (str "(score-queue " (long (or (:queue n) 0)) ")")])
                          nodes))
        f-cases (into {} (map-indexed
                          (fn [i n]
                            [(str "f_" i)
                             (str "(score-free " (long (or (:free-bytes n) 0)) ")")])
                          nodes))
        q-actual (compile-i64-cases q-cases)
        f-actual (compile-i64-cases f-cases)]
    (doseq [[i n] (map-indexed vector nodes)]
      (testing (:name n)
        (let [[q f] (sched/score n)]
          (is (= q (get q-actual (str "q_" i))))
          (is (= f (get f-actual (str "f_" i)))))))))

(deftest better-score-orders-like-sort-by
  (let [pairs [[[0 (- (* 16 GiB))] [1 (- (* 32 GiB))]]
               [[2 (- (* 1 GiB))] [2 (- (* 8 GiB))]]
               [[0 0] [0 0]]
               [[5 (- 100)] [4 (- 1)]]]
        cases (into {} (map-indexed
                        (fn [i [[q1 f1] [q2 f2]]]
                          [(str "b_" i)
                           (str "(if (better-score? " q1 " " f1 " " q2 " " f2 ") 1 0)")])
                        pairs))
        actual (compile-i64-cases cases)]
    (doseq [[i [a b]] (map-indexed vector pairs)]
      (testing (pr-str [a b])
        (let [expected (if (neg? (compare a b)) 1 0)]
          (is (= expected (get actual (str "b_" i)))))))))

(deftest pick-idx-2-full-matches-schedule-pick
  (let [GiB (* 1024 1024 1024)
        model {:model/engine :comfyui
               :model/checkpoint "c.safetensors"
               :model/min-free-bytes (* 8 GiB)}
        warm (fn [n m]
               (merge {:name n :engines #{:comfyui} :checkpoints #{"c.safetensors"}
                       :free-bytes (* 16 GiB) :queue 0} m))
        cold (fn [n m]
               (merge {:name n :engines #{:comfyui} :checkpoints #{}
                       :free-bytes (* 16 GiB) :queue 0} m))
        ;; cases: [nodes] expected-name-or-nil
        pairs [[[(warm "a" {:queue 2}) (cold "b" {:queue 0})] "a"]
               [[(cold "b" {:queue 0}) (warm "a" {:queue 2})] "a"]
               [[(warm "x" {:queue 1}) (warm "y" {:queue 0})] "y"]
               [[(cold "z" {:engines #{:mlx}})] nil]
               [[(cold "only" {})] "only"]]
        ;; build kotoba cases with host-projected ok/warm/better
        pack (fn [node]
               (let [engine (:model/engine model)
                     ckpt (:model/checkpoint model)
                     engines (or (:engines node) #{})
                     checkpoints (or (:checkpoints node) #{})
                     has-engine (if (contains? engines engine) 1 0)
                     has-ckpt (if (nil? ckpt) 0 1)
                     holds (if (and ckpt (contains? checkpoints ckpt)) 1 0)
                     can-fetch (if (false? (:node/can-fetch? node)) 0 1)]
                 (+ has-engine (* 2 has-ckpt) (* 4 holds) (* 8 can-fetch))))
        body2 (fn [n0 n1]
                (let [ok0 (if (sched/eligible? n0 model) "true" "false")
                      ok1 (if (sched/eligible? n1 model) "true" "false")
                      w0 (if (contains? (or (:checkpoints n0) #{}) (:model/checkpoint model)) 1 0)
                      w1 (if (contains? (or (:checkpoints n1) #{}) (:model/checkpoint model)) 1 0)
                      s0 (sched/score n0)
                      s1 (sched/score n1)
                      better (if (neg? (compare s0 s1)) "true" "false")
                      wb0 (if (= w0 1) "true" "false")
                      wb1 (if (= w1 1) "true" "false")]
                  (str "(pick-idx-2-full " ok0 " " ok1 " " wb0 " " wb1 " " better ")")))
        cases (into {}
                    (map-indexed
                     (fn [i [nodes _]]
                       (if (= 1 (count nodes))
                         [(str "p_" i)
                          (let [n0 (first nodes)
                                ok0 (if (sched/eligible? n0 model) "true" "false")]
                            (str "(pick-idx-2-full " ok0 " false false false false)"))]
                         [(str "p_" i) (body2 (nodes 0) (nodes 1))]))
                     pairs))
        actual (compile-i64-cases
                (merge cases
                       {"qa0" "(queue-after-assign 0 0)"
                        "qa1" "(queue-after-assign 0 1)"
                        "qa2" "(queue-after-assign 3 1)"
                        "t3" "(pick-idx-3-tournament 0 true true false true)"}))]
    (doseq [[i [nodes expect]] (map-indexed vector pairs)]
      (let [got (get actual (str "p_" i))
            ;; map idx to name
            name (cond (neg? got) nil
                       (= 1 (count nodes)) (when (not (neg? got)) (:name (first nodes)))
                       :else (:name (nth nodes got)))]
        (is (= expect name) (str "case " i " expect=" expect " got-idx=" got " name=" name))))
    (is (= 0 (get actual "qa0")))
    (is (= 1 (get actual "qa1")))
    (is (= 4 (get actual "qa2")))
    ;; champ 0 warm, node2 cold, better champ → stay 0
    (is (= 0 (get actual "t3")))))


(deftest assign-step-2-matches-schedule-assign
  (let [model {:model/engine :comfyui
               :model/checkpoint "c.safetensors"
               :model/min-free-bytes (* 8 GiB)}
        a {:name "a" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
           :free-bytes (* 16 GiB) :queue 0}
        b {:name "b" :engines #{:comfyui} :checkpoints #{}
           :free-bytes (* 16 GiB) :queue 0}
        jobs [{:model model} {:model model}]
        cljc (sched/assign [a b] jobs)
        f0 (:free-bytes a)
        f1 (:free-bytes b)
        ;; T5.3: better2-record + assign2 field projections (no pack3)
        b0 (str "(if (better-from-queues 0 " f0 " 0 " f1 ") 1 0)")
        s0 (str "(assign-step-2 0 0 true true (better2-record true false (better-from-queues 0 " f0 " 0 " f1 ")))")
        b1 (str "(if (better-from-queues 1 " f0 " 0 " f1 ") 1 0)")
        s1 (str "(assign-step-2 1 0 true true (better2-record true false (better-from-queues 1 " f0 " 0 " f1 ")))")
        actual (compile-i64-cases
                {"b0" b0
                 "s0c" (str "(assign-result-pick " s0 ")")
                 "s0q0" (str "(assign-result-q0 " s0 ")")
                 "s0q1" (str "(assign-result-q1 " s0 ")")
                 "s1c" (str "(assign-result-pick " s1 ")")
                 "s1q0" (str "(assign-result-q0 " s1 ")")
                 "s1q1" (str "(assign-result-q1 " s1 ")")
                 "pc" "(pick-code -1)"
                 "pc0" "(pick-code 0)"
                 "pc1" "(pick-code 1)"
                 "t0" "(triple-v0 (triple-record 3 4 0))"
                 "t1" "(triple-v1 (triple-record 3 4 0))"})]
    (is (= 0 (get actual "pc")))
    (is (= 1 (get actual "pc0")))
    (is (= 2 (get actual "pc1")))
    (is (= 3 (get actual "t0")))
    (is (= 4 (get actual "t1")))
    (is (= 1 (get actual "s0c")) "pick a (warm)")
    (is (= 1 (get actual "s0q0")))
    (is (= 0 (get actual "s0q1")))
    ;; warm preference still binds both jobs to a (cold b never enters warm set)
    (is (= 1 (get actual "s1c")) "still pick warm a")
    (is (= 2 (get actual "s1q0")))
    (is (= 0 (get actual "s1q1")))
    (is (= "a" (:node (cljc 0))))
    (is (= "a" (:node (cljc 1))))))

(deftest assign-step-2-both-warm-load-balances
  (let [f (* 16 GiB)
        ;; both warm → score by queue; tie picks index 1 (#73 prefer-warm-then-score)
        b0 (str "(better-from-queues 0 " f " 0 " f ")")
        s0 (str "(assign-step-2 0 0 true true (better2-record true true " b0 "))")
        b1 (str "(better-from-queues 0 " f " 1 " f ")")
        s1 (str "(assign-step-2 0 1 true true (better2-record true true " b1 "))")
        actual (compile-i64-cases
                {"s0c" (str "(assign-result-pick " s0 ")")
                 "s1c" (str "(assign-result-pick " s1 ")")})]
    (is (= 2 (get actual "s0c")))
    (is (= 1 (get actual "s1c")))))

(deftest assign-step-3-matches-schedule-assign
  (let [;; unequal free so score is strict (avoids known tie→later-idx vs cljc first)
        fa (* 8 GiB)
        fb (* 16 GiB)
        fc (* 32 GiB)
        model {:model/engine :comfyui
               :model/checkpoint "c.safetensors"
               :model/min-free-bytes (* 4 GiB)}
        a {:name "a" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
           :free-bytes fa :queue 0}
        b {:name "b" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
           :free-bytes fb :queue 0}
        c {:name "c" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
           :free-bytes fc :queue 0}
        jobs (repeat 3 {:model model})
        cljc (sched/assign [a b c] jobs)
        ;; all warm; more free ⇒ better score (score-free = -free)
        bp0 (str "(better3-record (better-pair 0 " fa " 0 " fb ") "
                 "(better-pair 0 " fa " 0 " fc ") "
                 "(better-pair 0 " fb " 0 " fc "))")
        ok-warm "(triple-record 1 1 1)"
        q0 "(triple-record 0 0 0)"
        s0 (str "(assign-step-3 " q0 " " ok-warm " " ok-warm " " bp0 ")")
        actual0 (compile-i64-cases
                 {"s0c" (str "(assign-step-3-code " s0 ")")
                  "s0q0" (str "(assign-step-3-q0 " s0 ")")
                  "s0q1" (str "(assign-step-3-q1 " s0 ")")
                  "s0q2" (str "(assign-step-3-q2 " s0 ")")
                  "pcn" "(pick-code-3 -1)"
                  "pc0" "(pick-code-3 0)"
                  "pc2" "(pick-code-3 2)"
                  "ap" (str "(assign-pick-3 " ok-warm " " ok-warm " " bp0 ")")
                  "aq0" (str "(triple-v0 (apply-pick-3 " q0 " 2))")
                  "aq1" (str "(triple-v1 (apply-pick-3 " q0 " 2))")
                  "aq2" (str "(triple-v2 (apply-pick-3 " q0 " 2))")})]
    (is (= 0 (get actual0 "pcn")))
    (is (= 1 (get actual0 "pc0")))
    (is (= 3 (get actual0 "pc2")))
    (is (= 2 (get actual0 "ap")) "pick c — largest free")
    (is (= 0 (get actual0 "aq0")))
    (is (= 0 (get actual0 "aq1")))
    (is (= 1 (get actual0 "aq2")))
    (is (= 3 (get actual0 "s0c")) "pick-code for node2")
    (is (= [0 0 1] [(get actual0 "s0q0") (get actual0 "s0q1") (get actual0 "s0q2")]))
    (is (= "c" (:node (nth cljc 0))))
    ;; step1: q=(0,0,1) — still prefer largest free among low queue
    (let [bp1 (str "(better3-record (better-pair 0 " fa " 0 " fb ") "
                   "(better-pair 0 " fa " 1 " fc ") "
                   "(better-pair 0 " fb " 1 " fc "))")
          q1 "(triple-record 0 0 1)"
          s1 (str "(assign-step-3 " q1 " " ok-warm " " ok-warm " " bp1 ")")
          actual1 (compile-i64-cases
                   {"s1c" (str "(assign-step-3-code " s1 ")")
                    "s1q0" (str "(assign-step-3-q0 " s1 ")")
                    "s1q1" (str "(assign-step-3-q1 " s1 ")")
                    "s1q2" (str "(assign-step-3-q2 " s1 ")")})]
      (is (= 2 (get actual1 "s1c")) "pick b — free 16GiB, queue 0 beats c queue 1")
      (is (= [0 1 1] [(get actual1 "s1q0") (get actual1 "s1q1") (get actual1 "s1q2")]))
      (is (= "b" (:node (nth cljc 1))))
      (is (= 3 (count cljc)))
      (is (every? some? (map :node cljc))))))

(deftest pick-fold-step-n4-matches-schedule-pick
  "Host-fold pick-fold-step over 4 nodes equals cljc pick when free differs."
  (let [fa (* 4 GiB)
        fb (* 8 GiB)
        fc (* 16 GiB)
        fd (* 32 GiB)
        model {:model/engine :comfyui
               :model/checkpoint "c.safetensors"
               :model/min-free-bytes (* 2 GiB)}
        nodes [{:name "a" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
                :free-bytes fa :queue 0}
               {:name "b" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
                :free-bytes fb :queue 0}
               {:name "c" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
                :free-bytes fc :queue 0}
               {:name "d" :engines #{:comfyui} :checkpoints #{"c.safetensors"}
                :free-bytes fd :queue 0}]
        cljc (sched/pick nodes model)
        frees [fa fb fc fd]
        ;; host fold: start no champ; all ok+warm
        ;; step i=0: has=0 ok=1 → take 0
        f0 (str "(pick-fold-step " (opt-i64-form nil) " true false true false)")
        ;; champ=0 warm=1; vs i=1 better 0 vs 1: free a < b so a not better → better=false
        b01 (str "(if (better-pair 0 " fa " 0 " fb ") 1 0)")
        f1 (str "(pick-fold-step " (opt-i64-form 1) " true true true (better-pair 0 " fa " 0 " fb "))")
        actual (compile-i64-cases
                {"f0" f0
                 "b01" b01
                 "f1" f1
                 "none" (str "(pick-fold-step " (opt-i64-form nil) " false false false false)")
                 "keep" (str "(pick-fold-step " (opt-i64-form 1) " false true false false)")
                 "qi0" "(queue-inc-if 0 0)"
                 "qi1" "(queue-inc-if 3 1)"})]
    (is (= 1 (get actual "f0")) "take first eligible")
    (is (= 0 (get actual "b01")) "b better free")
    (is (= 1 (get actual "f1")) "take challenger b")
    (is (= 0 (get actual "none")))
    (is (= 2 (get actual "keep")))
    (is (= 0 (get actual "qi0")))
    (is (= 4 (get actual "qi1")))
    ;; continue fold in second compile with b as champ vs c, d
    (let [b02 (str "(if (better-pair 0 " fb " 0 " fc ") 1 0)")
          f2 (str "(pick-fold-step " (opt-i64-form 1) " true true true (better-pair 0 " fb " 0 " fc "))")
          act2 (compile-i64-cases {"b02" b02 "f2" f2})]
      (is (= 0 (get act2 "b02")))
      (is (= 1 (get act2 "f2")) "take c")
      (let [f3 (str "(pick-fold-step " (opt-i64-form 1) " true true true (better-pair 0 " fc " 0 " fd "))")
            act3 (compile-i64-cases {"f3" f3})]
        (is (= 1 (get act3 "f3")) "take d — largest free")
        (is (= "d" (:name cljc)))))))

