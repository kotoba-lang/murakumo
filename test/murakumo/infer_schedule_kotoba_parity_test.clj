;; W6 pure-planner oracle: murakumo.infer.schedule eligible?/score
;; vs kotoba/infer_schedule_core.kotoba.

(ns murakumo.infer-schedule-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.schedule :as sched]))

(def port-source (slurp "kotoba/infer_schedule_core.kotoba"))

(def export-prefix "eligible? score-queue score-free better-score?")

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
