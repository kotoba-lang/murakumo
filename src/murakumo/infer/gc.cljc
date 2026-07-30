;; murakumo.infer.gc — disk garbage collection policy (pure cljc).
;;
;; W6 product-shell authority: GiB/defaults + need/free/target/rank/comfy
;; DELEGATE to precompiled kotoba/infer_gc_core.kotoba when oracle loadable
;; (JVM or cljs/nbb). Plan fold stays host.

(ns murakumo.infer.gc
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-gc)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(def GiB
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid 'gib []))
      (* 1024 1024 1024))
    (catch #?(:clj Exception :cljs :default) _
      (* 1024 1024 1024))))

(def default-policy
  {:target-free-bytes
   (try
     (if (oracle/ready? oid)
       (oracle/i64->host (oracle/call oid 'default-target-free []))
       (* 20 GiB))
     (catch #?(:clj Exception :cljs :default) _
       (* 20 GiB)))
   :comfy-keep-days
   (try
     (if (oracle/ready? oid)
       (oracle/i64->host (oracle/call oid 'default-comfy-keep-days []))
       7)
     (catch #?(:clj Exception :cljs :default) _
       7))
   :hf-keep
   (try
     (if (oracle/ready? oid)
       (oracle/i64->host (oracle/call oid 'default-hf-keep []))
       2)
     (catch #?(:clj Exception :cljs :default) _
       2))
   :evict-order [:rpc-cache :comfy-temp :hf-stale]})

(def reclaimable #{:rpc-cache :comfy-temp :hf-stale})

(defn- rank
  "Eviction rank within a class: oldest first, then biggest. Lower = evict sooner."
  [{:keys [atime-days bytes]}]
  [(- (or atime-days 0)) (- (or bytes 0))])

(defn- rank-better?
  "True when entry1 should evict before entry2."
  [e1 e2]
  (try-oracle
   #(oracle/bool->host
     (o 'rank-better?
        [(oracle/as-i64 (or (:atime-days e1) 0))
         (oracle/as-i64 (or (:bytes e1) 0))
         (oracle/as-i64 (or (:atime-days e2) 0))
         (oracle/as-i64 (or (:bytes e2) 0))]))
   #(neg? (compare (rank e1) (rank e2)))))

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
        need (try-oracle
              #(oracle/i64->host
                (o 'need-bytes
                   [(oracle/as-i64 target-free-bytes) (oracle/as-i64 free)]))
              #(max 0 (- target-free-bytes free)))
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
                                                 (try-oracle
                                                  #(oracle/bool->host
                                                    (o 'comfy-evictable?
                                                       [(oracle/as-i64 (or (:atime-days e) 0))
                                                        (oracle/as-i64 comfy-keep-days)]))
                                                  #(> (or (:atime-days e) 0)
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
        free-after (try-oracle
                    #(oracle/i64->host
                      (o 'free-after [(oracle/as-i64 free) (oracle/as-i64 reclaimed)]))
                    #(+ free reclaimed))
        target-met? (try-oracle
                     #(oracle/bool->host
                       (o 'target-met?
                          [(oracle/as-i64 free)
                           (oracle/as-i64 reclaimed)
                           (oracle/as-i64 target-free-bytes)]))
                     #(>= free-after target-free-bytes))]
    {:evict (vec evict)
     :reclaim-bytes reclaimed
     :free-after free-after
     :target-met? target-met?
     :kept-protected (count (filter #(= :protected (:class %)) entries))}))
