(ns murakumo.connect-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.connect :as connect]))

(def port-source (slurp "kotoba/connect_core.kotoba"))
(def export-prefix
  (str "default-class-name node-class-name serves-read? serves-live? serves-plane? "
       "class-native plane-read plane-live"))

(def connect-spec
  {:default-class :native
   :classes {:native {:read [:http] :live [:quic]}
             :browser {:read [:http] :live [:webrtc]}
             :edge {:read [:http] :live [:wss]}}})

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- bool-form [b]
  (if b "true" "false"))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- project-serves [node reach]
  (let [{:keys [class plane]} (#'connect/parse-reach reach)
        ncls (connect/node-class connect-spec node)
        http? (boolean (some #{:http}
                             (connect/class-transports connect-spec ncls :read)))
        common (set/intersection
                (set (connect/class-transports connect-spec ncls :live))
                (set (connect/class-transports connect-spec class :live)))
        common? (boolean (seq common))]
    {:plane (name plane) :http? http? :common? common?
     :expected (if (connect/serves-reach? connect-spec node reach) 1 0)}))

(deftest class-defaults-match
  (let [actual (compile-string-cases
                {"d0" (str "(default-class-name " (kotoba-literal "") ")")
                 "d1" (str "(default-class-name " (kotoba-literal "edge") ")")
                 "n0" (str "(node-class-name " (kotoba-literal "") " "
                           (kotoba-literal "") ")")
                 "n1" (str "(node-class-name " (kotoba-literal "browser") " "
                           (kotoba-literal "native") ")")})]
    (is (= (name (connect/default-class {})) (get actual "d0")))
    (is (= "edge" (get actual "d1")))
    (is (= (name (connect/node-class {} {})) (get actual "n0")))
    (is (= "browser" (get actual "n1")))))

(deftest serves-plane-matches-connect
  (let [native {:name "n"}
        corpus [[native :browser/read]
                [native :browser/live]
                [native :native/live]
                [native :edge/live]]
        cases (into {}
                    (map-indexed
                     (fn [i [node reach]]
                       (let [p (project-serves node reach)]
                         [(str "s_" i)
                          (str "(if (serves-plane? " (kotoba-literal (:plane p)) " "
                               (bool-form (:http? p)) " "
                               (bool-form (:common? p)) ") 1 0)")]))
                     corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i [node reach]] (map-indexed vector corpus)]
      (let [p (project-serves node reach)]
        (testing (str reach)
          (is (= (:expected p) (get actual (str "s_" i)))))))))

(deftest connect-plane-tokens-match
  (let [s (compile-string-cases
           {"cn" "(class-native)"
            "pr" "(plane-read)"
            "pl" "(plane-live)"
            "d0" (str "(default-class-name " (kotoba-literal "") ")")})]
    (is (= connect/class-native (get s "cn")))
    (is (= "native" (get s "cn")))
    (is (= connect/plane-read (get s "pr")))
    (is (= "read" (get s "pr")))
    (is (= connect/plane-live (get s "pl")))
    (is (= "live" (get s "pl")))
    (is (= connect/class-native (get s "d0")))
    (is (= (name (connect/default-class {})) connect/class-native))))
