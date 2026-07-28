(ns murakumo.persist-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.persist :as persist]))

(def port-source (slurp "kotoba/persist_core.kotoba"))
(def export-prefix
  "repo-authority fleet-graph-name snapshot-collection reconcile-collection snapshot-local-port reconcile-local-port forward-settle-ms digit-char nat-str i64-str snapshot-rkey reconcile-rkey repo-uri repo-write-url write-ok?")

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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

(deftest constants-and-paths-match
  (let [s (compile-string-cases
           {"a" "(repo-authority)" "g" "(fleet-graph-name)"
            "sc" "(snapshot-collection)" "rc" "(reconcile-collection)"
            "rk" "(snapshot-rkey 1000 1)" "rr" "(reconcile-rkey 1000 2)"
            "u" (str "(repo-uri " (kotoba-literal "com.murakumo.fleet.snapshot") " "
                     (kotoba-literal "snap-1") ")")
            "w" "(repo-write-url 18099)"})
        n (compile-i64-cases
           {"sp" "(snapshot-local-port)" "rp" "(reconcile-local-port)"
            "fs" "(forward-settle-ms)"
            "ok" (str "(write-ok? " (kotoba-literal "{\"status\":\"ok\"}") ")")
            "bad" (str "(write-ok? " (kotoba-literal "{\"status\":\"err\"}") ")")})]
    (is (= persist/repo-authority (get s "a")))
    (is (= persist/fleet-graph-name (get s "g")))
    (is (= persist/snapshot-collection (get s "sc")))
    (is (= persist/reconcile-collection (get s "rc")))
    (is (= (persist/snapshot-rkey 1000 1) (get s "rk")))
    (is (= (persist/reconcile-rkey 1000 2) (get s "rr")))
    (is (= (persist/repo-uri "com.murakumo.fleet.snapshot" "snap-1") (get s "u")))
    (is (= (persist/repo-write-url 18099) (get s "w")))
    (is (= persist/snapshot-local-port (get n "sp")))
    (is (= persist/reconcile-local-port (get n "rp")))
    (is (= persist/forward-settle-ms (get n "fs")))
    (is (= (if (persist/write-ok? "{\"status\":\"ok\"}") 1 0) (get n "ok")))
    (is (= (if (persist/write-ok? "{\"status\":\"err\"}") 1 0) (get n "bad")))))
