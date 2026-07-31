;; W6 pure-planner oracle: murakumo.component-authority scalar core
;; vs kotoba/component_authority_core.kotoba.

(ns murakumo.component-authority-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.component-authority :as authority]))

(def port-source (slurp "kotoba/component_authority_core.kotoba"))

(def ^:private id-len-lit
  "[:record :cauth/id-len [[:is-blank :bool] [:byte-len :i64]]]")
(defn- id-len-call [blank n]
  (str "(if (identifier-len-ok? (record-new " id-len-lit " " blank " " n ")) 1 0)"))

(def export-prefix
  (str "event-version max-identifier-bytes blank? ws? identifier? "
       "identifier-len-ok? place-epoch revoke-epoch next-sequence "
       "command-op event-kind format-v1 algorithm-ed25519 "
       "op-place op-revoke op-unknown event-placed event-revoked"))

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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

(defn- cljc-identifier? [x]
  (and (string? x) (not (str/blank? x)) (<= (count x) 4096)))

(deftest constants-match-authority
  (let [n (compile-i64-cases
           {"ev" "(event-version)"
            "mx" "(max-identifier-bytes)"})
        s (compile-string-cases
           {"fmt" "(format-v1)"
            "alg" "(algorithm-ed25519)"})]
    (is (= authority/event-version (get n "ev")))
    (is (= 4096 (get n "mx")))
    (is (= authority/format-v1 (get s "fmt")))
    (is (= "murakumo.component-authority/v1" (get s "fmt")))
    (is (= authority/algorithm-ed25519 (get s "alg")))
    (is (= "ed25519" (get s "alg")))))

(deftest identifier-policy-matches-cljc
  (let [corpus ["" "   " "\t" "bafyreicomponent" "edge-a" "x"]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "id_" i)
                           ;; Profile 5: identifier? is :bool.
                           (str "(if (identifier? " (kotoba-literal s) ") 1 0)")])
                        corpus))
        actual (compile-i64-cases cases)
        ;; length bounds via host-projected blank/byte-len (no huge literals)
        lens (compile-i64-cases
              {"ok4096" (id-len-call "false" 4096)
               "bad4097" (id-len-call "false" 4097)
               "blank" (id-len-call "true" 10)})]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (if (cljc-identifier? s) 1 0)
               (get actual (str "id_" i))))))
    (is (= 1 (get lens "ok4096")))
    (is (= 0 (get lens "bad4097")))
    (is (= 0 (get lens "blank")))
    (is (cljc-identifier? (apply str (repeat 4096 "a"))))
    (is (not (cljc-identifier? (apply str (repeat 4097 "a")))))))

(deftest epoch-and-sequence-match-place-revoke
  (let [actual (compile-i64-cases
                {"p0" "(place-epoch 0)"
                 "p1" "(place-epoch 1)"
                 "p5" "(place-epoch 5)"
                 "r0" "(revoke-epoch 0)"
                 "r1" "(revoke-epoch 1)"
                 "r5" "(revoke-epoch 5)"
                 "s0" "(next-sequence 0)"
                 "s2" "(next-sequence 2)"})]
    ;; place: missing epoch → 1; existing kept
    (is (= 1 (get actual "p0")))
    (is (= 1 (get actual "p1")))
    (is (= 5 (get actual "p5")))
    ;; revoke: (inc (or current 0))
    (is (= 1 (get actual "r0")))
    (is (= 2 (get actual "r1")))
    (is (= 6 (get actual "r5")))
    (is (= 1 (get actual "s0")))
    (is (= 3 (get actual "s2")))
    (testing "live place/revoke epoch surface"
      (let [[st1 e1] (authority/place (authority/initial-state) "cid-a" "n1")
            [st2 e2] (authority/place st1 "cid-a" "n2")
            [st3 e3] (authority/revoke st2 "cid-a")]
        (is (= 1 (:murakumo.component/epoch e1) (get actual "p0")))
        (is (= 1 (:murakumo.component/epoch e2)))
        (is (= 2 (:murakumo.component/epoch e3) (get actual "r1")))
        (is (= 1 (:murakumo.component/sequence e1)))
        (is (= 2 (:murakumo.component/sequence e2)))
        (is (= 3 (:murakumo.component/sequence e3)))
        (is (= :placed (:murakumo.component/event e1)))
        (is (= :revoked (:murakumo.component/event e3)))
        (is (= authority/event-version (:murakumo.component/version e1)))
        (is (nil? (authority/current-epoch (authority/initial-state) "x")))
        (is (= 2 (authority/current-epoch st3 "cid-a")))))))

(deftest command-and-event-kind-names
  (let [actual (compile-string-cases
                {"cp" (str "(command-op " (kotoba-literal "place") ")")
                 "cr" (str "(command-op " (kotoba-literal "revoke") ")")
                 "cu" (str "(command-op " (kotoba-literal "grant-ambient") ")")
                 "ep" (str "(event-kind " (kotoba-literal "place") ")")
                 "er" (str "(event-kind " (kotoba-literal "revoke") ")")
                 "eu" (str "(event-kind " (kotoba-literal "grant") ")")})]
    (is (= "place" (get actual "cp")))
    (is (= "revoke" (get actual "cr")))
    (is (= "unknown" (get actual "cu")))
    (is (= "placed" (get actual "ep")))
    (is (= "revoked" (get actual "er")))
    (is (= "unknown" (get actual "eu")))))

(deftest cauth-op-tokens-match
  (let [s (compile-string-cases
           {"op" "(op-place)"
            "orv" "(op-revoke)"
            "ou" "(op-unknown)"
            "ep" "(event-placed)"
            "er" "(event-revoked)"
            "cp" (str "(command-op " (kotoba-literal "place") ")")
            "cr" (str "(command-op " (kotoba-literal "revoke") ")")
            "cu" (str "(command-op " (kotoba-literal "bogus") ")")
            "kp" (str "(event-kind " (kotoba-literal "place") ")")
            "kr" (str "(event-kind " (kotoba-literal "revoke") ")")
            "ku" (str "(event-kind " (kotoba-literal "x") ")")})]
    (is (= authority/op-place (get s "op")))
    (is (= "place" (get s "op")))
    (is (= authority/op-revoke (get s "orv")))
    (is (= "revoke" (get s "orv")))
    (is (= authority/op-unknown (get s "ou")))
    (is (= "unknown" (get s "ou")))
    (is (= authority/event-placed (get s "ep")))
    (is (= "placed" (get s "ep")))
    (is (= authority/event-revoked (get s "er")))
    (is (= "revoked" (get s "er")))
    (is (= authority/op-place (get s "cp")))
    (is (= authority/op-revoke (get s "cr")))
    (is (= authority/op-unknown (get s "cu")))
    (is (= authority/event-placed (get s "kp")))
    (is (= authority/event-revoked (get s "kr")))
    (is (= authority/op-unknown (get s "ku")))))
