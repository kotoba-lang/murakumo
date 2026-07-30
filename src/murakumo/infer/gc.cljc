;; murakumo.infer.gc — disk garbage collection policy (pure cljc).
;;
;; W6 product-shell + T6.4: GiB/defaults + need/free/target/rank/comfy require
;; the shipped `:infer-gc` KIR on **every** platform. Host pure mirrors are
;; gone — cljs/nbb must preload shipped KIR before requiring this ns
;; (ADR-260731-w6-t64-infer-small-mirror-delete).
;; Plan fold stays host.

(ns murakumo.infer.gc
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-gc)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(def GiB
  (oracle/i64->host (o 'gib [])))

(def default-policy
  {:target-free-bytes (oracle/i64->host (o 'default-target-free []))
   :comfy-keep-days (oracle/i64->host (o 'default-comfy-keep-days []))
   :hf-keep (oracle/i64->host (o 'default-hf-keep []))
   :evict-order [:rpc-cache :comfy-temp :hf-stale]})

(def reclaimable #{:rpc-cache :comfy-temp :hf-stale})

(defn- rank
  "Eviction rank within a class: oldest first, then biggest. Lower = evict sooner."
  [{:keys [atime-days bytes]}]
  [(- (or atime-days 0)) (- (or bytes 0))])

(defn- rank-better?
  "True when entry1 should evict before entry2."
  [e1 e2]
  (oracle/bool->host
   (o 'rank-better?
      [(oracle/as-i64 (or (:atime-days e1) 0))
       (oracle/as-i64 (or (:bytes e1) 0))
       (oracle/as-i64 (or (:atime-days e2) 0))
       (oracle/as-i64 (or (:bytes e2) 0))])))

(defn- hf-lru-evictable
  "Of the :hf-stale entries, mark the (count - keep) least-recently-used as
   evictable; keep the `keep` most-recent (smallest atime-days)."
  [entries keep]
  (let [by-recency (sort-by (comp #(or % 0) :atime-days) entries)]
    (drop keep by-recency)))

(defn plan
  "entries: [{:path :class :bytes :atime-days} …]. free-bytes: current free.
   policy: see default-policy. Pure; deletes nothing."
  [entries free-bytes policy]
  (let [{:keys [target-free-bytes comfy-keep-days hf-keep evict-order]}
        (merge default-policy policy)
        free (or free-bytes 0)
        need (oracle/i64->host
              (o 'need-bytes
                 [(oracle/as-i64 target-free-bytes) (oracle/as-i64 free)]))
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
                                                 (oracle/bool->host
                                                  (o 'comfy-evictable?
                                                     [(oracle/as-i64 (or (:atime-days e) 0))
                                                      (oracle/as-i64 comfy-keep-days)]))))
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
        free-after (oracle/i64->host
                    (o 'free-after [(oracle/as-i64 free) (oracle/as-i64 reclaimed)]))
        target-met? (oracle/bool->host
                     (o 'target-met?
                        [(oracle/as-i64 free)
                         (oracle/as-i64 reclaimed)
                         (oracle/as-i64 target-free-bytes)]))]
    {:evict (vec evict)
     :reclaim-bytes reclaimed
     :free-after free-after
     :target-met? target-met?
     :kept-protected (count (filter #(= :protected (:class %)) entries))}))
