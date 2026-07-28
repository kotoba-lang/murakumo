;; W6 pure-planner oracle: murakumo.dash.state display helpers
;; vs kotoba/dash_state_core.kotoba.

(ns murakumo.dash-state-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.dash.state :as state]))

(def port-source (slurp "kotoba/dash_state_core.kotoba"))

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [short-hosted-cid health-class-of interval-sleep-ms clamp-at take-last-start append-new-len cap-count recent-take-n "
                      (str/join " " names) "])"))
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
                 (str "(:export [short-hosted-cid health-class-of interval-sleep-ms clamp-at take-last-start append-new-len cap-count recent-take-n "
                      (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest short-hosted-cid-matches-dash-state
  (let [corpus ["bafy12345678901234567890"
                "bafyA"
                "bafy12345678901234"
                ""]
        cases (into {} (map-indexed
                        (fn [i cid]
                          [(str "sh_" i)
                           (str "(short-hosted-cid " (kotoba-literal cid) ")")])
                        corpus))
        actual (compile-string-cases cases)]
    (doseq [[i cid] (map-indexed vector corpus)]
      (testing (pr-str cid)
        (is (= (state/short-hosted-cid cid)
               (get actual (str "sh_" i))))))))

(deftest health-class-of-matches-dash-state
  (let [corpus ["ok" "down" "no-resp" "unknown" ""]
        cases (into {} (map-indexed
                        (fn [i h]
                          [(str "hc_" i)
                           (str "(health-class-of " (kotoba-literal h) ")")])
                        corpus))
        actual (compile-string-cases cases)]
    (doseq [[i h] (map-indexed vector corpus)]
      (testing h
        (is (= (state/health-class {:health h})
               (get actual (str "hc_" i))))))))

(deftest interval-sleep-ms-matches-dash-state
  (let [corpus [0 1 5 15 60]
        cases (into {} (map-indexed
                        (fn [i s] [(str "sl_" i) (str "(interval-sleep-ms " s ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (str s)
        (is (= (state/interval-sleep-ms s)
               (get actual (str "sl_" i))))))))

(deftest clamp-at-matches-dash-state
  (let [corpus [[-1 3] [0 3] [1 3] [2 3] [99 3] [0 0] [5 1] [0 1]]
        cases (into {} (map-indexed
                        (fn [i [at hc]]
                          [(str "cl_" i) (str "(clamp-at " at " " hc ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [at hc]] (map-indexed vector corpus)]
      (testing (pr-str [at hc])
        (is (= (state/clamp-at at hc)
               (get actual (str "cl_" i))))))))

(deftest cap-index-math-matches-append-capped
  (let [actual (compile-i64-cases
                {"tls" "(take-last-start 10 6)"
                 "tls0" "(take-last-start 3 6)"
                 "anl" "(append-new-len 5 1)"
                 "cc" "(cap-count 10 6)"
                 "cc2" "(cap-count 3 6)"
                 "rt" "(recent-take-n -1 6)"
                 "rt2" "(recent-take-n 3 6)"})]
    (is (= 4 (get actual "tls")))
    (is (= 0 (get actual "tls0")))
    (is (= 6 (get actual "anl")))
    (is (= 6 (get actual "cc")))
    (is (= 3 (get actual "cc2")))
    (is (= 6 (get actual "rt")))
    (is (= 3 (get actual "rt2")))
    (testing "cljc append-capped length"
      (let [v (state/append-capped (vec (range 5)) 6 :x)]
        (is (= 6 (count v)))
        (is (= :x (last v))))
      (let [v (state/append-capped (vec (range 10)) 6 :y)]
        (is (= 6 (count v)))
        (is (= (take-last 6 (conj (vec (range 10)) :y)) v))))))

