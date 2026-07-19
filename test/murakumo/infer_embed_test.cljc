;; Offline unit tests for the pure llama.cpp single-node embedding engine
;; (ADR-2607192200 2026-07-19 addendum). No fleet/SSH — mirrors
;; infer_moe_test.cljc's shape for the sibling single-node engine.
(ns murakumo.infer-embed-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.engine :as engine]))

(deftest embed-head-cmd-defaults
  (testing "default port/ctx/pooling/parallel, no extra-args"
    (is (= (str "bin/llama-server -m model.gguf --embedding --pooling mean"
                " -ngl 999 -c 8192 --parallel 4 --host 0.0.0.0 --port 8091")
           (engine/embed-head-cmd {:bin-dir "bin" :model-path "model.gguf"})))))

(deftest embed-head-cmd-overrides
  (testing "every knob is overridable, and extra-args are appended verbatim"
    (let [cmd (engine/embed-head-cmd {:bin-dir "/home/gad/.murakumo/bin"
                                       :model-path "/home/gad/models/bge-m3-embed-gguf/bge-m3-Q8_0.gguf"
                                       :port 9091
                                       :ctx 4096
                                       :pooling "cls"
                                       :parallel 2
                                       :extra-args ["--verbose"]})]
      (is (re-find #"^/home/gad/\.murakumo/bin/llama-server -m /home/gad/models/bge-m3-embed-gguf/bge-m3-Q8_0\.gguf" cmd))
      (is (re-find #"--embedding --pooling cls" cmd))
      (is (re-find #"-c 4096" cmd))
      (is (re-find #"--parallel 2" cmd))
      (is (re-find #"--port 9091" cmd))
      (is (re-find #"--verbose$" cmd)))))

(deftest embed-head-cmd-omits-extra-args-when-absent
  (testing "no trailing space / extra-args segment when :extra-args is empty or nil"
    (is (not (re-find #"\s$" (engine/embed-head-cmd {:bin-dir "bin" :model-path "m"}))))
    (is (not (re-find #"\s$" (engine/embed-head-cmd {:bin-dir "bin" :model-path "m" :extra-args []}))))))

(deftest commands-llamacpp-embed-dispatch
  (testing "commands dispatches :llamacpp-embed to embed-head-cmd, ignoring `plan`
            (single-node, no ring — same posture as :mlx-moe) and does not
            touch :workers/:hosts the way the ring/mlx-ring engines do"
    (let [{:keys [head] :as result} (engine/commands nil :llamacpp-embed
                                                      {:bin-dir "bin" :model-path "m" :port 8091})]
      (is (= "bin/llama-server -m m --embedding --pooling mean -ngl 999 -c 8192 --parallel 4 --host 0.0.0.0 --port 8091"
             (:cmd head)))
      (is (not (contains? result :workers)))
      (is (not (contains? result :hosts))))))

(deftest existing-engines-unaffected
  (testing "adding :llamacpp-embed does not change the pre-existing engines'
            dispatch keys or output shape (regression guard for the shared
            production chat/mlx-ring/mlx-moe engines)"
    (is (= #{:workers :head} (set (keys (engine/commands {:assignments []} :llamacpp-rpc
                                                          {:bin-dir "bin" :model-path "m"})))))
    (is (= #{:hosts :head} (set (keys (engine/commands {:assignments []} :mlx-ring
                                                        {:hosts-file "h" :venv "v" :model-repo "r"})))))
    (is (= #{:head} (set (keys (engine/commands nil :mlx-moe {:model-repo "r"})))))))
