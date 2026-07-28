;; W6 pure-planner oracle: murakumo.infer.engine cmd assembly subset
;; vs kotoba/infer_engine_core.kotoba.

(ns murakumo.infer-engine-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.engine :as engine]))

(def port-source (slurp "kotoba/infer_engine_core.kotoba"))

(def export-prefix
  "default-rpc-port digit-char nat-str i64-str split-mode-name endpoint rpc-server-cmd embed-head-front embed-head-back")

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

(defn- split-mode-cljc [strategy]
  (case strategy :tensor "row" "layer"))

(defn- one-worker-plan [node]
  {:assignments [{:span 1 :node (merge {:head? false} node)}
                 {:span 0 :node {:name "head" :host "head" :head? true}}]})

(deftest default-rpc-port-matches
  (let [actual (compile-i64-cases {"p" "(default-rpc-port)"})]
    (is (= engine/default-rpc-port (get actual "p")))))

(deftest split-mode-name-matches-head-cmd-case
  (let [corpus ["tensor" "pipeline" "expert" "other"]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "sm_" i)
                           (str "(split-mode-name " (kotoba-literal s) ")")])
                        corpus))
        actual (compile-string-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing s
        (is (= (split-mode-cljc (keyword s))
               (get actual (str "sm_" i))))))))

(deftest endpoint-matches-rpc-endpoints-element
  (let [corpus [["10.0.0.1" 50052]
                ["host.local" 8080]
                ["::1" 1]]
        cases (into {} (map-indexed
                        (fn [i [h p]]
                          [(str "ep_" i)
                           (str "(endpoint " (kotoba-literal h) " " p ")")])
                        corpus))
        actual (compile-string-cases cases)]
    (doseq [[i [h p]] (map-indexed vector corpus)]
      (testing (pr-str [h p])
        (is (= (engine/rpc-endpoints [{:ip h :port p}])
               (get actual (str "ep_" i))))))))

(deftest rpc-server-cmd-matches-rpc-worker-cmds
  (let [corpus [{:bin-dir "/opt/llama" :port 50052 :device "MTL0"
                 :node {:name "w" :host "w" :ip "1.2.3.4"}
                 :cache-dir nil}
                {:bin-dir "/b" :port 50052 :device "CUDA0"
                 :node {:name "w" :host "w" :ip "9.9.9.9" :rpc-cache? false}
                 :cache-dir nil}
                {:bin-dir "/b" :port 60000 :device "MTL0"
                 :node {:name "w" :host "w" :ip "1.1.1.1"}
                 :cache-dir "/var/cache/rpc"}]
        call (fn [{:keys [bin-dir port device node cache-dir]}]
               (let [cache? (not (false? (:rpc-cache? node)))
                     cache (if cache? 1 0)
                     cdir (or cache-dir "")]
                 (str "(rpc-server-cmd " (kotoba-literal bin-dir) " " port " "
                      (kotoba-literal device) " " cache " "
                      (kotoba-literal cdir) ")")))
        cases (into {} (map-indexed (fn [i m] [(str "rpc_" i) (call m)]) corpus))
        actual (compile-string-cases cases)]
    (doseq [[i m] (map-indexed vector corpus)]
      (let [opts (cond-> {:bin-dir (:bin-dir m)
                          :port (:port m)
                          :device (:device m)}
                   (:cache-dir m) (assoc :cache-dir (:cache-dir m)))
            cmds (engine/rpc-worker-cmds (one-worker-plan (:node m)) opts)]
        (testing (pr-str m)
          (is (= 1 (count cmds)))
          (is (= (:cmd (first cmds))
                 (get actual (str "rpc_" i)))))))))

(deftest embed-head-cmd-matches-engine
  ;; ABI max arity 5 → front+back joined in the case body.
  (let [corpus [{:bin-dir "/opt/llama" :model-path "/m.gguf"}
                {:bin-dir "/b" :model-path "/e.gguf" :port 9000 :ctx 4096
                 :pooling "cls" :parallel 2}
                {:bin-dir "/x" :model-path "/y" :port 8091 :ctx 8192
                 :pooling "mean" :parallel 4}]
        call (fn [{:keys [bin-dir model-path port ctx pooling parallel]
                   :or {port 8091 ctx 8192 pooling "mean" parallel 4}}]
               (str "(string-concat (embed-head-front "
                    (kotoba-literal bin-dir) " " (kotoba-literal model-path) " "
                    (kotoba-literal pooling) " " ctx ") (embed-head-back "
                    parallel " " port "))"))
        cases (into {} (map-indexed (fn [i m] [(str "em_" i) (call m)]) corpus))
        actual (compile-string-cases cases)]
    (doseq [[i m] (map-indexed vector corpus)]
      (testing (pr-str m)
        (is (= (engine/embed-head-cmd m)
               (get actual (str "em_" i))))))))