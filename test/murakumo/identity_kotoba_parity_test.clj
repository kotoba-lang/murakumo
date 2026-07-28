;; W6 pure-planner oracle: murakumo.identity string preimages + trim
;; vs kotoba/identity_core.kotoba (hashing stays cljc).

(ns murakumo.identity-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.identity :as id]))

(def port-source (slurp "kotoba/identity_core.kotoba"))

(def export-prefix
  "ws? trim seed-node seed-p2p seed-x25519 seed-overlay did-derive-cmd did-from-output")

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

(deftest trim-and-did-from-output-match
  (let [corpus [" did:key:z-test\n"
                "\tdid:key:z\r\n"
                "plain"
                ""
                "  x  "]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "d_" i)
                           (str "(did-from-output " (kotoba-literal s) ")")])
                        corpus))
        actual (compile-string-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (id/did-from-output s) (get actual (str "d_" i))))))))

(deftest seed-preimages-match-sha-inputs
  (let [seed "operator"
        node "asher"
        overlay "bafyOverlay"
        cases {"n" (str "(seed-node " (kotoba-literal seed) " "
                        (kotoba-literal node) ")")
               "p" (str "(seed-p2p " (kotoba-literal seed) " "
                        (kotoba-literal node) ")")
               "x" (str "(seed-x25519 " (kotoba-literal seed) ")")
               "o" (str "(seed-overlay " (kotoba-literal seed) " "
                        (kotoba-literal overlay) ")")
               "cmd" (str "(did-derive-cmd " (kotoba-literal "/bin/kotoba") " "
                          (kotoba-literal seed) ")")}
        actual (compile-string-cases cases)]
    (is (= (str seed ":" node) (get actual "n")))
    (is (= (id/node-seed seed {:name node})
           (id/sha256-hex (get actual "n"))))
    (is (= (id/node-p2p-seed seed {:name node})
           (id/sha256-hex (get actual "p"))))
    (is (= (id/x25519-seed seed)
           (id/sha256-hex (get actual "x"))))
    (is (= (id/overlay-auth-key seed overlay)
           (id/sha256-hex (get actual "o"))))
    (is (= (str/join " " (id/did-derive-argv "/bin/kotoba" seed))
           (get actual "cmd")))))
