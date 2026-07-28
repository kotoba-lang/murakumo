;; W6 pure-planner oracle: murakumo.deploy.plan path/argv/constants
;; vs kotoba/deploy_plan_core.kotoba.

(ns murakumo.deploy-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.deploy.plan :as plan]))

(def port-source (slurp "kotoba/deploy_plan_core.kotoba"))

(def export-prefix
  "default-wasm default-publish-node artifact-forward-port publish-forward-port forward-settle-ms placement-wait-ms digit-char nat-str i64-str app-manifest-path publish-selector localhost-url last-slash-index manifest-dir command-output component-build-cmd app-deploy-cmd")

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

(deftest constants-match
  (let [s (compile-string-cases
           {"w" "(default-wasm)" "p" "(default-publish-node)"})
        n (compile-i64-cases
           {"a" "(artifact-forward-port)"
            "u" "(publish-forward-port)"
            "f" "(forward-settle-ms)"
            "w" "(placement-wait-ms)"})]
    (is (= plan/default-wasm (get s "w")))
    (is (= plan/default-publish-node (get s "p")))
    (is (= plan/artifact-forward-port (get n "a")))
    (is (= plan/publish-forward-port (get n "u")))
    (is (= plan/forward-settle-ms (get n "f")))
    (is (= plan/placement-wait-ms (get n "w")))))

(deftest manifest-dir-and-paths-match
  (let [cases {"d1" (str "(manifest-dir " (kotoba-literal "apps/bot.edn") ")")
               "d2" (str "(manifest-dir " (kotoba-literal "bot.edn") ")")
               "d3" (str "(manifest-dir " (kotoba-literal "a/b/c.edn") ")")
               "ap" (str "(app-manifest-path " (kotoba-literal "apps") " "
                         (kotoba-literal "heartbeat.edn") ")")
               "ps0" (str "(publish-selector " (kotoba-literal "") ")")
               "ps1" (str "(publish-selector " (kotoba-literal "judah") ")")
               "co" (str "(command-output " (kotoba-literal "  bafyCID\n") ")")}
        actual (compile-string-cases cases)]
    (is (= (plan/manifest-dir "apps/bot.edn") (get actual "d1")))
    (is (= (plan/manifest-dir "bot.edn") (get actual "d2")))
    (is (= (plan/manifest-dir "a/b/c.edn") (get actual "d3")))
    (is (= (plan/app-manifest-path "apps" {:manifest "heartbeat.edn"})
           (get actual "ap")))
    (is (= (plan/publish-selector nil) (get actual "ps0")))
    (is (= (plan/publish-selector "judah") (get actual "ps1")))
    (is (= (plan/command-output "  bafyCID\n") (get actual "co")))))

(deftest argv-joined-cmds-match
  (let [build (plan/component-build-argv "/bin/kotoba" "apps/src/bot.clj" "wit" "/tmp/out.wasm")
        deploy (plan/app-deploy-argv "/bin/kotoba" "apps/bot.edn" "wit" 18077)
        cases {"b" (str "(component-build-cmd "
                        (kotoba-literal "/bin/kotoba") " "
                        (kotoba-literal "apps/src/bot.clj") " "
                        (kotoba-literal "wit") " "
                        (kotoba-literal "/tmp/out.wasm") ")")
               "d" (str "(app-deploy-cmd "
                        (kotoba-literal "/bin/kotoba") " "
                        (kotoba-literal "apps/bot.edn") " "
                        (kotoba-literal "wit") " 18077)")
               "u" "(localhost-url 18077)"}
        actual (compile-string-cases cases)]
    (is (= (str/join " " build) (get actual "b")))
    (is (= (str/join " " deploy) (get actual "d")))
    (is (= "http://localhost:18077" (get actual "u")))))
