;; W6 pure-planner oracle: murakumo.reconcile.plan scalar decisions
;; vs kotoba/reconcile_plan_core.kotoba.

(ns murakumo.reconcile-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.reconcile.plan :as r]))

(def port-source (slurp "kotoba/reconcile_plan_core.kotoba"))

(def export-prefix
  (str "default-replicas desired deficit watch-sleep-ms action-name "
       "better-target? first-of-2 first-of-3 "
       "pick-targets-2-pack pick-targets-3-first "
       "target-pack-first target-pack-second target-pack-count"))

(def fleet
  {:fleet/name "test-mesh"
   :nodes [{:name "a" :roles ["pin" "compute"] :labels {:zone "jp" :tier "edge"}}
           {:name "b" :roles ["pin" "compute"] :labels {:zone "jp" :tier "edge"}}
           {:name "c" :roles ["pin" "compute"] :labels {:zone "jp" :tier "edge"}}
           {:name "d" :roles ["pin" "compute" "relay"] :labels {:zone "jp" :role "canary"}}
           {:name "e" :roles ["pin"] :labels {:zone "us"}}]})

(def snap
  {:nodes [{:name "a" :hosted ["bafyHEART"]}
           {:name "b" :hosted []}
           {:name "c" :hosted []}
           {:name "d" :hosted []}
           {:name "e" :hosted ["bafyHEART"]}]})

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

(defn- opt-i64-form [n]
  (if (nil? n)
    "(option-none-of [:option :i64])"
    (str "(option-some-of [:option :i64] " (long n) ")")))

