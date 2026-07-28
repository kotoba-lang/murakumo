;; W6 pure-planner oracle: murakumo.infer.join tier core
;; vs kotoba/infer_join_core.kotoba.

(ns murakumo.infer-join-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.join :as join]))

(def port-source (slurp "kotoba/infer_join_core.kotoba"))

(def export-prefix
  "tier-browser tier-wasm tier-native max-resident-bytes needs-relay? can? clamp-resident eligible-for-work?")

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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

(defn- tier-code [t]
  (case t :browser 0 :wasm 1 :native 2 2))

(defn- kind-name [k]
  (name k))

(deftest max-resident-matches-tiers
  (let [cases {"b" "(max-resident-bytes 0)"
               "w" "(max-resident-bytes 1)"
               "n" "(max-resident-bytes 2)"}
        actual (compile-i64-cases cases)]
    (is (= (get-in join/tiers [:browser :max-resident-bytes]) (get actual "b")))
    (is (= (get-in join/tiers [:wasm :max-resident-bytes]) (get actual "w")))
    (is (= (get-in join/tiers [:native :max-resident-bytes]) (get actual "n")))))

(deftest needs-relay-matches-join
  (let [corpus [[:browser true] [:browser false]
                [:wasm true] [:wasm false]
                [:native true] [:native false]]
        cases (into {} (map-indexed
                        (fn [i [t inbound]]
                          [(str "nr_" i)
                           (str "(needs-relay? " (tier-code t) " "
                                (if inbound 1 0) ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [t inbound]] (map-indexed vector corpus)]
      (testing (str t " inbound=" inbound)
        (is (= (if (join/needs-relay? {:tier t :inbound-reachable? inbound}) 1 0)
               (get actual (str "nr_" i))))))))

(deftest can-matches-join-tiers
  (let [kinds [:media-postproc :small-shard :embarrassingly-parallel :prompt-eval
               :host-large-model :low-latency-pipeline :media-generate :full-shard
               :unknown-kind]
        tiers [:browser :wasm :native]
        corpus (for [t tiers k kinds] [t k])
        cases (into {} (map-indexed
                        (fn [i [t k]]
                          [(str "c_" i)
                           (str "(can? " (tier-code t) " "
                                (kotoba-literal (kind-name k)) ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [t k]] (map-indexed vector corpus)]
      (testing (str t " / " k)
        (is (= (if (join/can? {:tier t} k) 1 0)
               (get actual (str "c_" i))))))))

(deftest clamp-resident-matches-enrollment-min
  (let [tier-max (get-in join/tiers [:browser :max-resident-bytes])
        corpus [[(* 8 1024 1024 1024) tier-max]
                [(* 1 1024 1024 1024) tier-max]
                [-1 tier-max]
                [tier-max tier-max]]
        cases (into {} (map-indexed
                        (fn [i [mem tmax]]
                          [(str "cr_" i) (str "(clamp-resident " mem " " tmax ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [mem tmax]] (map-indexed vector corpus)]
      (testing (str mem)
        (let [expected (if (neg? mem) tmax (min mem tmax))]
          (is (= expected (get actual (str "cr_" i)))))))))

(deftest eligible-for-work-matches-join
  (let [corpus [[1 (* 2 1024 1024 1024) 0]
                [1 (* 2 1024 1024 1024) (* 1 1024 1024 1024)]
                [1 (* 2 1024 1024 1024) (* 3 1024 1024 1024)]
                [0 (* 13 1024 1024 1024) 100]]
        cases (into {} (map-indexed
                        (fn [i [can maxr res]]
                          [(str "ew_" i)
                           (str "(eligible-for-work? " can " " maxr " " res ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [can maxr res]] (map-indexed vector corpus)]
      (testing (pr-str [can maxr res])
        (let [node {:node/can (when (= can 1) [:media-postproc])
                    :node/caps {:max-resident-bytes maxr}}
              job {:work-kind :media-postproc :resident-bytes res}
              ;; project can-kind the way host would
              can-kind (if (and (= can 1)
                                (some #{:media-postproc} (:node/can node)))
                         1 0)
              expected (if (join/eligible-for-work?
                            (assoc node :node/can (if (= can 1)
                                                    [:media-postproc]
                                                    []))
                            job)
                         1 0)]
          (is (= expected (get actual (str "ew_" i))))
          (is (= can-kind can)))))))
