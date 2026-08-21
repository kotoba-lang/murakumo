;; Offline unit tests for the pure inference planner/engine (no fleet, no SSH).
(ns murakumo.infer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [murakumo.infer :as infer]
            [murakumo.infer.engine :as engine]
            [murakumo.infer.plan :as plan]))

(def GiB plan/GiB)

(defn- mini [name] {:name name :host name :mem-bytes (* 16 GiB)
                    :os-reserve-bytes (* 5/2 GiB)})
(def head {:name "head" :host "localhost" :head? true :mem-bytes (* 32 GiB)
           :os-reserve-bytes (* 12 GiB)})

(def glm {:model/id "glm-5.2-reap50-q2k" :model/format :gguf
          :model/layers 78 :model/weight-bytes 139000000000})

(deftest physical-head-is-not-an-rpc-worker
  (let [head-cfg {:name "head" :node-name "gad"
                  :host "root@100.82.98.110"}
        nodes [{:name "asher" :host "asher" :ip "100.96.122.69"}
               {:name "gad" :host "gad" :ip "100.82.98.110"}
               {:name "alias" :host "root@100.82.98.110"}
               {:name "xavier" :host "xavier" :ip "100.87.226.80"}]]
    (testing "explicit inventory identity wins even when SSH names differ"
      (is (#'infer/same-physical-node? head-cfg (second nodes))))
    (testing "host/IP aliases of the same conductor are also excluded"
      (is (#'infer/same-physical-node? (dissoc head-cfg :node-name) (second nodes)))
      (is (#'infer/same-physical-node? (dissoc head-cfg :node-name) (nth nodes 2))))
    (testing "only the physical head is removed from the worker probe set"
      (is (= ["asher" "xavier"]
             (mapv :name (#'infer/worker-nodes {:infer/head head-cfg} nodes)))))
    (testing "missing optional identities do not make nil equal nil"
      (is (not (#'infer/same-physical-node? {} {:name "worker"}))))))

(deftest usable-memory
  (testing "16 GiB worker: 16 − 2.5 os − 1.25 headroom = 12.25 GiB"
    (is (= (* 49/4 GiB) (plan/usable-bytes (mini "a")))))
  (testing "an explicit wired limit caps the ceiling"
    (is (= (- (* 10 GiB) (* 5/4 GiB))
           (plan/usable-bytes (assoc (mini "a") :wired-limit-bytes (* 10 GiB))))))
  (testing "a node smaller than the reserves contributes nothing"
    (is (zero? (plan/usable-bytes {:mem-bytes (* 2 GiB)})))))

(deftest partition-is-contiguous-and-complete
  (let [nodes (conj (mapv mini ["a" "b" "c" "d"]) head)
        asg (plan/partition-layers glm nodes)]
    (testing "every layer assigned exactly once, in ring order"
      (is (= 78 (reduce + (map :span asg))))
      (is (= (map (comp second :layers) asg)
             (rest (concat (map (comp first :layers) asg) [78])))))
    (testing "spans are memory-proportional: the 32 GiB head gets the biggest slice"
      (is (apply = (map :span (butlast asg))))
      (is (> (:span (last asg)) (:span (first asg)))))))

(deftest fits-gate
  (testing "11 workers + head hold 139 GB of GLM-5.2 REAP50 Q2_K"
    (let [nodes (conj (mapv #(mini (str "m" %)) (range 11)) head)
          pl (plan/plan glm nodes)]
      (is (:fits? pl))
      (is (every? :fits? (:assignments pl)))))
  (testing "the same fleet does NOT hold the 214 GiB MLX 4-bit — the honest gate"
    (let [nodes (conj (mapv #(mini (str "m" %)) (range 11)) head)]
      (is (not (:fits? (plan/plan (assoc glm :model/weight-bytes 229780000000) nodes))))))
  (testing "zero-memory fleet plans to zero, not to a crash"
    (is (not (:fits? (plan/plan glm [{:mem-bytes 0}]))))))

(deftest dense-layer-aware-partition
  (testing "GLM-5.2's 3 light dense layers let ELEVEN 16 GiB ranks hold what
            uniform layer math says they cannot"
    (let [glm-dense (assoc glm :model/dense-layers 3 :model/dense-layer-frac 1/10)
          ranks (mapv #(mini (str "m" %)) (range 11))
          pl (plan/plan glm-dense ranks)
          first-asg (first (:assignments pl))]
      (is (:fits? pl))
      (is (> (:span first-asg) 7) "the dense-heavy first shard takes extra layers")
      (is (= 78 (reduce + (map :span (:assignments pl)))))))
  (testing "without dense info the same fleet is honestly rejected"
    (is (not (:fits? (plan/plan glm (mapv #(mini (str "m" %)) (range 11))))))))

(deftest deterministic
  (let [nodes (conj (mapv mini ["a" "b" "c"]) head)]
    (is (= (plan/plan glm nodes) (plan/plan glm nodes)))))

(deftest strategy-choice
  (testing "1 GbE fleet → pipeline, whatever the model shape"
    (is (= :pipeline (:strategy (plan/choose-strategy
                                 {:link-gbps 1 :ranks 12
                                  :model {:model/experts 128 :model/kv-heads 4}})))))
  (testing "Thunderbolt-class + divisible kv-heads → tensor"
    (is (= :tensor (:strategy (plan/choose-strategy
                               {:link-gbps 40 :ranks 4
                                :model {:model/experts 128 :model/kv-heads 8}})))))
  (testing "fast link, indivisible heads, MoE → expert"
    (is (= :expert (:strategy (plan/choose-strategy
                               {:link-gbps 40 :ranks 5
                                :model {:model/experts 128 :model/kv-heads 8}})))))
  (testing "no link measurement → conservative pipeline"
    (is (= :pipeline (:strategy (plan/choose-strategy {:ranks 3 :model {}}))))))

(deftest strategy-command-emission
  (let [pl (plan/plan glm [(assoc (mini "a") :ip "100.0.0.1") head])]
    (is (re-find #"--split-mode row" (engine/head-cmd pl {:bin-dir "bin" :model-path "m" :strategy :tensor})))
    (is (re-find #"--split-mode layer" (engine/head-cmd pl {:bin-dir "bin" :model-path "m"})))
    (is (re-find #"-ot \"exps=CPU\"" (engine/head-cmd pl {:bin-dir "bin" :model-path "m"
                                                          :strategy :expert :moe-override "exps=CPU"})))
    (is (re-find #"--api-key-file \"/run/secrets/fleet.keys\""
                 (engine/head-cmd pl {:bin-dir "bin" :model-path "m"
                                      :api-key-file "/run/secrets/fleet.keys"})))))

(deftest llamacpp-rpc-commands
  (let [nodes [(assoc (mini "a") :ip "100.0.0.1")
               (assoc (mini "b") :ip "100.0.0.2" :rpc-cache? false :rpc-port 40052)
               head]
        pl (plan/plan glm nodes)
        {:keys [workers head]} (engine/commands pl :llamacpp-rpc
                                                {:bin-dir "bin" :model-path "m.gguf"})]
    (testing "one rpc-server per worker — the head is NOT a worker"
      (is (= ["a" "b"] (map :name workers)))
      (is (re-find #"-d MTL0 -c$" (:cmd (first workers))))
      (is (not (re-find #"-c$" (:cmd (second workers))))))
    (testing "head drives the ring: endpoints in order, tensor-split = workers + head last"
      (is (re-find #"--rpc 100\.0\.0\.1:50052,100\.0\.0\.2:40052 " (:cmd head)))
      (is (re-find #"--tensor-split \d+,\d+,\d+ " (:cmd head))))))

(deftest distributed-head-request-parallelism
  (let [pl (plan/plan glm [(assoc (mini "a") :ip "100.0.0.1") head])
        cmd (engine/head-cmd pl {:bin-dir "bin" :model-path "m.gguf"
                                 :ctx 524288 :parallel 2})]
    (testing "two slots preserve the model's 262144-token request window"
      (is (re-find #"-c 524288 --parallel 2 " cmd)))))

(deftest mlx-ring-commands
  (let [pl (plan/plan glm [(assoc (mini "a") :ip "100.0.0.1") head])
        {:keys [hosts head]} (engine/commands pl :mlx-ring
                                              {:hosts-file "hosts.json" :venv "~/mlxlm"
                                               :model-repo "mlx-community/GLM-5.2-4bit"})]
    (is (= [{:ssh "a" :ips ["100.0.0.1"]} {:ssh "localhost" :ips ["localhost"]}] hosts))
    (is (re-find #"--backend ring" (:cmd head)))))

(deftest model-dir-resolution
  (let [head-cfg {:model-dir "/home/gad/models/GLM-5.2-REAP50-Q2_K-GGUF"}]
    (testing "a model with its own :model/dir wins over the head's global :model-dir"
      (is (= "/home/gad/models/Qwen-AgentWorld-35B-A3B-GGUF"
             (#'infer/model-dir head-cfg {:model/dir "/home/gad/models/Qwen-AgentWorld-35B-A3B-GGUF"}))))
    (testing "a model without :model/dir falls back to the head's global :model-dir"
      (is (= "/home/gad/models/GLM-5.2-REAP50-Q2_K-GGUF"
             (#'infer/model-dir head-cfg {:model/id "glm-5.2-reap50-q2k"}))))))

(deftest standalone-systemd-unit
  (let [unit (#'infer/standalone-unit "/opt/bin/llama-server -m /m.gguf --port 8090")]
    (testing "unit embeds the exact serve command and self-heals on crash"
      (is (re-find #"ExecStart=/opt/bin/llama-server -m /m\.gguf --port 8090\n" unit))
      (is (re-find #"Restart=on-failure" unit))
      (is (re-find #"WantedBy=multi-user\.target" unit)))
    (testing "runs as the gad user like the existing llama-server.service"
      (is (re-find #"User=gad" unit)))))

(deftest distributed-ring-systemd-unit
  (let [unit (#'infer/ring-unit
              "/opt/bin/llama-server -m /m.gguf --rpc 10.0.0.1:50052 --port 8090"
              [{:ip "10.0.0.1" :port 50052}])]
    (testing "persists and self-heals the exact distributed head command"
      (is (re-find #"ExecStart=/opt/bin/llama-server .*--rpc 10\.0\.0\.1:50052.*--port 8090\n" unit))
      (is (re-find #"Restart=on-failure" unit))
      (is (re-find #"WantedBy=multi-user\.target" unit)))
    (testing "waits for every rank instead of accepting a partial ring"
      (is (re-find #"ExecStartPre=.*nc -z -w 2 10\.0\.0\.1 50052" unit))
      (is (re-find #"-ge 60 \]" unit))
      (is (re-find #"done; sleep 5;" unit)))
    (testing "runs as the model-owning gad user"
      (is (re-find #"User=gad" unit)))))

(deftest distributed-ring-watchdog-unwedges-an-active-head
  (let [specs [{:target "naphtali@10.0.0.1" :ip "10.0.0.1" :port 50052}
               {:target "asher@10.0.0.2" :ip "10.0.0.2" :port 40052}]
        unit (#'infer/ring-watchdog-unit 8090)
        script (#'infer/ring-watchdog-script 8090 specs)
        timer (#'infer/ring-watchdog-timer)]
    (testing "probes the scheduler rather than trusting process-active"
      (is (re-find #"/usr/local/sbin/murakumo-ring-watchdog" unit))
      (is (re-find #"http://127\.0\.0\.1:8090/slots" script))
      (is (re-find #"--max-time 10" script)))
    (testing "does not mistake an active decode for an idle wedge"
      (is (re-find #"ss -Htn state established '\( sport = :8090 \)'" script))
      (is (re-find #"record busy" script))
      (is (re-find #"sleep 5" script))
      (is (= 2 (count (re-seq #"http://127\.0\.0\.1:8090/slots" script)))))
    (testing "keeps a bounded machine-readable soak ledger"
      (is (re-find #"ring-watchdog-events\.log" script))
      (let [logrotate (#'infer/ring-watchdog-logrotate)]
        (is (re-find #"weekly" logrotate))
        (is (re-find #"rotate 12" logrotate))))
    (testing "does not kill the measured four-minute cold load"
      (is (re-find #"-lt 420" script)))
    (testing "rebuilds the complete RPC failure domain before the head"
      (is (< (.indexOf script "systemctl stop murakumo-ring.service")
             (.indexOf script "naphtali@10.0.0.1")
             (.indexOf script "nc -z -w 2 10.0.0.1 50052")
             (.indexOf script "systemctl start murakumo-ring.service")))
      (is (re-find #"asher@10\.0\.0\.2" script))
      (is (re-find #"rpc-ha-key" script)))
    (testing "runs repeatedly without an external cron"
      (is (re-find #"OnUnitActiveSec=30s" timer))
      (is (re-find #"WantedBy=timers\.target" timer)))))

(deftest rpc-worker-launchd-service-is-resident-and-constrained
  (let [plist (#'infer/rpc-worker-plist "asher" "/Users/asher" 40052 "MTL0" true)]
    (is (re-find #"com\.murakumo\.rpc-worker" plist))
    (is (re-find #"<key>UserName</key><string>asher</string>" plist))
    (is (re-find #"/Users/asher/\.murakumo/bin/rpc-server" plist))
    (is (re-find #"<string>-p</string><string>40052</string>" plist))
    (is (re-find #"<string>-c</string>" plist))
    (is (re-find #"<key>KeepAlive</key><true/>" plist)))
  (let [entry (#'infer/rpc-ha-authorized-key
               "ssh-ed25519 AAAATEST murakumo-rpc-ha")]
    (is (str/starts-with? entry "restrict,command="))
    (is (str/includes? entry "launchctl kickstart -k system/com.murakumo.rpc-worker"))
    (is (str/ends-with? entry "ssh-ed25519 AAAATEST murakumo-rpc-ha"))))
