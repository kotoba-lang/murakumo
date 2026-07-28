;; W6 pure-planner oracle: murakumo.infer.schedule eligible?/score
;; vs kotoba/infer_schedule_core.kotoba.

(ns murakumo.infer-schedule-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.schedule :as sched]))

(def port-source (slurp "kotoba/infer_schedule_core.kotoba"))

(def export-prefix "eligible? score-queue score-free better-score? holds-warm? prefer-warm-then-score pick-idx-2-full pick-idx-3-tournament queue-after-assign")

(def GiB (* 1024 1024 1024))

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

(defn- pack-flags
  "Bit-pack set-membership into guest flags:
   1 has-engine | 2 has-checkpoint | 4 holds-checkpoint | 8 can-fetch."
  [node model]
  (let [engine (:model/engine model)
        ckpt (:model/checkpoint model)
        engines (or (:engines node) #{})
        checkpoints (or (:checkpoints node) #{})
        has-engine (if (contains? engines engine) 1 0)
        has-ckpt (if (nil? ckpt) 0 1)
        holds (if (and ckpt (contains? checkpoints ckpt)) 1 0)
        can-fetch (if (false? (:node/can-fetch? node)) 0 1)]
    (+ has-engine
       (* 2 has-ckpt)
       (* 4 holds)
       (* 8 can-fetch))))

(defn- eligible-call [node model]
  (str "(eligible? " (pack-flags node model) " "
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

