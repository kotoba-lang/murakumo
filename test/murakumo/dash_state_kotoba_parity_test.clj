;; W6 pure-planner oracle: murakumo.dash.state display helpers
;; vs kotoba/dash_state_core.kotoba.

(ns murakumo.dash-state-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.dash.state :as state]))

(def port-source (slurp "kotoba/dash_state_core.kotoba"))

(def export-prefix
  (str "short-hosted-cid health-class-of interval-sleep-ms clamp-at "
       "take-last-start append-new-len cap-count recent-take-n "
       "digit-char nat-str i64-str digit-val? digit-of parse-digits-go parse-digits "
       "trim-ws parse-links probe-line-key probe-line-value "
       "content-type-json content-type-html http-ok-status "
       "health-from-present health-ok-label health-down-label "
       "health-url mesh-log-path probe-h-prefix probe-l-clause probe-p-clause "
       "probe-command short-hosted-cid-max-len short-cid-max-len hosted-join-sep "
       "default-dashboard-port default-dashboard-interval "
       "default-dashboard-port-str default-dashboard-interval-str "
       "join-append hosted-append snapshot-record-type"))

(def ^:private join-ty
  "[:record :dash/join [[:acc :string] [:sep :string] [:next :string]]]")
(def ^:private hosted-ty
  "[:record :dash/hosted [[:acc :string] [:next :string]]]")
(def ^:private clamp-ty
  "[:record :dash/clamp [[:requested-at :i64] [:history-count :i64]]]")
(def ^:private pair-i64-ty
  "[:record :dash/pair-i64 [[:a :i64] [:b :i64]]]")

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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
                          [(str "cl_" i)
                           (str "(clamp-at (record-new " clamp-ty " " at " " hc "))")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [at hc]] (map-indexed vector corpus)]
      (testing (pr-str [at hc])
        (is (= (state/clamp-at at hc)
               (get actual (str "cl_" i))))))))

(deftest cap-index-math-matches-append-capped
  (let [actual (compile-i64-cases
                {"tls" (str "(take-last-start (record-new " pair-i64-ty " 10 6))")
                 "tls0" (str "(take-last-start (record-new " pair-i64-ty " 3 6))")
                 "anl" (str "(append-new-len (record-new " pair-i64-ty " 5 1))")
                 "cc" (str "(cap-count (record-new " pair-i64-ty " 10 6))")
                 "cc2" (str "(cap-count (record-new " pair-i64-ty " 3 6))")
                 "rt" (str "(recent-take-n (record-new " pair-i64-ty " -1 6))")
                 "rt2" (str "(recent-take-n (record-new " pair-i64-ty " 3 6))")})]
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

(deftest parse-links-matches-dash-state
  (let [corpus ["2" " 0 " "10" "" "not-int" "12x" " 7"]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "pl_" i)
                           (str "(parse-links " (kotoba-literal s) ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (state/parse-links s)
               (get actual (str "pl_" i))))))))

(deftest probe-line-key-value-matches
  (let [lines ["H:{\"ok\":true}" "L:2" "P:bafyA," "nope" "X" ""]
        key-cases (into {} (map-indexed
                            (fn [i line]
                              [(str "pk_" i)
                               (str "(probe-line-key " (kotoba-literal line) ")")])
                            lines))
        val-cases (into {} (map-indexed
                            (fn [i line]
                              [(str "pv_" i)
                               (str "(probe-line-value " (kotoba-literal line) ")")])
                            lines))
        keys (compile-string-cases key-cases)
        vals (compile-string-cases val-cases)]
    (doseq [[i line] (map-indexed vector lines)]
      (testing (pr-str line)
        (let [m (state/probe-lines (str line "\n"))
              k (get keys (str "pk_" i))
              v (get vals (str "pv_" i))]
          (if (seq k)
            (is (= {k v} m))
            (is (= {} m))))))))

(deftest content-type-and-health-from-present
  (let [actual-s (compile-string-cases
                  {"ctj" "(content-type-json)"
                   "cth" "(content-type-html)"
                   "ho" "(health-from-present true)"
                   "hd" "(health-from-present false)"
                   "ok" "(health-ok-label)"
                   "dn" "(health-down-label)"})
        actual-i (compile-i64-cases {"st" "(http-ok-status)"})]
    (is (= "application/json" (get actual-s "ctj")))
    (is (= "text/html; charset=utf-8" (get actual-s "cth")))
    (is (= 200 (get actual-i "st")))
    (is (= "ok" (get actual-s "ho")))
    (is (= "down" (get actual-s "hd")))
    (is (= (:status (state/json-response "{}")) (get actual-i "st")))
    (is (= (get-in (state/html-response "<x/>") [:headers "content-type"])
           (get actual-s "cth")))
    (is (= "ok" (state/health-from-present true)))
    (is (= "down" (state/health-from-present false)))))

