;; W6 pure-planner oracle: murakumo.fleet.inventory port/url/selector core
;; vs kotoba/fleet_inventory_core.kotoba.

(ns murakumo.fleet-inventory-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.fleet.inventory :as inv]))

(def port-source (slurp "kotoba/fleet_inventory_core.kotoba"))

(def export-prefix
  (str "default-control-port digit-char nat-str i64-str resolve-port health-url "
       "selector-is-all? selector-wants-name? line-has-offline? "
       "selector-all offline-token health-url-prefix health-url-path selector-join-sep"))

(def ^:private selector-name-ty
  "[:record :fleet/selector-name [[:sel :string] [:name :string]]]")

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

(defn- resolve-call [fleet node]
  (let [node-port (when (contains? node :port) (:port node))
        fleet-port (when (contains? fleet :fleet/port) (:fleet/port fleet))]
    (str "(resolve-port " (opt-i64-form node-port) " " (opt-i64-form fleet-port) ")")))

(deftest default-control-port-matches
  (let [actual (compile-i64-cases {"d" "(default-control-port)"})]
    (is (= 8077 (get actual "d")))))

(deftest resolve-port-matches-node-port
  (let [fleet {:fleet/port 9000 :nodes []}
        nodes [{:name "judah" :port 9010}
               {:name "asher"}
               {:name "solo"}]
        fleets [fleet fleet {:nodes []}]
        cases (into {} (map-indexed
                        (fn [i n]
                          [(str "rp_" i) (resolve-call (nth fleets i) n)])
                        nodes))
        actual (compile-i64-cases cases)]
    (doseq [[i n] (map-indexed vector nodes)]
      (testing (:name n)
        (is (= (inv/node-port (nth fleets i) n)
               (get actual (str "rp_" i))))))))

(deftest health-url-matches
  (let [fleet {:fleet/port 9000}
        nodes [{:name "judah" :port 9010}
               {:name "asher"}
               {:name "solo"}]
        fleets [fleet fleet {}]
        ports (mapv #(inv/node-port %1 %2) fleets nodes)
        cases (into {} (map-indexed
                        (fn [i p] [(str "hu_" i) (str "(health-url " p ")")])
                        ports))
        actual (compile-string-cases cases)]
    (doseq [[i n] (map-indexed vector nodes)]
      (testing (:name n)
        (is (= (inv/node-health-url (nth fleets i) n)
               (get actual (str "hu_" i))))))))

(deftest selector-is-all-matches
  (let [corpus ["" "all" "asher" "asher,levi" "ALL"]
        ;; empty string stands for cljc nil
        cljc-sel (fn [s] (if (= s "") nil s))
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "sa_" i)
                           (str "(if (selector-is-all? " (kotoba-literal s) ") 1 0)")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (let [sel (cljc-sel s)
              expected (if (or (nil? sel) (= sel inv/selector-all)) 1 0)]
          (is (= expected (get actual (str "sa_" i)))))))))

(deftest selector-wants-name-matches-split
  (let [corpus [["levi,asher,missing" "asher"]
                ["levi,asher,missing" "levi"]
                ["levi,asher,missing" "missing"]
                ["levi,asher,missing" "judah"]
                ["asher" "asher"]
                ["asher" "ash"]]
        cases (into {} (map-indexed
                        (fn [i [sel name]]
                          [(str "sw_" i)
                           (str "(if (selector-wants-name? (record-new " selector-name-ty " "
                                (kotoba-literal sel) " " (kotoba-literal name) ")) 1 0)")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [sel name]] (map-indexed vector corpus)]
      (testing (str sel " / " name)
        (let [want (set (str/split sel #","))
              expected (if (want name) 1 0)]
          (is (= expected (get actual (str "sw_" i)))))))))

(deftest line-has-offline-matches-tailscale-rule
  (let [corpus ["100.64.0.1 asher user@ macOS active; direct 1.2.3.4:41641"
                "100.64.0.2 judah user@ macOS offline"
                "noise"
                ""]
        cases (into {} (map-indexed
                        (fn [i line]
                          [(str "lo_" i)
                           (str "(if (line-has-offline? " (kotoba-literal line) ") 1 0)")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i line] (map-indexed vector corpus)]
      (testing (pr-str line)
        (let [expected (if (str/includes? line inv/offline-token) 1 0)]
          (is (= expected (get actual (str "lo_" i)))))))))

(deftest fleet-inventory-tokens-match
  (let [s (compile-string-cases
           {"sa" "(selector-all)"
            "ot" "(offline-token)"
            "hp" "(health-url-prefix)"
            "hs" "(health-url-path)"
            "sj" "(selector-join-sep)"})
        n (compile-i64-cases {"dp" "(default-control-port)"})]
    (is (= inv/selector-all (get s "sa")))
    (is (= "all" (get s "sa")))
    (is (= inv/offline-token (get s "ot")))
    (is (= "offline" (get s "ot")))
    (is (= inv/health-url-prefix (get s "hp")))
    (is (= "http://localhost:" (get s "hp")))
    (is (= inv/health-url-path (get s "hs")))
    (is (= "/health" (get s "hs")))
    (is (= inv/selector-join-sep (get s "sj")))
    (is (= "," (get s "sj")))
    (is (= inv/default-control-port (get n "dp")))
    (is (= 8077 (get n "dp")))
    (is (= (str inv/health-url-prefix 8077 inv/health-url-path)
           (inv/node-health-url {} {})))))
