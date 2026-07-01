;; murakumo.infer — fleet distributed inference, exo-style (bb command layer).
;;
;; The pure planning/engine core lives in murakumo.infer.plan / .engine (cljc —
;; portable into the JVM tests, cloud-murakumo, and kotoba WASM). This namespace
;; is the terminal operator: probe the fleet's live memory over SSH, cut the
;; memory-weighted shard plan, push binaries, run the ring, talk to the model.
;;
;;   bb murakumo infer probe                 live mem/disk/GPU map of the fleet
;;   bb murakumo infer plan  <model>         shard plan table + go/no-go gate
;;   bb murakumo infer provision [sel]       rsync rpc-server + raise GPU wired limit
;;   bb murakumo infer up|down|ps [sel]      start/stop/inspect the worker ring
;;   bb murakumo infer serve <model> <gguf>  run the head (llama-server, OpenAI API)
;;   bb murakumo infer generate "<prompt>"   one completion via the head's /v1 API

(ns murakumo.infer
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [murakumo.fleet :as fleet]
            [murakumo.infer.engine :as engine]
            [murakumo.infer.plan :as plan]
            [murakumo.ssh :as ssh]))

(def ^:private plan-file ".murakumo-infer-plan.edn")  ; last cut plan (control-plane state)
(def ^:private remote-bin "$HOME/.murakumo/bin")

(defn- load-config [] (edn/read-string (slurp "infer.edn")))

(defn- gib [x] (long (* x plan/GiB)))

(defn- infer-nodes
  "Mesh fleet ∪ :infer/extra-nodes, Tailscale-enriched."
  [cfg]
  (let [f (fleet/enrich (fleet/load-fleet))
        extra (:infer/extra-nodes cfg)]
    (concat (:nodes f) extra)))

(defn- probe-node
  "SSH one node for its live memory/disk/GPU facts → plan-ready node map (nil if
   unreachable). One retry — a fleet-wide parallel sweep can transiently drop an
   SSH handshake, and losing a node silently shrinks the plan."
  [cfg n]
  (let [probe #(ssh/sh (:host n)
                       "sysctl -n hw.memsize; df -k / | tail -1 | awk '{print $4}'; sysctl -n iogpu.wired_limit_mb 2>/dev/null || echo 0")
        r (probe)
        {:keys [exit out]} (if (zero? (:exit r)) r (probe))]
    (when (zero? exit)
      (let [[mem disk-k wired] (str/split-lines out)
            wired-mb (parse-long (str/trim wired))]
        (assoc n
               :mem-bytes (parse-long (str/trim mem))
               :disk-free-bytes (* 1024 (parse-long (str/trim disk-k)))
               :os-reserve-bytes (gib (:infer/node-os-reserve-gib cfg 5/2))
               ;; 0 = macOS default cap (~70 %); only an explicit raise is credited.
               :wired-limit-bytes (when (pos? wired-mb) (* 1024 1024 wired-mb))
               :rpc-cache? (>= (* 1024 (parse-long (str/trim disk-k)))
                               (gib (:infer/cache-min-free-gib cfg 20))))))))