(deftest probe-command-matches-dash-state
  (let [s (compile-string-cases
           {"hu" "(health-url 8077)"
            "ml" "(mesh-log-path)"
            "hp" "(probe-h-prefix)"
            "pc" "(probe-command 8077)"
            "p2" "(probe-command 18099)"})]
    (is (= (state/health-url 8077) (get s "hu")))
    (is (= (state/mesh-log-path) (get s "ml")))
    (is (str/starts-with? (get s "hp") "echo \"H:$(curl"))
    (is (= (state/probe-command 8077) (get s "pc")))
    (is (= (state/probe-command 18099) (get s "p2")))
    (is (str/includes? (get s "pc") "http://localhost:8077/health"))
    (is (str/includes? (get s "pc") "peer connected"))
    (is (str/includes? (get s "pc") "trigger: executed"))
    (is (str/starts-with? (get s "pc") "echo \"H:$(curl -s -m4 "))))

(deftest dashboard-defaults-and-hosted-join-match
  (let [s (compile-string-cases
           {"hj" "(hosted-join-sep)"
            "ps" "(default-dashboard-port-str)"
            "is" "(default-dashboard-interval-str)"
            "ja" (str "(join-append (record-new " join-ty " "
                      (kotoba-literal "") " "
                      (kotoba-literal " ") " " (kotoba-literal "a") "))")
            "jb" (str "(join-append (record-new " join-ty " "
                      (kotoba-literal "a") " "
                      (kotoba-literal " ") " " (kotoba-literal "b") "))")
            "ha0" (str "(hosted-append (record-new " hosted-ty " "
                       (kotoba-literal "") " "
                       (kotoba-literal "bafyA") "))")
            "ha1" (str "(hosted-append (record-new " hosted-ty " "
                       (kotoba-literal "bafyA") " "
                       (kotoba-literal "bafyB") "))")})
        n (compile-i64-cases
           {"hm" "(short-hosted-cid-max-len)"
            "cm" "(short-cid-max-len)"
            "dp" "(default-dashboard-port)"
            "di" "(default-dashboard-interval)"})]
    (is (= state/hosted-join-sep (get s "hj")))
    (is (= " " (get s "hj")))
    (is (= state/default-dashboard-port-str (get s "ps")))
    (is (= "8899" (get s "ps")))
    (is (= state/default-dashboard-interval-str (get s "is")))
    (is (= "15" (get s "is")))
    (is (= state/short-hosted-cid-max-len (get n "hm")))
    (is (= 18 (get n "hm")))
    (is (= state/short-cid-max-len (get n "cm")))
    (is (= 14 (get n "cm")))
    (is (= state/default-dashboard-port (get n "dp")))
    (is (= 8899 (get n "dp")))
    (is (= state/default-dashboard-interval (get n "di")))
    (is (= 15 (get n "di")))
    (is (= (state/join-append "" " " "a") (get s "ja")))
    (is (= "a" (get s "ja")))
    (is (= (state/join-append "a" " " "b") (get s "jb")))
    (is (= "a b" (get s "jb")))
    (is (= (state/hosted-append "" "bafyA") (get s "ha0")))
    (is (= (state/hosted-append "bafyA" "bafyB") (get s "ha1")))
    (is (= "bafyA bafyB" (get s "ha1")))
    (is (= {:port 8899 :interval 15} (state/dashboard-options [])))
    (is (= "bafyA bafyB" (state/hosted-summary {:hosted ["bafyA" "bafyB"]})))
    (is (nil? (state/hosted-summary {:hosted []})))))

(deftest snapshot-record-type-matches
  (let [s (compile-string-cases {"srt" "(snapshot-record-type)"})]
    (is (= state/snapshot-record-type (get s "srt")))
    (is (= "com.murakumo.fleet.snapshot" (get s "srt")))
    (is (= "com.murakumo.fleet.snapshot"
           (:$type (state/snapshot-record
                    {:ts 1 :fleet "f" :nodes []} "{}"))))))
