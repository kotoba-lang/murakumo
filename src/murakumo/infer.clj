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
;;
;; A model whose registry entry carries `:model/engine :mlx-moe` (mu-hashmi/
;; mlx-moe) skips the fleet-wide ring entirely: `plan`/`provision`/`serve` cut
;; over to murakumo.infer.moe's single-node planner instead of
;; murakumo.infer.plan's layer partition — see cmd-plan-moe / cmd-serve-moe.

(ns murakumo.infer
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [murakumo.fleet :as fleet]
            [murakumo.infer.engine :as engine]
            [murakumo.infer.moe :as moe]
            [murakumo.infer.plan :as plan]
            [murakumo.ssh :as ssh]))

(def ^:private plan-file ".murakumo-infer-plan.edn")  ; last cut plan (control-plane state)
(def ^:private remote-bin "$HOME/.murakumo/bin")
(def ^:private remote-env (str "DYLD_LIBRARY_PATH=" remote-bin ":$DYLD_LIBRARY_PATH "))

(defn- load-config [] (edn/read-string (slurp "infer.edn")))

(defn- api-host
  "Host name for HTTP clients. SSH hosts may be written user@host."
  [node]
  (or (:api-host node)
      (some-> (:host node) (str/split #"@") last)))

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
                       "if sysctl -n hw.memsize >/dev/null 2>&1; then sysctl -n hw.memsize; else awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo; fi; df -k / | tail -1 | awk '{print $4}'; sysctl -n iogpu.wired_limit_mb 2>/dev/null || echo 0")
        r (probe)
        {:keys [exit out]} (if (zero? (:exit r)) r (probe))]
    (when (zero? exit)
      (let [[mem disk-k wired] (str/split-lines out)
            wired-mb (parse-long (str/trim wired))]
        (assoc n
               :mem-bytes (parse-long (str/trim mem))
               :disk-free-bytes (* 1024 (parse-long (str/trim disk-k)))
               :os-reserve-bytes (gib (:infer/node-os-reserve-gib cfg 5/2))
               :headroom-bytes (gib (:infer/node-headroom-gib cfg 5/4))
               ;; 0 = macOS default cap (~70 %); only an explicit raise is credited.
               :wired-limit-bytes (when (pos? wired-mb) (* 1024 1024 wired-mb))
               :rpc-cache? (>= (* 1024 (parse-long (str/trim disk-k)))
                               (gib (:infer/cache-min-free-gib cfg 20))))))))

