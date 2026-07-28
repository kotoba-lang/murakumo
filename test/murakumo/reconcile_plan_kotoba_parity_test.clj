;; W6 pure-planner oracle: murakumo.reconcile.plan scalar decisions
;; vs kotoba/reconcile_plan_core.kotoba.

(ns murakumo.reconcile-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.reconcile.plan :as r]))

(def port-source (slurp "kotoba/reconcile_plan_core.kotoba"))

(def export-prefix "default-replicas desired deficit watch-sleep-ms action-name")

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

(defn- project-action-args
  "Host-project reconcile-app inputs into guest scalars."
  [app snap]
  (let [decision (r/reconcile-app fleet snap nil app)
        has-cid (if (:cid app) 1 0)
        running (count (:running decision))
        desired (or (:replicas app) 1)
        free (count (:targets decision))
        ;; when blocked, targets empty but candidates may be empty too
        free-candidates (if (= :blocked (:action decision))
                          0
                          (if (= :place (:action decision))
                            (max free 1)
                            free))
        has-misplaced (if (seq (:misplaced decision)) 1 0)]
    {:decision decision
     :has-cid has-cid
     :running running
     :desired desired
     :free-candidates free-candidates
     :has-misplaced has-misplaced}))

(deftest defaults-and-deficit-and-sleep
  (let [actual (compile-i64-cases
                {"dr" "(default-replicas)"
                 "d0" "(desired 0 99)"
                 "d1" "(desired 1 3)"
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
                          (str "(action-name " (:has-cid p) " " (:running p) " "
                               (:desired p) " " (:free-candidates p) " "
                               (:has-misplaced p) ")")]))
                     corpus))
        actual (compile-string-cases cases)]
    (doseq [[i [app sn]] (map-indexed vector corpus)]
      (let [p (project-action-args app sn)
            expected (name (:action (:decision p)))]
        (testing (str (:name app) " → " expected)
          (is (= expected (get actual (str "a_" i)))))))))
