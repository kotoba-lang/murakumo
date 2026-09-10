#!/usr/bin/env nbb
;; model-kv-geometry — read the KV-cache geometry out of a GGUF and say what
;; context each memory budget can actually hold.
;;
;; ⚠ WHY THIS IS A TOOL AND NOT A TABLE OF NUMBERS.
;;
;; A model's context is usually copied from its card, and the card states what
;; the model was TRAINED for -- a property of the weights, not of the machine.
;; What a node can hold is a different quantity entirely, and it is decided by
;; the KV cache, which grows linearly in context and is invisible in the
;; download size. Measured on Qwen3.8-27B-GSQ-RCO-IQ2_XS: 8.42 GB of weights,
;; and at its declared 262,144 context a KV cache of 36.5 GB. The number on
;; the card does not fit the hardware at any quantization.
;;
;; So the DATA to keep per model is the geometry -- layers, KV heads, key and
;; value length -- because that is a fixed property of the model and yields
;; bytes-per-token exactly. The fitting context is then computed against a
;; specific budget rather than stored, since it changes with the node, the
;; cache type, and what else that node is holding.
;;
;;   nbb scripts/model-kv-geometry.cljs <node> <path-to.gguf> [--edn]
;;   nbb scripts/model-kv-geometry.cljs --local <path-to.gguf> [--edn]

(ns model-kv-geometry
  (:require ["child_process" :as cp] ["fs" :as fs] [clojure.string :as str]))

(def ^:private header-bytes
  "How much of the file to pull for metadata. The tokenizer's token list and
  merges live in the header and dominate it -- a 150k-vocab model runs to
  several MB -- so this is generous. The tensor table follows the metadata,
  and we stop as soon as we have what we need."
  (* 48 1024 1024))

