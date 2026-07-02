;; murakumo.infer.gc — disk garbage collection policy (pure cljc).
;;
;; The fleet's minis fill up: dead RPC tensor caches from finished sharded runs
;; (15–22 GB/node after GLM-5.2), stale HuggingFace downloads, ComfyUI temp
;; frames. A node at <1 GB free can neither generate nor stay healthy (the
;; asher incident). This namespace decides — as pure data → data, so the same
;; policy runs in bb, tests, and the CF Worker — WHAT is safe to reclaim.
;;
;; Safety is the whole point. The classifier NEVER evicts:
;;   :protected — ollama models (owner's), active ComfyUI checkpoints, the run
;;                ledger, murakumo's own binaries/identity.
;; It reclaims, in priority order until the target free space is met:
;;   1. :rpc-cache  — llama.cpp RPC tensor cache. Dead the moment a sharded run
;;                    ends; fully re-derivable from the GGUF (prewarm rebuilds it).
;;   2. :comfy-temp — ComfyUI temp/output older than keep-days.
;;   3. :hf-stale   — HuggingFace cache, LRU: keep the `hf-keep` most-recently
;;                    used, evict the rest (re-fetchable from HF).
;; Within a class, oldest (largest atime-days) goes first; ties by largest bytes
;; (reclaim the most per deletion).

(ns murakumo.infer.gc)

(def GiB (* 1024 1024 1024))

(def default-policy
  {:target-free-bytes (* 20 GiB)      ; reclaim until at least this much is free
   :comfy-keep-days 7                  ; keep ComfyUI temp/output newer than this
   :hf-keep 2                          ; keep the N most-recently-used HF models
   :evict-order [:rpc-cache :comfy-temp :hf-stale]})

(def reclaimable #{:rpc-cache :comfy-temp :hf-stale})

(defn- rank
  "Eviction rank within a class: oldest first, then biggest. Lower = evict sooner."
  [{:keys [atime-days bytes]}]
  [(- (or atime-days 0)) (- (or bytes 0))])

(defn- hf-lru-evictable
  "Of the :hf-stale entries, mark the (count - keep) least-recently-used as
   evictable; keep the `keep` most-recent (smallest atime-days)."
  [entries keep]
  (let [by-recency (sort-by (comp #(or % 0) :atime-days) entries)]
    (drop keep by-recency)))

(defn plan
  "entries: [{:path :class :bytes :atime-days} …] (class ∈ reclaimable ∪
   #{:protected}). free-bytes: current free on the node. policy: see
   default-policy. → {:evict [entry…] :reclaim-bytes n :free-after n
   :target-met? bool :kept-protected n}. Deterministic; deletes nothing."
  [entries free-bytes policy]
  (let [{:keys [target-free-bytes comfy-keep-days hf-keep evict-order]}
        (merge default-policy policy)
        need (max 0 (- target-free-bytes (or free-bytes 0)))
        candidates (reduce
                    (fn [acc cls]
                      (concat acc
                              (case cls
                                :rpc-cache
                                (sort-by rank (filter #(= :rpc-cache (:class %)) entries))
                                :comfy-temp
                                (sort-by rank (filter #(and (= :comfy-temp (:class %))
                                                            (> (or (:atime-days %) 0) comfy-keep-days))
                                                      entries))
                                :hf-stale
                                (sort-by rank (hf-lru-evictable
                                               (filter #(= :hf-stale (:class %)) entries)
                                               hf-keep))
                                nil)))
                    [] evict-order)
        [evict reclaimed]
        (reduce (fn [[ev got] e]
                  (if (>= got need)
                    (reduced [ev got])
                    [(conj ev e) (+ got (or (:bytes e) 0))]))
                [[] 0] candidates)]
    {:evict (vec evict)
     :reclaim-bytes reclaimed
     :free-after (+ (or free-bytes 0) reclaimed)
     :target-met? (>= (+ (or free-bytes 0) reclaimed) target-free-bytes)
     :kept-protected (count (filter #(= :protected (:class %)) entries))}))
