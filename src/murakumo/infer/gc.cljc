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
;;
;; W6 product-shell authority: GiB/defaults + need/free/target/rank/comfy
;; DELEGATE to precompiled kotoba/infer_gc_core.kotoba on JVM. Plan fold stays
;; host.

(ns murakumo.infer.gc
  (:require #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :infer-gc)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(def GiB
  #?(:clj (long (o 'gib []))
     :cljs (* 1024 1024 1024)))

(def default-policy
  {:target-free-bytes #?(:clj (long (o 'default-target-free []))
                         :cljs (* 20 GiB))
   :comfy-keep-days #?(:clj (long (o 'default-comfy-keep-days []))
                       :cljs 7)
   :hf-keep #?(:clj (long (o 'default-hf-keep []))
               :cljs 2)
   :evict-order [:rpc-cache :comfy-temp :hf-stale]})

(def reclaimable #{:rpc-cache :comfy-temp :hf-stale})

(defn- rank
  "Eviction rank within a class: oldest first, then biggest. Lower = evict sooner.
   JVM: comparison via kotoba `rank-better?` for sort-by (stable host sort keys
   still use the same tuple semantics as before)."
  [{:keys [atime-days bytes]}]
  [(- (or atime-days 0)) (- (or bytes 0))])

(defn- rank-better?
  "True when entry1 should evict before entry2. JVM: kotoba `rank-better?`."
  [e1 e2]
  #?(:clj
     (= 1 (o 'rank-better?
             [(long (or (:atime-days e1) 0)) (long (or (:bytes e1) 0))
              (long (or (:atime-days e2) 0)) (long (or (:bytes e2) 0))]))
     :cljs
     (neg? (compare (rank e1) (rank e2)))))

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
   :target-met? bool :kept-protected n}. Deterministic; deletes nothing.
   JVM: need-bytes / free-after / target-met? / comfy-evictable? via oracle."
  [entries free-bytes policy]
  (let [{:keys [target-free-bytes comfy-keep-days hf-keep evict-order]}
        (merge default-policy policy)
        free (or free-bytes 0)
        need #?(:clj (long (o 'need-bytes
                              [(long target-free-bytes) (long free)]))
                :cljs (max 0 (- target-free-bytes free)))
        candidates (reduce
                    (fn [acc cls]
                      (concat acc
                              (case cls
                                :rpc-cache
                                (sort-by rank (filter #(= :rpc-cache (:class %)) entries))
                                :comfy-temp
                                (sort-by rank
                                         (filter
                                          (fn [e]
                                            (and (= :comfy-temp (:class e))
                                                 #?(:clj (= 1 (o 'comfy-evictable?
                                                                 [(long (or (:atime-days e) 0))
                                                                  (long comfy-keep-days)]))
                                                    :cljs (> (or (:atime-days e) 0)
                                                             comfy-keep-days))))
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
                [[] 0] candidates)
        free-after #?(:clj (long (o 'free-after [(long free) (long reclaimed)]))
                      :cljs (+ free reclaimed))
        target-met? #?(:clj (= 1 (o 'target-met?
                                    [(long free) (long reclaimed)
                                     (long target-free-bytes)]))
                       :cljs (>= free-after target-free-bytes))]
    {:evict (vec evict)
     :reclaim-bytes reclaimed
     :free-after free-after
     :target-met? target-met?
     :kept-protected (count (filter #(= :protected (:class %)) entries))}))