(defn- probe-fleet
  "All inference workers probed in parallel + the head probed locally."
  [cfg]
  (let [workers (->> (infer-nodes cfg)
                     (pmap #(probe-node cfg %))
                     (filter some?)
                     vec)
        head-cfg (:infer/head cfg)
        head-mem (-> (p/sh "sysctl" "-n" "hw.memsize") :out str str/trim parse-long)]
    {:workers workers
     :head (assoc head-cfg
                  :head? true
                  :mem-bytes head-mem
                  :os-reserve-bytes (gib (:os-reserve-gib head-cfg 12)))}))

(defn- model-or-die [cfg id]
  (or (get (:models cfg) id)
      (do (println (str "unknown model " (pr-str id) " — known: "
                        (str/join ", " (keys (:models cfg)))))
          (System/exit 1))))

(defn- cut-plan
  "Probe + partition: workers in fleet order, the head as the LAST ring member
   (llama.cpp device order = RPC workers first, local GPU last)."
  [cfg model]
  (let [{:keys [workers head]} (probe-fleet cfg)]
    (plan/plan model (conj workers head))))

(defn cmd-probe [_cfg-args]
  (let [cfg (load-config)
        {:keys [workers head]} (probe-fleet cfg)]
    (println (format "%-10s %8s %10s %10s %8s" "NODE" "MEM-GIB" "USABLE-GIB" "DISK-FREE" "CACHE"))
    (doseq [n (conj workers head)]
      (println (format "%-10s %8.1f %10.2f %9.0fG %8s"
                       (:name n)
                       (/ (double (:mem-bytes n)) plan/GiB)
                       (/ (double (plan/usable-bytes n)) plan/GiB)
                       (/ (double (:disk-free-bytes n 0)) 1e9)
                       (str (boolean (:rpc-cache? n))))))))

(defn cmd-plan [[model-id]]
  (let [cfg (load-config)
        model (model-or-die cfg (or model-id "glm-5.2-reap50-q2k"))
        pl (cut-plan cfg model)]
    (println (format "model %s  weights %.1f GiB  layers %d"
                     (:model/id model)
                     (/ (double (:model/weight-bytes model)) plan/GiB)
                     (:model/layers model)))
    (println (format "%-10s %8s %10s %9s %8s %4s" "NODE" "MEM-GIB" "USABLE-GIB" "LAYERS" "EST-GIB" "OK"))
    (doseq [{:keys [name mem-gib usable-gib layers est-gib ok]} (plan/report pl)]
      (println (format "%-10s %8.1f %10.2f %4d-%-4d %8.2f %4s"
                       name mem-gib usable-gib (first layers) (second layers) est-gib ok)))
    (println (format "total usable %.1f GiB — %s"
                     (/ (double (:total-usable-bytes pl)) plan/GiB)
                     (if (:fits? pl) "FITS ✓" "DOES NOT FIT ✗")))
    (spit plan-file (pr-str pl))
    (println (str "plan → " plan-file))
    (when-not (:fits? pl) (System/exit 2))))

(defn- load-plan []
  (or (try (edn/read-string (slurp plan-file)) (catch Exception _ nil))
      (do (println "no plan — run `bb murakumo infer plan <model>` first")
          (System/exit 1))))

(defn- serving-workers
  "Plan assignments that serve layers, minus the head (it runs locally)."
  [pl]
  (engine/workers pl))

(defn cmd-provision
  "Push rpc-server to each serving worker + raise the GPU wired limit (needs the
   fleet's passwordless sudo; best-effort — a refusal only costs capacity)."
  [[sel]]
  (let [cfg (load-config)
        pl (load-plan)
        want (when (and sel (not= sel "all")) (set (str/split sel #",")))
        wired (:infer/wired-limit-mb cfg)]
    (doseq [{:keys [node]} (serving-workers pl)
            :when (or (nil? want) (want (:name node)))]
      (print (format "[%s] " (:name node))) (flush)
      (let [host (:host node)]
        (if-not (ssh/reachable? host)
          (println "unreachable — skipped")
          (do (ssh/sh host (str "mkdir -p " remote-bin))
              (let [{:keys [exit err]} (ssh/scp host "bin/rpc-server" (str remote-bin "/rpc-server"))]
                (if-not (zero? exit)
                  (println (str "scp failed: " err))
                  (let [w (ssh/sh host (format "sudo -n sysctl iogpu.wired_limit_mb=%d 2>&1 || echo no-sudo" wired))]
                    (println (str "rpc-server ✓  wired-limit: " (:out w))))))))))))

(defn cmd-up [[sel]]
  (let [cfg (load-config)
        pl (load-plan)
        port (:infer/rpc-port cfg engine/default-rpc-port)
        want (when (and sel (not= sel "all")) (set (str/split sel #",")))]
    (doseq [{:keys [node]} (serving-workers pl)
            :when (or (nil? want) (want (:name node)))]
      (let [cache (if (:rpc-cache? node) " -c" "")
            cmd (format "pkill -f '%s/rpc-server' 2>/dev/null; sleep 0.2; nohup %s/rpc-server -H 0.0.0.0 -p %d%s >/tmp/murakumo-rpc.log 2>&1 & sleep 0.3; pgrep -f rpc-server >/dev/null && echo up || echo FAILED"
                        remote-bin remote-bin port cache)]
        (println (format "[%s] %s" (:name node) (:out (ssh/sh (:host node) cmd))))))))

(defn cmd-down [[sel]]
  (let [pl (load-plan)
        want (when (and sel (not= sel "all")) (set (str/split sel #",")))]
    (doseq [{:keys [node]} (serving-workers pl)
            :when (or (nil? want) (want (:name node)))]
      (ssh/sh (:host node) "pkill -f '.murakumo/bin/rpc-server'")
      (println (format "[%s] down" (:name node))))))

(defn cmd-ps [_]
  (let [pl (load-plan)]
    (doseq [{:keys [node layers]} (serving-workers pl)]
      (let [{:keys [out]} (ssh/sh (:host node) "pgrep -f '.murakumo/bin/rpc-server' >/dev/null && echo running || echo stopped")]
        (println (format "[%-10s] %-8s layers %d-%d" (:name node) out (first layers) (second layers)))))))

(defn cmd-serve
  "Run the head llama-server in the foreground (^C stops serving, workers stay up)."
  [[model-id gguf-path]]
  (let [cfg (load-config)
        model (model-or-die cfg (or model-id "glm-5.2-reap50-q2k"))
        pl (load-plan)
        cmd (engine/head-cmd pl {:bin-dir "bin"
                                 :model-path (or gguf-path (:model/gguf model))
                                 :rpc-port (:infer/rpc-port cfg engine/default-rpc-port)
                                 :port (:infer/api-port cfg 8080)
                                 :ctx (:infer/ctx cfg 4096)})]
    (println cmd)
    (p/shell cmd)))

(defn cmd-generate [[prompt]]
  (let [cfg (load-config)
        body (json/generate-string
              {:messages [{:role "user" :content (or prompt "Name three Japanese cities.")}]
               :max_tokens 256})
        {:keys [out]} (p/sh "curl" "-s" "-m" "600"
                            (str "http://localhost:" (:infer/api-port cfg 8080) "/v1/chat/completions")
                            "-H" "Content-Type: application/json" "-d" body)]
    (let [r (json/parse-string (str out) true)]
      (println (or (get-in r [:choices 0 :message :content]) out))
      (when-let [t (:timings r)]
        (println (format "-- %.2f tok/s prefill, %.2f tok/s gen"
                         (double (:prompt_per_second t 0.0))
                         (double (:predicted_per_second t 0.0))))))))

(defn -main [& [cmd & args]]
  (case cmd
    "probe" (cmd-probe args)
    "plan" (cmd-plan args)
    "provision" (cmd-provision args)
    "up" (cmd-up args)
    "down" (cmd-down args)
    "ps" (cmd-ps args)
    "serve" (cmd-serve args)
    "generate" (cmd-generate args)
    (println "usage: bb murakumo infer probe|plan <model>|provision [sel]|up|down|ps|serve <model> [gguf]|generate \"<prompt>\"")))
