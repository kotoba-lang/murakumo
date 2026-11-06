"""Random-init, text-only CSA2-style CED/MoE research model.

Implements compressed causal main KV, Full/Reindex/Reuse, local SWA,
encoder-sourced decoder main KV, and gathered (not all-expert) SwiGLU.
This is a reduced research design, not a DeepSeek checkpoint reproduction.
"""
(import math)
(import mlx.core :as mx)

(defn config [[name "tiny"]]
  (setv presets
    {"tiny" [64 12 8 32 4 4 512]
     "small" [256 8 16 128 4 16 32768]
     "1b" [768 16 64 416 12 64 32768]
     "3b" [1024 24 64 608 16 64 32768]
     "30b-target" [3072 32 128 768 48 64 32768]})
  (setv [width layers experts ff heads head-dim vocab] (get presets name))
  {"name" name "width" width "layers" layers "experts" experts
   "ff" ff "heads" heads "head_dim" head-dim "vocab" vocab
   "top_k" (cond (= name "tiny") 2 (= name "30b-target") 9 True 4) "index_dim" 32
   "index_top_k" 16 "window" 128 "encoder_compression" 2
   "index_loss_weight" 0.01 "balance_loss_weight" 0.01})

(defn modes [cfg]
  (setv half (// (get cfg "layers") 2))
  (lfor i (range (get cfg "layers"))
    (cond
      (< i 2) "swa"
      (< i half) (if (= (% (- i 2) 6) 0) "full" "reuse")
      (= i half) "full"
      (= (% (- i half) 4) 0) "reindex"
      True "reuse")))

(defn shapes [cfg]
  (setv d (get cfg "width") h (get cfg "heads")
        hd (get cfg "head_dim") e (get cfg "experts")
        f (get cfg "ff") ix (get cfg "index_dim")
        result {"embedding" [(get cfg "vocab") d] "final_norm" [d]})
  (for [[i mode] (enumerate (modes cfg))]
    (setv prefix (+ "blocks." (str i) "."))
    (for [[key shape] (.items
      {"attn_norm" [d] "ffn_norm" [d]
       "q" [d (* h hd)] "local_kv" [d (* 2 hd)]
       "o" [(* h hd) d] "router" [d e]
       "expert_gate" [e d f] "expert_up" [e d f]
       "expert_down" [e f d]
       "shared_gate" [d f] "shared_up" [d f] "shared_down" [f d]})]
      (setv (get result (+ prefix key)) shape))
    (when (= mode "full")
      (setv (get result (+ prefix "main_kv")) [d (* 2 hd)]
            (get result (+ prefix "compress_z")) [d (* 2 hd)]
            (get result (+ prefix "index_k")) [hd ix]))
    (when (in mode ["full" "reindex"])
      (setv (get result (+ prefix "index_q")) [d ix])))
  result)

(defn parameter-report [cfg]
  (setv ss (shapes cfg) total 0 routed 0)
  (for [[key shape] (.items ss)]
    (setv count (math.prod shape))
    (+= total count)
    (when (in ".expert_" key) (+= routed count)))
  {"total_parameters" total "routed_parameters" routed
   ;; Logical selected-weight count, not measured FLOPs or resident bytes.
   "selected_parameters_upper_bound"
     (+ (- total routed) (* (// routed (get cfg "experts")) (get cfg "top_k")))
   "bf16_weight_bytes" (* 2 total)
   "adamw_training_state_bytes_16_per_parameter" (* 16 total)})

(defn init-parameters [cfg [seed 0] [dtype mx.bfloat16] [materialize False]]
  (mx.random.seed seed)
  (setv result {})
  (for [[key shape] (.items (shapes cfg))]
    (setv value (if (in "norm" key)
      (mx.ones shape :dtype dtype)
      (* (mx.random.normal shape :dtype dtype)
         (if (= key "embedding") 0.02 (/ 1.0 (math.sqrt (get shape -2)))))))
    (when materialize (mx.eval value))
    (setv (get result key) value))
  result)

(defn norm [x weight]
  (.astype (* x (mx.rsqrt (+ (mx.mean (mx.square (.astype x mx.float32)) :axis -1 :keepdims True)
                             1e-6)) weight) x.dtype))

(defn rope [x positions]
  (setv dim (get x.shape -1) half (// dim 2)
        freq (mx.exp (* (mx.arange half) (/ (- (math.log 10000.0)) half)))
        phase (* (mx.expand_dims positions -1) freq)
        a (get x #(... (slice 0 half))) b (get x #(... (slice half None))))
  (.astype (mx.concatenate [(- (* a (mx.cos phase)) (* b (mx.sin phase)))
                            (+ (* a (mx.sin phase)) (* b (mx.cos phase)))] :axis -1)
           x.dtype))

(defn compress-main [kv z rate]
  "Causal non-overlapping learned channel-wise compression, available at block end."
  (setv [batch seq dim] kv.shape pad (% (- seq) rate))
  (when pad
    (setv kv (mx.pad kv #(#(0 0) #(0 pad) #(0 0)))
          z (mx.pad z #(#(0 0) #(0 pad) #(0 0)) :constant_values -1e9)))
  (setv shape [batch (// (+ seq pad) rate) rate dim])
  (mx.sum (* (.reshape kv shape) (mx.softmax (.reshape z shape) :axis 2)) :axis 2))

(defn attention-kernel [q main-k main-v local-k local-v indices valid cfg]
  (setv indices (mx.stop-gradient indices) valid (mx.stop-gradient valid)
        [batch heads seq dim] q.shape
        kk (mx.take-along-axis (mx.expand_dims main-k 1)
               (mx.expand_dims indices -1) :axis 2)
        vv (mx.take-along-axis (mx.expand_dims main-v 1)
               (mx.expand_dims indices -1) :axis 2)
        positions (mx.arange seq)
        window (min seq (get cfg "window"))
        li (+ (mx.expand_dims positions -1) (mx.arange (- 1 window) 1))
        lm (>= li 0)
        li (mx.maximum li 0)
        lk (mx.take local-k li :axis 1) lv (mx.take local-v li :axis 1)
        keys (mx.concatenate [kk lk] :axis 2)
        values (mx.concatenate [vv lv] :axis 2)
        mask (mx.concatenate [valid (mx.broadcast-to lm [batch seq window])] :axis 2)
        ;; Batched matrix products avoid the B*H*S*K*D broadcast products.
        scores (/ (.transpose (@ (.transpose q [0 2 1 3]) (.swapaxes keys -1 -2))
                              [0 2 1 3]) (math.sqrt dim))
        scores (mx.where (mx.expand_dims mask 1) scores -1e9)
        probs (mx.softmax (.astype scores mx.float32) :axis -1))
  (.transpose (@ (.transpose (.astype probs values.dtype) [0 2 1 3]) values) [0 2 1 3]))

(defn selected-attention [q main-k main-v local-k local-v indices valid cfg]
  (setv apply (if (.get cfg "activation_checkpoint" False)
                  (mx.checkpoint attention-kernel) attention-kernel))
  (apply q main-k main-v local-k local-v indices valid cfg))

(defn moe-kernel [x p prefix cfg]
  (setv [batch seq dim] x.shape flat (.reshape x [-1 dim])
        logits (@ flat (get p (+ prefix "router")))
        probs (mx.softmax (.astype logits mx.float32) :axis -1)
        k (get cfg "top_k") e (get cfg "experts")
        ids (mx.stop-gradient (get (mx.argsort probs :axis -1) #(... (slice (- k) None))))
        weights (mx.take-along-axis probs ids :axis -1)
        weights (/ weights (mx.sum weights :axis -1 :keepdims True))
        expanded (mx.expand_dims (mx.expand_dims flat -2) -2)
        gate (mx.gather-mm expanded (get p (+ prefix "expert_gate")) :rhs_indices ids)
        up (mx.gather-mm expanded (get p (+ prefix "expert_up")) :rhs_indices ids)
        hidden (* gate (mx.sigmoid gate) up)
        down (mx.gather-mm hidden (get p (+ prefix "expert_down")) :rhs_indices ids)
        selected (mx.sum (* (mx.squeeze down :axis -2)
                            (mx.expand_dims (.astype weights down.dtype) -1)) :axis 1)
        sg (@ flat (get p (+ prefix "shared_gate")))
        shared (@ (* sg (mx.sigmoid sg) (@ flat (get p (+ prefix "shared_up"))))
                    (get p (+ prefix "shared_down")))
        loads (mx.mean (mx.sum (mx.equal (mx.expand_dims ids -1) (mx.arange e)) :axis 1)
                       :axis 0)
        balance (* (/ e k) (mx.sum (* (mx.stop-gradient loads) (mx.mean probs :axis 0)))))
  #((.reshape (+ selected shared) [batch seq dim]) balance loads))

(defn moe [x p prefix cfg]
  (setv apply (if (.get cfg "activation_checkpoint" False)
                  (mx.checkpoint moe-kernel) moe-kernel))
  (apply x p prefix cfg))

(defn block [p x source main-k main-v main-end index-k selection cfg i mode]
  (setv [batch seq d] x.shape heads (get cfg "heads")
        hd (get cfg "head_dim") half (// (get cfg "layers") 2)
        pos (mx.arange seq) layer-index (mx.array 0.0)
        ;; Checkpoint's local VJP also sees integer state inputs. They remain
        ;; discrete; stop only these, never main K/V, index K or CED source.
        main-end (if (is main-end None) None (mx.stop-gradient main-end))
        selection (if (is selection None) None (mx.stop-gradient selection)))
    (setv prefix (+ "blocks." (str i) ".")
          xn (norm x (get p (+ prefix "attn_norm")))
          q (.transpose (.reshape (@ xn (get p (+ prefix "q"))) [batch seq heads hd])
                        [0 2 1 3])
          q (rope q (.reshape pos [1 1 seq]))
          local (@ xn (get p (+ prefix "local_kv")))
          lk (rope (get local #(... (slice 0 hd))) (.reshape pos [1 seq]))
          lv (get local #(... (slice hd None))))
    (if (= mode "swa")
      (do
        (setv dummy (mx.zeros [batch 1 hd] :dtype x.dtype)
              empty (mx.zeros [batch seq 0] :dtype mx.int32)
              out (selected-attention q dummy dummy lk lv empty
                       (mx.zeros [batch seq 0] :dtype mx.bool_) cfg)))
      (do
        (when (= mode "full")
          (setv origin (if (< i half) xn source)
                rate (if (< i half) (get cfg "encoder_compression") 1)
                kv (compress-main (@ origin (get p (+ prefix "main_kv")))
                                  (@ origin (get p (+ prefix "compress_z"))) rate)
                count (get kv.shape 1)
                main-end (- (* (+ (mx.arange count) 1) rate) 1)
                main-k (rope (get kv #(... (slice 0 hd))) (.reshape main-end [1 count]))
                main-v (get kv #(... (slice hd None)))
                index-k (@ main-k (get p (+ prefix "index_k")))))
        (when (in mode ["full" "reindex"])
          (setv iq (@ xn (get p (+ prefix "index_q")))
                score (.astype (/ (@ iq (.swapaxes index-k -1 -2))
                                   (math.sqrt (get cfg "index_dim"))) mx.float32)
                causal (<= (mx.expand_dims main-end 0) (mx.expand_dims pos -1))
                masked (mx.where causal score -1e9)
                count (get main-k.shape 1)
                top (min count (get cfg "index_top_k"))
                selection (mx.stop-gradient
                            (get (mx.argsort masked :axis -1) #(... (slice (- top) None))))
                ;; Index selection is discrete. An explicit detached dense-attention
                ;; target trains the indexer; attention gradients train shared main KV.
                teacher-logits (mx.mean (/ (@ q (.swapaxes (mx.expand_dims main-k 1) -1 -2))
                                           (math.sqrt hd)) :axis 1)
                teacher (mx.where causal (mx.softmax (mx.where causal teacher-logits -1e9)
                                                        :axis -1) 0.0)
                teacher (mx.stop-gradient teacher)
                log-probs (- masked (mx.logsumexp masked :axis -1 :keepdims True))
                index-loss (- (mx.mean (mx.sum (* teacher log-probs) :axis -1))))
          (setv layer-index index-loss))
        (setv ends (mx.take main-end selection)
              valid (<= ends (.reshape pos [1 seq 1]))
              out (selected-attention q main-k main-v lk lv selection valid cfg))))
    (setv x (+ x (@ (.reshape (.transpose out [0 2 1 3]) [batch seq (* heads hd)])
                    (get p (+ prefix "o")))))
    (setv [ff balance load] (moe (norm x (get p (+ prefix "ffn_norm"))) p prefix cfg)
          x (+ x ff))
  #(x main-k main-v main-end index-k selection layer-index balance load))

(defn forward [p tokens cfg]
  (setv half (// (get cfg "layers") 2)
        x (mx.take (get p "embedding") tokens :axis 0)
        main-k None main-v None main-end None index-k None selection None
        source None total-index (mx.array 0.0) total-balance (mx.array 0.0) loads [])
  (for [[i mode] (enumerate (modes cfg))]
    (when (= i half)
      ;; CED source and shared KV stay differentiable across checkpoint boundaries.
      (setv source x main-k None main-v None index-k None selection None))
    (setv prefix (+ "blocks." (str i) ".")
          local-params (dfor [k v] (.items p) :if (.startswith k prefix) k v)
          [x main-k main-v main-end index-k selection index-loss balance load]
          (block local-params x source main-k main-v main-end index-k selection cfg i mode))
    (+= total-index index-loss)
    (+= total-balance balance)
    (.append loads load))
  (setv logits (@ (norm x (get p "final_norm")) (.transpose (get p "embedding") [1 0]))
        aux (+ (* (get cfg "index_loss_weight") total-index)
               (* (get cfg "balance_loss_weight") total-balance)))
  #(logits aux {"index_loss" total-index "balance_loss" total-balance
                 "expert_loads" (mx.stack loads)}))

(defn loss [p inputs targets cfg]
  (setv [logits aux stats] (forward p inputs cfg)
        flat (.reshape (.astype logits mx.float32) [-1 (get cfg "vocab")])
        target (.reshape targets [-1])
        selected (mx.squeeze (mx.take-along-axis flat (mx.expand_dims target -1) :axis -1)
                             :axis -1)
        ce (mx.mean (- (mx.logsumexp flat :axis -1) selected)))
  (+ ce aux))
