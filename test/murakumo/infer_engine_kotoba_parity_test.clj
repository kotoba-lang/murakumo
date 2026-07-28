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
  (str "default-rpc-port digit-char nat-str i64-str split-mode-name endpoint "
       "rpc-server-cmd embed-head-front embed-head-back "
       "mlx-moe-bin mlx-moe-front opt-i64-flag opt-str-flag "
       "tensor-split-3 mlx-launch-front"))

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

(defn- mlx-moe-join
  "Join kotoba front + optional flags the same way cljc mlx-moe-cmd does."
  [{:keys [venv model-repo port capacity pin-top-k kv-bits profile warmup]
    :or {port 8080}}]
  (let [v (or venv "")
        front (str "(mlx-moe-front " (kotoba-literal v) " "
                   (kotoba-literal model-repo) " " port ")")
        flags [(str "(opt-i64-flag " (kotoba-literal " --capacity") " "
                    (long (or capacity 0)) " " (if capacity 1 0) ")")
               (str "(opt-i64-flag " (kotoba-literal " --pin-top-k") " "
                    (long (or pin-top-k 0)) " " (if pin-top-k 1 0) ")")
               (str "(opt-i64-flag " (kotoba-literal " --kv-bits") " "
                    (long (or kv-bits 0)) " " (if kv-bits 1 0) ")")
               (str "(opt-str-flag " (kotoba-literal " --profile") " "
                    (kotoba-literal (or profile "")) " " (if profile 1 0) ")")
               (str "(opt-str-flag " (kotoba-literal " --warmup") " "
                    (kotoba-literal (or warmup "")) " " (if warmup 1 0) ")")]
        nest (reduce (fn [acc f] (str "(string-concat " acc " " f ")"))
                     front
                     flags)]
    nest))

(deftest mlx-moe-cmd-matches-engine
  (let [corpus [{:venv "/opt/mlx" :model-repo "org/model" :port 8080}
                {:venv nil :model-repo "m" :port 9000 :capacity 8}
                {:venv "/v" :model-repo "r" :port 8080 :capacity 4
                 :pin-top-k 2 :kv-bits 8 :profile "fast" :warmup "1"}]
        cases (into {} (map-indexed (fn [i m] [(str "moe_" i) (mlx-moe-join m)]) corpus))
        actual (compile-string-cases cases)]
    (doseq [[i m] (map-indexed vector corpus)]
      (let [opts (cond-> {:model-repo (:model-repo m) :port (:port m)}
                   (:venv m) (assoc :venv (:venv m))
                   (:capacity m) (assoc :capacity (:capacity m))
                   (:pin-top-k m) (assoc :pin-top-k (:pin-top-k m))
                   (:kv-bits m) (assoc :kv-bits (:kv-bits m))
                   (:profile m) (assoc :profile (:profile m))
                   (:warmup m) (assoc :warmup (:warmup m)))]
        (testing (pr-str m)
          (is (= (engine/mlx-moe-cmd opts)
                 (get actual (str "moe_" i)))))))))

(deftest tensor-split-3-matches-engine
  (let [plan {:assignments
              [{:span 3 :node {:name "w0" :host "w0" :head? false}}
               {:span 2 :node {:name "w1" :host "w1" :head? false}}
               {:span 1 :node {:name "h" :host "h" :head? true}}]}
        want (engine/tensor-split plan)
        actual (compile-string-cases
                {"ts" "(tensor-split-3 3 2 1)"
                 "ts0" "(tensor-split-3 0 0 5)"})]
    (is (= want (get actual "ts")))
    (is (= "0,0,5" (get actual "ts0")))))

(deftest mlx-launch-front-matches-engine-prefix
  (let [plan {:assignments [{:span 1 :node {:name "n" :host "h" :head? true}}]}
        opts {:venv "/opt/mlx" :hosts-file "/tmp/hosts.json"
              :model-repo "org/m" :max-tokens 64
              :prompt "hello"}
        full (engine/mlx-launch-cmd plan opts)
        prefix (subs full 0 (str/index-of full " --prompt "))
        actual (compile-string-cases
                {"lf" (str "(mlx-launch-front "
                           (kotoba-literal "/opt/mlx") " "
                           (kotoba-literal "/tmp/hosts.json") " "
                           (kotoba-literal "org/m") " 64)")
                 "bin" (str "(mlx-moe-bin " (kotoba-literal "") ")")
                 "bin2" (str "(mlx-moe-bin " (kotoba-literal "/x") ")")})]
    (is (= prefix (get actual "lf")))
    (is (= "mlx-moe" (get actual "bin")))
    (is (= "/x/bin/mlx-moe" (get actual "bin2")))))
