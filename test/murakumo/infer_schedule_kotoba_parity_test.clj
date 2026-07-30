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
                        "queue-after-assign lane-base pack3 pack-get queues-pack-2 pick-code "
                        "assign-step-2 assign-result-pick assign-result-q0 assign-result-q1 "
                        "better-from-queues queues-pack-3 pick-code-3 assign-pick-3 apply-pick-3 "
                        "assign-step-3 assign-step-3-code assign-step-3-queues better-pair "
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
  "T5.3: the guest eligibility record descriptor, as source text."
  (str "[:record :schedule/eligibility "
       "[[:has-engine :i64] [:has-checkpoint :i64] "
       "[:holds-checkpoint :i64] [:can-fetch :i64]]]"))

(defn- eligibility-record-literal
  "Set-membership as a record literal instead of four packed bits."
  [node model]
  (let [engine (:model/engine model)
        ckpt (:model/checkpoint model)
        engines (or (:engines node) #{})
        checkpoints (or (:checkpoints node) #{})
        has-engine (if (contains? engines engine) 1 0)
        has-ckpt (if (nil? ckpt) 0 1)
        holds (if (and ckpt (contains? checkpoints ckpt)) 1 0)
        can-fetch (if (false? (:node/can-fetch? node)) 0 1)]
    (str "(record-new " eligibility-literal " "
         has-engine " " has-ckpt " " holds " " can-fetch ")")))

(defn- eligible-call [node model]
  (str "(eligible? " (eligibility-record-literal node model) " "
       (long (or (:free-bytes node) 0)) " "
       (long (or (:model/min-free-bytes model) 0)) ")"))
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
                           (str "(better-score? " q1 " " f1 " " q2 " " f2 ")")])
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
                (let [ok0 (if (sched/eligible? n0 model) 1 0)
                      ok1 (if (sched/eligible? n1 model) 1 0)
                      w0 (if (contains? (or (:checkpoints n0) #{}) (:model/checkpoint model)) 1 0)
                      w1 (if (contains? (or (:checkpoints n1) #{}) (:model/checkpoint model)) 1 0)
                      s0 (sched/score n0)
                      s1 (sched/score n1)
                      better (if (neg? (compare s0 s1)) 1 0)]
                  (str "(pick-idx-2-full " ok0 " " ok1 " " w0 " " w1 " " better ")")))
        cases (into {}
                    (map-indexed
                     (fn [i [nodes _]]
                       (if (= 1 (count nodes))
                         [(str "p_" i)
                          (let [n0 (first nodes)
                                ok0 (if (sched/eligible? n0 model) 1 0)]
                            (str "(pick-idx-2-full " ok0 " 0 0 0 0)"))]
                         [(str "p_" i) (body2 (nodes 0) (nodes 1))]))
                     pairs))
        actual (compile-i64-cases
                (merge cases
                       {"qa0" "(queue-after-assign 0 0)"
                        "qa1" "(queue-after-assign 0 1)"
                        "qa2" "(queue-after-assign 3 1)"
                        "t3" "(pick-idx-3-tournament 0 1 1 0 1)"}))]
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
        ;; step0
        b0 (str "(better-from-queues 0 " f0 " 0 " f1 ")")
        s0 (str "(assign-step-2 0 0 1 1 (pack3 1 0 " b0 "))")
        ;; after step0: q=(1,0); step1
        b1 (str "(better-from-queues 1 " f0 " 0 " f1 ")")
        s1 (str "(assign-step-2 1 0 1 1 (pack3 1 0 " b1 "))")
        actual (compile-i64-cases
                {"b0" b0
                 "s0" s0
                 "b1" b1
                 "s1" s1
                 "pc" "(pick-code -1)"
                 "pc0" "(pick-code 0)"
                 "pc1" "(pick-code 1)"
                 "qp" "(queues-pack-2 3 4)"
                 "g0" "(pack-get (queues-pack-2 3 4) 0)"
                 "g1" "(pack-get (queues-pack-2 3 4) 1)"})]
    (is (= 0 (get actual "pc")))
    (is (= 1 (get actual "pc0")))
    (is (= 2 (get actual "pc1")))
    (is (= 3 (get actual "g0")))
    (is (= 4 (get actual "g1")))
    (let [s0v (get actual "s0")
          code (mod s0v 65536)
          nq0 (mod (quot s0v 65536) 65536)
          nq1 (quot s0v (* 65536 65536))]
      (is (= 1 code) "pick a (warm)")
      (is (= 1 nq0))
      (is (= 0 nq1)))
    (let [s1v (get actual "s1")
          code (mod s1v 65536)
          nq0 (mod (quot s1v 65536) 65536)
          nq1 (quot s1v (* 65536 65536))]
      ;; warm preference still binds both jobs to a (cold b never enters warm set)
      (is (= 1 code) "still pick warm a")
      (is (= 2 nq0))
      (is (= 0 nq1)))
    (is (= "a" (:node (cljc 0))))
    (is (= "a" (:node (cljc 1))))))

(deftest assign-step-2-both-warm-load-balances
  (let [f (* 16 GiB)
        ;; both warm → score by queue; tie picks index 1 (#73 prefer-warm-then-score)
        b0 (str "(better-from-queues 0 " f " 0 " f ")")
        s0 (str "(assign-step-2 0 0 1 1 (pack3 1 1 " b0 "))")
        ;; after pick node1: queues (0,1)
        b1 (str "(better-from-queues 0 " f " 1 " f ")")
        s1 (str "(assign-step-2 0 1 1 1 (pack3 1 1 " b1 "))")
        actual (compile-i64-cases {"s0" s0 "s1" s1})]
    (let [c0 (mod (get actual "s0") 65536)
          c1 (mod (get actual "s1") 65536)]
      (is (= 2 c0))
      (is (= 1 c1)))))

(deftest assign-step-3-matches-schedule-assign
  (let [f (* 16 GiB)
        B 65536
        unpack3 (fn [p] [(mod p B) (mod (quot p B) B) (quot p (* B B))])
        ;; unequal free so score is strict (avoids known tie→later-idx vs cljc first)
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
        bp0 (str "(pack3 (better-pair 0 " fa " 0 " fb ") "
                 "(better-pair 0 " fa " 0 " fc ") "
                 "(better-pair 0 " fb " 0 " fc "))")
        s0 (str "(assign-step-3 (queues-pack-3 0 0 0) (pack3 1 1 1) (pack3 1 1 1) " bp0 ")")
        actual0 (compile-i64-cases
                 {"s0" s0
                  "pcn" "(pick-code-3 -1)"
                  "pc0" "(pick-code-3 0)"
                  "pc2" "(pick-code-3 2)"
                  "ap" (str "(assign-pick-3 (pack3 1 1 1) (pack3 1 1 1) " bp0 ")")
                  "aq" "(apply-pick-3 (queues-pack-3 0 0 0) 2)"})]
    (is (= 0 (get actual0 "pcn")))
    (is (= 1 (get actual0 "pc0")))
    (is (= 3 (get actual0 "pc2")))
    (is (= 2 (get actual0 "ap")) "pick c — largest free")
    (is (= [0 0 1] (unpack3 (get actual0 "aq"))))
    (let [code (mod (get actual0 "s0") 4)
          [nq0 nq1 nq2] (unpack3 (quot (get actual0 "s0") 4))]
      (is (= 3 code) "pick-code for node2")
      (is (= [0 0 1] [nq0 nq1 nq2]))
      (is (= "c" (:node (nth cljc 0)))))
    ;; step1: q=(0,0,1) — still prefer largest free among low queue
    (let [bp1 (str "(pack3 (better-pair 0 " fa " 0 " fb ") "
                   "(better-pair 0 " fa " 1 " fc ") "
                   "(better-pair 0 " fb " 1 " fc "))")
          s1 (str "(assign-step-3 (queues-pack-3 0 0 1) (pack3 1 1 1) (pack3 1 1 1) " bp1 ")")
          actual1 (compile-i64-cases
                   {"s1" s1
                    "gc" (str "(assign-step-3-code " (get actual0 "s0") ")")
                    "gq" (str "(assign-step-3-queues " (get actual0 "s0") ")")})]
      (is (= 3 (get actual1 "gc")))
      (is (= (quot (get actual0 "s0") 4) (get actual1 "gq")))
      (let [code (mod (get actual1 "s1") 4)
            [nq0 nq1 nq2] (unpack3 (quot (get actual1 "s1") 4))]
        (is (= 2 code) "pick b — free 16GiB, queue 0 beats c queue 1")
        (is (= [0 1 1] [nq0 nq1 nq2]))
        (is (= "b" (:node (nth cljc 1))))
        (is (= 3 (count cljc)))
        (is (every? some? (map :node cljc)))))))

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
        f0 (str "(pick-fold-step " (opt-i64-form nil) " 1 0 1 0)")
        ;; champ=0 warm=1; vs i=1 better 0 vs 1: free a < b so a not better → better=0
        b01 (str "(better-pair 0 " fa " 0 " fb ")")
        f1 (str "(pick-fold-step " (opt-i64-form 1) " 1 1 1 " b01 ")")
        actual (compile-i64-cases
                {"f0" f0
                 "b01" b01
                 "f1" f1
                 "none" (str "(pick-fold-step " (opt-i64-form nil) " 0 0 0 0)")
                 "keep" (str "(pick-fold-step " (opt-i64-form 1) " 0 1 0 0)")
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
    (let [b02 (str "(better-pair 0 " fb " 0 " fc ")")
          f2 (str "(pick-fold-step " (opt-i64-form 1) " 1 1 1 " b02 ")")
          act2 (compile-i64-cases {"b02" b02 "f2" f2})]
      (is (= 0 (get act2 "b02")))
      (is (= 1 (get act2 "f2")) "take c")
      (let [b03 (str "(better-pair 0 " fc " 0 " fd ")")
            f3 (str "(pick-fold-step " (opt-i64-form 1) " 1 1 1 " b03 ")")
            act3 (compile-i64-cases {"f3" f3})]
        (is (= 1 (get act3 "f3")) "take d — largest free")
        (is (= "d" (:name cljc)))))))