(defn- fetch [node path]
  (if (= node :local)
    (fs/readFileSync path)
    ;; ⚠ NOT through murakumo.tunnel's wrapped ssh. That wrapper appends an
    ;; in-band `__murakumo_rc=` sentinel to stdout, which is harmless for text
    ;; and corrupts binary -- measured elsewhere in this repo as a png
    ;; arriving 16 bytes long. Raw ssh, bytes straight back.
    (cp/execSync (str "ssh -o BatchMode=yes -o ConnectTimeout=10 " node
                      " 'head -c " header-bytes " " path "'")
                 #js {:encoding "buffer" :maxBuffer (* 64 1024 1024)})))

;; GGUF metadata value types, by their on-disk tag.
(def ^:private t-u8 0) (def ^:private t-i8 1) (def ^:private t-u16 2)
(def ^:private t-i16 3) (def ^:private t-u32 4) (def ^:private t-i32 5)
(def ^:private t-f32 6) (def ^:private t-bool 7) (def ^:private t-string 8)
(def ^:private t-array 9) (def ^:private t-u64 10) (def ^:private t-i64 11)
(def ^:private t-f64 12)

(defn- rd
  "Read one metadata value of type `t` at `pos`; returns [value next-pos].
  Arrays of scalars are SKIPPED rather than materialised -- the token list is
  hundreds of thousands of entries and nothing here needs it, and building it
  is the difference between this finishing and this not."
  [^js buf pos t]
  (cond
    (= t t-u8)  [(.readUInt8 buf pos) (+ pos 1)]
    (= t t-i8)  [(.readInt8 buf pos) (+ pos 1)]
    (= t t-u16) [(.readUInt16LE buf pos) (+ pos 2)]
    (= t t-i16) [(.readInt16LE buf pos) (+ pos 2)]
    (= t t-u32) [(.readUInt32LE buf pos) (+ pos 4)]
    (= t t-i32) [(.readInt32LE buf pos) (+ pos 4)]
    (= t t-f32) [(.readFloatLE buf pos) (+ pos 4)]
    (= t t-bool) [(not= 0 (.readUInt8 buf pos)) (+ pos 1)]
    (= t t-u64) [(js/Number (.readBigUInt64LE buf pos)) (+ pos 8)]
    (= t t-i64) [(js/Number (.readBigInt64LE buf pos)) (+ pos 8)]
    (= t t-f64) [(.readDoubleLE buf pos) (+ pos 8)]
    (= t t-string)
    (let [n (js/Number (.readBigUInt64LE buf pos))]
      [(.toString buf "utf8" (+ pos 8) (+ pos 8 n)) (+ pos 8 n)])
    (= t t-array)
    ;; ⚠ SOME OF THESE ARRAYS ARE THE ANSWER, not noise to step over.
    ;; Gemma 4 publishes `attention.head_count_kv` as ONE VALUE PER LAYER --
    ;; 48 of them -- because it alternates local and global attention, so a
    ;; model's KV geometry is not always a single number. The first version of
    ;; this tool skipped every array and reported NaN for exactly the models
    ;; whose geometry is most worth having.
    ;; Small numeric arrays are kept; the tokenizer's hundreds of thousands of
    ;; strings are still skipped, because materialising those is the
    ;; difference between this finishing and this not.
    (let [et (.readUInt32LE buf pos)
          n  (js/Number (.readBigUInt64LE buf (+ pos 4)))
          keep? (and (not= et t-string) (not= et t-array) (<= n 8192))
          p  (+ pos 12)]
      (loop [i 0 p p acc (when keep? (transient []))]
        (if (>= i n)
          [(if keep? (persistent! acc) (str "<skipped array of " n ">")) p]
          (let [[v p'] (rd buf p et)]
            (recur (inc i) p' (when keep? (conj! acc v)))))))
    :else (throw (ex-info "unknown GGUF metadata type" {:type t :pos pos}))))

(defn- metadata [^js buf]
  (when-not (= "GGUF" (.toString buf "utf8" 0 4))
    (throw (ex-info "not a GGUF file" {})))
  (let [n-kv (js/Number (.readBigUInt64LE buf 16))]
    (loop [i 0 pos 24 acc {}]
      (if (>= i n-kv)
        acc
        (let [kn (js/Number (.readBigUInt64LE buf pos))
              k  (.toString buf "utf8" (+ pos 8) (+ pos 8 kn))
              t  (.readUInt32LE buf (+ pos 8 kn))
              [v p'] (rd buf (+ pos 12 kn) t)]
          (recur (inc i) p' (assoc acc k v)))))))

(defn- arch-get [meta arch & suffixes]
  (some (fn [s] (get meta (str arch "." s))) suffixes))

(defn geometry
  "KV geometry as data. Every field is read from the file; nothing is inferred
  from the model's name."
  [meta]
  (let [arch   (get meta "general.architecture")
        layers (arch-get meta arch "block_count")
        heads  (arch-get meta arch "attention.head_count")
        kv-h-raw (or (arch-get meta arch "attention.head_count_kv") heads)
        ;; A vector here means per-layer KV heads. Its length IS the layer
        ;; count, so the cache is the SUM over layers rather than
        ;; layers x heads -- collapsing it to an average would be wrong for
        ;; any model whose layers genuinely differ.
        per-layer? (vector? kv-h-raw)
        kv-h   (if per-layer? (apply + kv-h-raw) kv-h-raw)
        embed  (arch-get meta arch "embedding_length")
        ;; key_length/value_length are optional; when absent the head
        ;; dimension is embedding/heads, which is the GGUF default and the
        ;; only safe fallback. Saying which branch was taken matters, because
        ;; a wrong head_dim scales the whole answer.
        derived? (nil? (arch-get meta arch "attention.key_length"))
        kl (or (arch-get meta arch "attention.key_length")
               (when (and embed heads) (/ embed heads)))
        vl (or (arch-get meta arch "attention.value_length") kl)]
    {:arch arch
     :name (get meta "general.name")
     :layers layers
     :heads heads
     :kv-heads (if per-layer? kv-h-raw kv-h)
     :kv-heads-per-layer? per-layer?
     ;; Layers that use a sliding window hold at most `window` tokens of
     ;; cache no matter how large the context is, so a model with these is
     ;; cheaper at long context than the flat arithmetic below suggests. This
     ;; is reported, never silently applied -- which layers are windowed is
     ;; not in the header, so the flat number stays the honest upper bound.
     :sliding-window (arch-get meta arch "attention.sliding_window")
     :key-length kl
     :value-length vl
     :head-dim-derived? derived?
     :context-train (arch-get meta arch "context_length")
     ;; Elements of cache per token = layers * kv_heads * (key_len + value_len),
     ;; counting K and V. Multiply by the cache type's bytes-per-element.
     :kv-elements-per-token
     (when (and kv-h kl vl)
       ;; Per-layer: sum(kv_heads) * (kl + vl). Uniform: layers * kv * (kl+vl).
       (if per-layer?
         (* kv-h (+ kl vl))
         (when layers (* layers kv-h (+ kl vl)))))}))

;; q8_0 packs 32 values into 34 bytes (32 quants + an f16 scale), so 1.0625
;; bytes per element -- not 1.
(def cache-bytes-per-element {:f32 4.0 :f16 2.0 :q8_0 1.0625 :q4_0 0.5625})

(defn fits
  "The largest context this geometry fits into `budget` bytes, after the
  weights. Returns nil when the budget cannot hold the weights at all."
  [{:keys [kv-elements-per-token]} weight-bytes budget cache-type]
  (when (and kv-elements-per-token budget)
    (let [per-tok (* kv-elements-per-token (get cache-bytes-per-element cache-type 2.0))
          ;; Compute buffers and the runtime itself. Measured on this fleet at
          ;; roughly half a gigabyte for a 27B; kept explicit so it can be
          ;; argued with rather than hidden inside the arithmetic.
          overhead 5.0E8
          left (- budget (or weight-bytes 0) overhead)]
      (when (pos? left)
        ;; Contexts are powers of two in practice; report the largest that fits.
        (last (filter #(<= (* per-tok %) left)
                      [1024 2048 4096 8192 16384 32768 65536 131072 262144 524288]))))))

(defn -main [& args]
  (let [edn? (some #{"--edn"} args)
        args (remove #{"--edn"} args)
        [a b] args
        [node path] (if (= a "--local") [:local b] [a b])
        g (geometry (metadata (fetch node path)))]
    (if edn?
      (println (pr-str g))
      (do
        (println (str (:name g) "  [" (:arch g) "]"))
        (println (str "  layers " (:layers g) "  heads " (:heads g)
                      "  kv-heads " (if (:kv-heads-per-layer? g)
                                      (str "per-layer, sum="
                                           (apply + (:kv-heads g))
                                           " " (pr-str (vec (take 8 (:kv-heads g)))) "…")
                                      (:kv-heads g))
                      "  key/value " (:key-length g) "/" (:value-length g)
                      (when (:head-dim-derived? g) "  (head-dim DERIVED from embedding/heads)")))
        (println (str "  context the model was trained for: " (:context-train g)))
        (println (str "  KV elements per token: " (:kv-elements-per-token g)))
        (when (:sliding-window g)
          (println (str "  ⚠ sliding window " (:sliding-window g)
                        " — windowed layers cap their cache at that many tokens,"
                        " so the figures below are an UPPER BOUND")))
        (doseq [ct [:f16 :q8_0]]
          (let [per-tok (* (:kv-elements-per-token g) (get cache-bytes-per-element ct))]
            (println (str "  " (name ct) ": " (.toFixed (/ per-tok 1024) 1) " KiB/token"
                          "   8192 -> " (.toFixed (/ (* per-tok 8192) 1e9) 2) " GB"
                          "   32768 -> " (.toFixed (/ (* per-tok 32768) 1e9) 2) " GB"
                          "   " (:context-train g) " -> "
                          (.toFixed (/ (* per-tok (:context-train g)) 1e9) 1) " GB"))))))))

(apply -main *command-line-args*)
