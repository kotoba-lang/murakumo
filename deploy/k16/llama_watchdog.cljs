(ns llama-watchdog
  "Restart a llama-server that still answers /health \"ok\" but can no longer
  complete anything.

  Why this exists (measured on the aiueos K16, 2026-09-15, root ADR
  adr-2609151900): after `vk::Queue::submit: ErrorDeviceLost` the Vulkan
  llama-server keeps its process, keeps answering GET /health {status ok},
  and answers every completion with HTTP 500. systemd's Restart=always never
  fires because nothing exited. So the probe here is a completion, not a
  health check: max_tokens 1 on an IDLE slot.

  Decisions, in order, per target:
    health not ok / unreachable  -> SKIP  (loading or dead; systemd owns that)
    a slot is processing          -> SKIP  (busy is alive; a probe would queue)
    completion 200                -> OK
    completion 5xx / timeout      -> RESTART <unit>  (dry-run: print only)

  Exit codes are three answers: 0 every target OK or skipped for a stated
  reason, 1 at least one restart issued, 2 could not measure (no targets /
  systemctl missing).

  usage: nbb llama_watchdog.cljs [--dry-run] [--timeout-ms 90000]
         targets are the TARGETS vector below (unit port model)."
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(def targets
  [{:unit "murakumo-k16-qwen38u-llama" :port 8097 :model "qwen3.8-27b-uncensored"}
   {:unit "murakumo-edge-usagi-llama"  :port 8096 :model "ling-3.0-tiny"}])

(defn- fetch-json [url opts timeout-ms]
  (-> (js/fetch url (clj->js (assoc opts :signal (js/AbortSignal.timeout timeout-ms))))
      (.then (fn [r] (-> (.text r) (.then (fn [t] {:status (.-status r) :text t})))))
      (.catch (fn [e] {:status 0 :text (str e)}))))

(defn- slots-busy? [text]
  (try (some #(true? (.-is_processing %)) (js/JSON.parse text))
       (catch :default _ nil)))

(defn- probe-completion [base model timeout-ms]
  (-> (fetch-json (str base "/v1/chat/completions")
                  {:method "POST"
                   :headers {"content-type" "application/json"}
                   :body (js/JSON.stringify
                          (clj->js {:model model :max_tokens 1 :temperature 0
                                    :messages [{:role "user" :content "ok"}]}))}
                  timeout-ms)
      (.then (fn [{:keys [status text]}]
               (if (= 200 status)
                 {:decision :ok :reason "completion 200"}
                 {:decision :restart
                  :reason (str "completion " status " " (subs text 0 (min 160 (count text))))})))))

(defn- decide [{:keys [port model]} timeout-ms]
  (let [base (str "http://127.0.0.1:" port)]
    (-> (fetch-json (str base "/health") {} 10000)
        (.then (fn [{:keys [status]}]
                 (if (not= 200 status)
                   {:decision :skip :reason (str "health " status)}
                   (-> (fetch-json (str base "/slots") {} 10000)
                       (.then (fn [{:keys [status text]}]
                                (if (and (= 200 status) (slots-busy? text))
                                  {:decision :skip :reason "slot processing"}
                                  (probe-completion base model timeout-ms)))))))))))

(defn- restart! [unit dry-run?]
  (if dry-run?
    (println "DRY-RUN would restart" unit)
    (do (cp/execFileSync "systemctl" #js ["restart" unit] #js {:stdio "inherit"})
        (println "RESTARTED" unit))))

(defn -main [& args]
  (let [dry-run? (some #{"--dry-run"} args)
        timeout-ms (let [i (.indexOf (to-array args) "--timeout-ms")]
                     (if (neg? i) 90000 (js/parseInt (nth args (inc i)))))]
    (when (empty? targets) (println "REFUSE no targets") (js/process.exit 2))
    (-> (js/Promise.all (clj->js (map (fn [t] (-> (decide t timeout-ms) (.then #(assoc % :target t)))) targets)))
        (.then (fn [results]
                 (let [results (map #(js->clj % :keywordize-keys true) results)
                       results (map (fn [r] (update r :target #(js->clj % :keywordize-keys true))) results)
                       restarted (atom 0)]
                   (doseq [{:keys [decision reason target]} results]
                     (println (name decision) (:unit target) (str ":" (:port target)) "--" reason)
                     (when (= :restart (keyword decision))
                       (swap! restarted inc)
                       (restart! (:unit target) dry-run?)))
                   (println (str "SCANNED\t" (count results)))
                   (js/process.exit (if (pos? @restarted) 1 0)))))
        (.catch (fn [e] (println "REFUSE" (str e)) (js/process.exit 2))))))

(apply -main *command-line-args*)
