(ns murakumo.infer.evolution
  "Cloud-Murakumo dispatch planning for bounded self-evolution experiments.

  The output uses the `murakumo.infer.relay` job shape.  It is intentionally
  transport-free: a relay, Cloudflare Worker, JVM service, or WASM host can
  execute the jobs without changing the safety and scheduling semantics."
  (:require [murakumo.infer.plan :as infer-plan]))

(defn healthy-nodes
  "Keep explicitly healthy, inference-capable nodes with usable memory.
  Missing health is treated as unhealthy; this prevents a stale dashboard from
  dispatching an experiment into an unknown fleet."
  [nodes]
  (->> nodes
       (filter #(and (= :healthy (:health %))
                     (contains? (set (:caps %)) :inference)
                     (pos? (infer-plan/usable-bytes %))))
       vec))

(defn dispatch-plan
  "Create cloud-murakumo relay jobs for a reproducible candidate evaluation.

  Returns `:blocked` until the live fleet has enough healthy capacity.  A ready
  plan has one bounded inference job per prompt and carries the candidate and
  benchmark IDs needed to persist a later evidence receipt."
  [{:keys [candidate-id benchmark-id prompts model nodes max-tokens]
    :or {max-tokens 512}}]
  (let [nodes (healthy-nodes nodes)
        shard-plan (when (and model (seq nodes)) (infer-plan/plan model nodes))]
    (cond
      (empty? nodes)
      {:status :blocked :reason :no-healthy-inference-nodes :jobs []}

      (not (:fits? shard-plan))
      {:status :blocked :reason :insufficient-fleet-memory
       :shard-plan shard-plan :jobs []}

      :else
      (let [links (seq (keep :link-gbps nodes))]
        {:status :ready
         :strategy (infer-plan/choose-strategy
                    {:link-gbps (when links (apply min links))
                     :ranks (count nodes) :model model})
         :shard-plan shard-plan
         :jobs (mapv (fn [prompt index]
                       {:kind :inference
                        :price 0
                        :idempotency-key (str "shinka/" candidate-id "/" benchmark-id "/" index)
                        :input {:candidate-id candidate-id
                                :benchmark-id benchmark-id
                                :prompt-id (str benchmark-id "-" index)
                                :prompt prompt
                                :max-tokens max-tokens
                                :reproducible true}})
                     prompts (range))}))))