(defn- probe-fleet
  "All inference workers probed in parallel + the head (local operator machine,
   or a fleet node when the head is :remote? — the operator being off the fleet
   LAN would otherwise put a WAN hop inside every token)."
  [cfg]
  (let [head-cfg (:infer/head cfg)
        workers (->> (infer-nodes cfg)
                     (remove #(= (:host %) (:host head-cfg)))   ; head serves, but not as an rpc worker
                     (pmap #(probe-node cfg %))
                     (filter some?)
                     vec)
        head (if (:remote? head-cfg)
               (probe-node cfg head-cfg)
               (let [mem (-> (p/sh "sysctl" "-n" "hw.memsize") :out str str/trim parse-long)
                     disk-k (-> (p/sh "sh" "-c" "df -k / | tail -1 | awk '{print $4}'")
                                :out str str/trim parse-long)
                     wired-mb (-> (p/sh "sh" "-c" "sysctl -n iogpu.wired_limit_mb 2>/dev/null || echo 0")
                                  :out str str/trim parse-long)]
                 (assoc head-cfg
                        :mem-bytes mem
                        :disk-free-bytes (* 1024 disk-k)
                        :headroom-bytes (gib (:infer/head-headroom-gib cfg
                                               (:infer/node-headroom-gib cfg 5/4)))
                        :wired-limit-bytes (when (pos? wired-mb) (* 1024 1024 wired-mb))
                        :rpc-cache? (>= (* 1024 disk-k)
                                        (gib (:infer/cache-min-free-gib cfg 20))))))]
    {:workers workers
     :head (assoc head
                  :head? true
                  :os-reserve-bytes (gib (:os-reserve-gib head-cfg 12))
                  :headroom-bytes (gib (:infer/head-headroom-gib cfg
                                         (:infer/node-headroom-gib cfg 5/4))))}))

(defn- model-or-die [cfg id]
  (or (get (:models cfg) id)
      (do (println (str "unknown model " (pr-str id) " — known: "
                        (str/join ", " (keys (:models cfg)))))
          (System/exit 1))))

(defn- moe? [model] (= :mlx-moe (:model/engine model)))

(defn- moe-opt
  [cfg model k]
  (or (get model (keyword "model" (name k)))
      (get-in cfg [:infer/mlx-moe k])))

(defn- probe-and-plan
  "Probe the fleet + :infer/extra-nodes and hand every candidate to `plan-fn`
   (murakumo.infer.plan/plan or murakumo.infer.moe/plan — same 2-arg shape,
   [model nodes])."
  [cfg model plan-fn]
  (let [{:keys [workers head]} (probe-fleet cfg)]
    (plan-fn model (conj workers head))))

(defn- cut-plan
  "Probe + partition: workers in fleet order, the head as the LAST ring member
   (llama.cpp device order = RPC workers first, local GPU last)."
  [cfg model]
  (probe-and-plan cfg model plan/plan))

(defn- cut-plan-moe
  "Probe the fleet + :infer/extra-nodes and hand every candidate to
   murakumo.infer.moe/plan — it alone picks the single best-memory node (no
   ring, so the :infer/head config doesn't apply here)."
  [cfg model]
  (probe-and-plan cfg model moe/plan))

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

(defn- cmd-plan-moe
  "mlx-moe plan report: one candidate node, its mlx-moe capacity, and the
   README's honest 'does this model benefit' verdict — no per-layer table,
   there is no ring to lay out."
  [cfg model]
  (let [pl (cut-plan-moe cfg model)
        {:keys [node] :as asg} (first (:assignments pl))
        {:keys [verdict why]} (:verdict pl)]
    (println (format "model %s  engine mlx-moe (single-node, SSD-paged experts)"
                     (:model/id model)))
    (if node
      (println (format "candidate %-10s usable %.1f GiB  capacity %s  est-resident %.1f GiB  %s"
                       (:name node)
                       (/ (double (:total-usable-bytes pl)) plan/GiB)
                       (or (:capacity pl) "-")
                       (/ (double (:est-bytes asg 0)) plan/GiB)
                       (if (:fits? pl) "FITS ✓" "DOES NOT FIT ✗")))
      (println "no candidate node — probe found nothing"))
    (println (str "verdict: " (name verdict) " — " why))
    (spit plan-file (pr-str pl))
    (println (str "plan → " plan-file))
    (when-not (:fits? pl) (System/exit 2))))

(defn cmd-plan [[model-id]]
  (let [cfg (load-config)
        model (model-or-die cfg (or model-id "glm-5.2-reap50-q2k"))]
    (if (moe? model)
      (cmd-plan-moe cfg model)
      (let [pl (cut-plan cfg model)]
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
        (when-not (:fits? pl) (System/exit 2))))))

(defn- load-plan []
  (or (try (edn/read-string (slurp plan-file)) (catch Exception _ nil))
      (do (println "no plan — run `bb murakumo infer plan <model>` first")
          (System/exit 1))))

(defn- serving-workers
  "Plan assignments that serve layers, minus the head (it runs locally)."
  [pl]
  (engine/workers pl))

(defn- moe-node [pl] (:node (first (:assignments pl))))

(defn- cmd-provision-moe
  "mlx-moe has no rpc-server/llama-server binary to push — it's a pip package.
   Install/upgrade it on the plan's chosen node over SSH and prove `mlx-moe`
   resolves on PATH before `serve` tries to nohup it."
  [pl]
  (if-let [node (moe-node pl)]
    (let [host (:host node)]
      (print (format "[%s] " (:name node))) (flush)
      (if-not (ssh/reachable? host)
        (println "unreachable — skipped")
        (do (ssh/sh host "pip3 install -q -U mlx-moe")
            (let [check (ssh/sh host "mlx-moe --help >/dev/null 2>&1 && echo ok || echo FAILED")]
              (println (str "mlx-moe " (:out check)))))))
    (println "no candidate node in the plan — run `bb murakumo infer plan <moe-model>` first")))

(defn cmd-provision
  "Push rpc-server to each serving worker + raise the GPU wired limit (needs the
   fleet's passwordless sudo; best-effort — a refusal only costs capacity).
   For an mlx-moe plan (:engine :mlx-moe) this instead pip-installs mlx-moe on
   the plan's single chosen node — see cmd-provision-moe."
  [[sel]]
  (let [pl (load-plan)]
    (if (= :mlx-moe (:engine pl))
      (cmd-provision-moe pl)
      (let [cfg (load-config)
            want (when (and sel (not= sel "all")) (set (str/split sel #",")))
            wired (:infer/wired-limit-mb cfg)]
        (doseq [{:keys [node]} (serving-workers pl)
                :when (or (nil? want) (want (:name node)))]
          (print (format "[%s] " (:name node))) (flush)
          (let [host (:host node)]
            (if-not (ssh/reachable? host)
              (println "unreachable — skipped")
              (do (ssh/sh host (str "mkdir -p " remote-bin))
                  (let [{:keys [exit err]} (ssh/scp host "bin/rpc-server" ".murakumo/bin/rpc-server")]
                    (if-not (zero? exit)
                      (println (str "scp failed: " err))
                      (let [_ (doseq [lib (->> (file-seq (io/file "bin"))
                                               (map #(.getName %))
                                               (filter #(str/ends-with? % ".dylib")))]
                                (ssh/scp host (str "bin/" lib) (str ".murakumo/bin/" lib)))
                            _ (ssh/sh host "chmod +x .murakumo/bin/rpc-server")
                            w (ssh/sh host (format "sudo -n sysctl iogpu.wired_limit_mb=%d 2>&1 || echo no-sudo" wired))]
                        (println (str "rpc-server ✓  wired-limit: " (:out w))))))))))
        ;; a remote head serves llama-server from its own .murakumo/bin
        (let [{:keys [remote? host]} (:infer/head cfg)]
          (when remote?
            (print (format "[%s] " host)) (flush)
            (ssh/sh host (str "mkdir -p " remote-bin))
            (let [{:keys [exit err]} (ssh/scp host "bin/llama-server" ".murakumo/bin/llama-server")]
              (if (zero? exit)
                (do (ssh/sh host "chmod +x .murakumo/bin/llama-server")
                    (println "llama-server ✓ (head)"))
                (println (str "scp failed: " err))))))))))

(defn cmd-up [[sel]]
  (let [cfg (load-config)
        pl (load-plan)
        port (:infer/rpc-port cfg engine/default-rpc-port)
        want (when (and sel (not= sel "all")) (set (str/split sel #",")))]
    (doseq [{:keys [node]} (serving-workers pl)
            :when (or (nil? want) (want (:name node)))]
      (let [cache (if (:rpc-cache? node) " -c" "")
            dev (or (:rpc-device node) (:infer/rpc-device cfg "MTL0"))
            cmd (format "pkill -f '%s/rpc-server' 2>/dev/null; sleep 0.2; nohup env %s%s/rpc-server -H 0.0.0.0 -p %d -d %s%s >/tmp/murakumo-rpc.log 2>&1 & sleep 0.3; pgrep -f rpc-server >/dev/null && echo up || echo FAILED"
                        remote-bin remote-env remote-bin port dev cache)]
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

(defn- cmd-serve-moe
  "Start mlx-moe on the plan's chosen node over SSH (always remote — the node
   is whichever fleet/extra-node the planner picked, not the operator's own
   machine, so there is no local-foreground path the way llama-server has)."
  [cfg model pl]
  (cond
    (not= :mlx-moe (:engine pl))
    (println (str "stale/mismatched plan (last `plan` was not for an mlx-moe model) — "
                  "run `bb murakumo infer plan " (:model/id model) "` first"))

    (not (moe-node pl))
    (println "no candidate node in the plan — run `bb murakumo infer plan <moe-model>` first")

    :else
    (let [node (moe-node pl)
          moe-cfg (:infer/mlx-moe cfg)
          opts {:model-repo (or (:model/mlx-repo model) (:hf/repo model))
                :port (:infer/api-port cfg 8080)
                :capacity (or (:model/capacity model) (:capacity moe-cfg) (:capacity pl))
                :pin-top-k (moe-opt cfg model :pin-top-k)
                :kv-bits (or (moe-opt cfg model :kv-bits) (:infer/kv-bits cfg))
                :profile (or (:model/mlx-moe-profile model) (:profile moe-cfg))
                :warmup (moe-opt cfg model :warmup)
                :extra-args (or (:model/mlx-moe-extra-args model) (:extra-args moe-cfg))}
          cmd (engine/mlx-moe-cmd opts)
          {:keys [verdict why]} (:verdict pl)
          host (:host node)]
      (println cmd)
      (println (str "verdict: " (name verdict) " — " why))
      (let [{:keys [out]} (ssh/sh host
                                  (format "pkill -f 'mlx-moe serve' 2>/dev/null; sleep 0.3; nohup %s >/tmp/murakumo-moe.log 2>&1 & sleep 1; pgrep -f 'mlx-moe serve' >/dev/null && echo serving || echo FAILED" cmd))]
        (println (format "[%s] %s — http://%s:%s/v1 (first launch downloads the model; watch /tmp/murakumo-moe.log)"
                         host out (api-host node) (:infer/api-port cfg 8080)))))))

(defn- cmd-serve-ring
  "The original ring path: llama-server locally in the foreground, or — for a
   :remote? head — resident on the fleet node over SSH (the GGUF lives in the
   head's :model-dir there; `provision` pushes the binary)."
  [cfg model pl gguf-path]
  (let [head-cfg (:infer/head cfg)
        remote? (:remote? head-cfg)
        opts {:bin-dir (if remote? (:bin-dir head-cfg ".murakumo/bin") "bin")
              :model-path (or gguf-path
                              (if remote?
                                (str (:model-dir head-cfg "glm") "/" (:model/gguf model))
                                (:model/gguf model)))
              :rpc-port (:infer/rpc-port cfg engine/default-rpc-port)
              :port (:infer/api-port cfg 8080)
              :ctx (:infer/ctx cfg 4096)
              :extra-args (:model/llama-extra-args model)}
        cmd (engine/head-cmd pl opts)]
    (println cmd)
    (if remote?
      (let [{:keys [out]} (ssh/sh (:host head-cfg)
                                  (format "pgrep -x llama-server | xargs -r kill 2>/dev/null || true; sleep 0.3; nohup %s >/tmp/murakumo-head.log 2>&1 & sleep 1; pgrep -x llama-server >/dev/null && echo serving || echo FAILED" cmd))]
        (println (format "[%s] %s — http://%s:%s/v1 (load streams the shards; watch /tmp/murakumo-head.log)"
                         (:host head-cfg) out (api-host head-cfg) (:infer/api-port cfg 8080))))
      (p/shell cmd))))

(defn cmd-serve
  "Run the head: llama-server ring (default), or — for an mlx-moe model
   (:model/engine :mlx-moe) — mlx-moe on the plan's single chosen node."
  [[model-id gguf-path]]
  (let [cfg (load-config)
        model (model-or-die cfg (or model-id "glm-5.2-reap50-q2k"))
        pl (load-plan)]
    (if (moe? model)
      (cmd-serve-moe cfg model pl)
      (cmd-serve-ring cfg model pl gguf-path))))

(defn cmd-generate
  "One completion via the head's /v1 API. Targets whichever host actually
   served the last `plan` — the mlx-moe node when the saved plan is
   :engine :mlx-moe, else the configured llama-server head."
  [[prompt]]
  (let [cfg (load-config)
        last-plan (try (edn/read-string (slurp plan-file)) (catch Exception _ nil))
        moe-node* (when (= :mlx-moe (:engine last-plan)) (moe-node last-plan))
        head-host (if moe-node*
                    (api-host moe-node*)
                    (let [h (:infer/head cfg)] (if (:remote? h) (api-host h) "localhost")))
        body (json/generate-string
              {:messages [{:role "user" :content (or prompt "Name three Japanese cities.")}]
               :max_tokens 256})
        {:keys [out]} (p/sh "curl" "-s" "-m" "600"
                            (str "http://" head-host ":" (:infer/api-port cfg 8080) "/v1/chat/completions")
                            "-H" "Content-Type: application/json" "-d" body)]
    (let [r (json/parse-string (str out) true)]
      (println (or (get-in r [:choices 0 :message :content]) out))
      (when-let [t (:timings r)]
        (println (format "-- %.2f tok/s prefill, %.2f tok/s gen"
                         (double (:prompt_per_second t 0.0))
                         (double (:predicted_per_second t 0.0))))))))

(defn -main [& [cmd & args]]
  (case cmd
    "media" (do (require 'murakumo.infer.media)
                (apply (resolve 'murakumo.infer.media/-main) args))
    "gc" (do (require 'murakumo.infer.gc-op)
             (apply (resolve 'murakumo.infer.gc-op/-main) args))
    "bench" (do (require 'murakumo.infer.bench)
                (apply (resolve 'murakumo.infer.bench/-main) args))
    "relay" (do (require 'murakumo.infer.relay-server)
                (apply (resolve 'murakumo.infer.relay-server/-main) args))
    "gateway" (do (require 'murakumo.infer.gateway)
                  (apply (resolve 'murakumo.infer.gateway/-main) args))
    "probe" (cmd-probe args)
    "plan" (cmd-plan args)
    "provision" (cmd-provision args)
    "up" (cmd-up args)
    "down" (cmd-down args)
    "ps" (cmd-ps args)
    "serve" (cmd-serve args)
    "generate" (cmd-generate args)
    (println "usage: bb murakumo infer probe|plan <model>|provision [sel]|up|down|ps|serve <model> [gguf]|generate \"<prompt>\"|media …|gc [--apply]|relay [port]|gateway [port]")))