(defn- opt-str-form [s]
  (if (nil? s)
    "(option-none-of [:option :string])"
    (let [lit (str \" (-> (str s)
                          (str/replace "\\" "\\\\")
                          (str/replace "\"" "\\\"")) \")]
      (str "(option-some-of [:option :string] " lit ")"))))

(defn- project-action-args
  "Host-project reconcile-app inputs into guest Product Value ABI options."
  [app snap]
  (let [decision (r/reconcile-app fleet snap nil app)
        running (count (:running decision))
        desired (or (:replicas app) 1)
        free (count (:targets decision))
        ;; when blocked, targets empty but candidates may be empty too
        free-candidates (if (= :blocked (:action decision))
                          0
                          (if (= :place (:action decision))
                            (max free 1)
                            free))]
    {:decision decision
     :cid (:cid app)
     :running running
     :desired desired
     :free-candidates free-candidates}))

(deftest defaults-and-deficit-and-sleep
  (let [actual (compile-i64-cases
                {"dr" "(default-replicas)"
                 "d0" (str "(desired " (opt-i64-form nil) ")")
                 "d1" (str "(desired " (opt-i64-form 3) ")")
                 "df" "(deficit 3 1)"
                 "df0" "(deficit 1 3)"
                 "sl" "(watch-sleep-ms 15)"})]
    (is (= 1 (get actual "dr")))
    (is (= 1 (get actual "d0")))
    (is (= 3 (get actual "d1")))
    (is (= 2 (get actual "df")))
    (is (= 0 (get actual "df0")))
    (is (= (r/watch-sleep-ms 15) (get actual "sl")))))

(deftest action-name-matches-reconcile-app
  (let [apps [{:name "heartbeat" :cid "bafyHEART" :replicas 3
               :placement {:labels {:zone "jp"} :roles ["compute"]}}
              {:name "ok" :cid "bafyHEART" :replicas 1
               :placement {:labels {:zone "jp"} :roles ["compute"]}}
              {:name "ghost" :cid "bafyGHOST" :replicas 1
               :placement {:labels {:zone "antarctica"}}}
              {:name "uncompiled" :replicas 1 :placement {}}]
        snaps [snap
               {:nodes [{:name "a" :hosted ["bafyHEART"]}
                        {:name "b" :hosted []} {:name "c" :hosted []}
                        {:name "d" :hosted []} {:name "e" :hosted []}]}
               snap
               snap]
        ;; over: two eligible running, desired 1
        over-app {:name "heartbeat" :cid "bafyHEART" :replicas 1
                  :placement {:labels {:zone "jp"} :roles ["compute"]}}
        over-snap {:nodes [{:name "a" :hosted ["bafyHEART"]}
                           {:name "b" :hosted ["bafyHEART"]}
                           {:name "c" :hosted []} {:name "d" :hosted []}
                           {:name "e" :hosted []}]}
        corpus (conj (mapv vector apps snaps) [over-app over-snap])
        cases (into {}
                    (map-indexed
                     (fn [i [app sn]]
                       (let [p (project-action-args app sn)]
                         [(str "a_" i)
                          (str "(action-name " (opt-str-form (:cid p)) " "
                               (:running p) " " (:desired p) " "
                               (:free-candidates p) ")")]))
                     corpus))
        actual (compile-string-cases cases)]
    (doseq [[i [app sn]] (map-indexed vector corpus)]
      (let [p (project-action-args app sn)
            expected (name (:action (:decision p)))]
        (testing (str (:name app) " → " expected)
          (is (= expected (get actual (str "a_" i)))))))))

(deftest pick-targets-pure-matches-cljc
  (let [pick @#'r/pick-targets
        ;; name order bits for ["c" "a" "b"]: indices 0=c,1=a,2=b
        ;; name strings: a < b < c
        ;; n01: c<=a? 0; n02: c<=b? 0; n12: a<=b? 1 → bits 0+0+4 = 4
        name-bits-cab 4
        actual (compile-i64-cases
                {"f2" "(first-of-2 1 3 1)"  ;; load0=1 load1=3 name0 before → 0
                 "f2b" "(first-of-2 1 1 0)" ;; equal load, name0 after → 1
                 "p2" "(pick-targets-2-pack 1 3 1 2)"
                 "p2n1" "(pick-targets-2-pack 1 3 1 1)"
                 "p2n0" "(pick-targets-2-pack 1 3 1 0)"
                 "f3" (str "(first-of-3 1 3 1 " name-bits-cab ")")
                 "t3" (str "(pick-targets-3-first 1 3 1 " name-bits-cab ")")
                 "pf" "(target-pack-first (pick-targets-2-pack 1 3 1 2))"
                 "ps" "(target-pack-second (pick-targets-2-pack 1 3 1 2))"
                 "pc" "(target-pack-count (pick-targets-2-pack 1 3 1 2))"})
        ;; cljc: candidates c,a,b loads c=1,a=3,b=1 → sorted b,c,a
        cljc (pick ["c" "a" "b"] 2 {"c" 1 "a" 3 "b" 1})]
    (is (= 0 (get actual "f2")))
    (is (= 1 (get actual "f2b")))
    ;; first of c(1) vs a(3) with c before a? c>a so name0-before=0... wait
    ;; candidates order in call is c,a,b as indices 0,1,2
    ;; first-of-3 with loads 1,3,1: compare c vs a: load c better → w01=0
    ;; compare c vs b: loads equal 1,1, name c<=b? false → n02=0 → b wins → first=2 (b)
    (is (= 2 (get actual "f3")))
    (is (= 2 (get actual "t3")))
    (is (= 0 (get actual "pf")))  ;; first of two with load1=1,3 name0 before → 0
    (is (= 1 (get actual "ps")))
    (is (= 2 (get actual "pc")))
    (is (= ["b" "c"] cljc))
    ;; two-pack: candidates order [b c] loads 1,1 names b before c
    (let [p2 (compile-i64-cases
              {"ord" "(pick-targets-2-pack 1 1 1 2)"})
          pack (get p2 "ord")]
      (is (= 0 (rem pack 256)))
      (is (= 1 (rem (quot pack 256) 256)))
      (is (= 2 (quot pack 65536))))))

