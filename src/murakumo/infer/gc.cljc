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
;; W6 product-shell authority (ADR-260728-w6-join-gc-oracle-authority):
;; On the JVM, policy constants + need/free-after/target-met/rank/comfy gates
;; DELEGATE to precompiled infer_gc_core.kir.edn. Host remains: entry filters,
;; vector reduce, cljs mirrors.

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
  #?(:clj {:target-free-bytes (long (o 'default-target-free []))
           :comfy-keep-days (long (o 'default-comfy-keep-days []))
           :hf-keep (long (o 'default-hf-keep []))
           :evict-order [:rpc-cache :comfy-temp :hf-stale]}
     :cljs {:target-free-bytes (* 20 GiB)
            :comfy-keep-days 7
            :hf-keep 2
            :evict-order [:rpc-cache :comfy-temp :hf-stale]}))

(def reclaimable #{:rpc-cache :comfy-temp :hf-stale})

(defn- rank
  "Eviction rank within a class: oldest first, then biggest. Lower = evict sooner."
  [{:keys [atime-days bytes]}]
  [(- (or atime-days 0)) (- (or bytes 0))])

(defn- rank-compare
  "Comparator using kotoba rank-better? on JVM (1 ⇒ a evicts before b)."
  [a b]
  #?(:clj
     (let [a1 (long (or (:atime-days a) 0))
           b1 (long (or (:bytes a) 0))
           a2 (long (or (:atime-days b) 0))
           b2 (long (or (:bytes b) 0))]
       (cond
         (= 1 (o 'rank-better? [a1 b1 a2 b2])) -1
         (= 1 (o 'rank-better? [a2 b2 a1 b1])) 1
         :else 0))
     :cljs (compare (rank a) (rank b))))

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
        need #?(:clj (long (o 'need-bytes
                              [(long target-free-bytes)
                               (long (or free-bytes 0))]))
                :cljs (max 0 (- target-free-bytes (or free-bytes 0))))
        candidates (reduce
                    (fn [acc cls]
                      (concat acc
                              (case cls
                                :rpc-cache
                                (sort rank-compare
                                      (filter #(= :rpc-cache (:class %)) entries))
                                :comfy-temp
                                (sort rank-compare
                                      (filter #(and (= :comfy-temp (:class %))
                                                    #?(:clj (= 1 (o 'comfy-evictable?
                                                                    [(long (or (:atime-days %) 0))
                                                                     (long comfy-keep-days)]))
                                                       :cljs (> (or (:atime-days %) 0)
                                                                comfy-keep-days)))
                                              entries))
                                :hf-stale
                                (sort rank-compare
                                      (hf-lru-evictable
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
        free0 (or free-bytes 0)]
    {:evict (vec evict)
     :reclaim-bytes reclaimed
     :free-after #?(:clj (long (o 'free-after [(long free0) (long reclaimed)]))
                    :cljs (+ free0 reclaimed))
     :target-met? #?(:clj (= 1 (o 'target-met?
                                  [(long free0) (long reclaimed)
                                   (long target-free-bytes)]))
                     :cljs (>= (+ free0 reclaimed) target-free-bytes))
     :kept-protected (count (filter #(= :protected (:class %)) entries))}))
