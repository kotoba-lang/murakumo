"""Serial model-parallel schedule. Full weights/masters belong only to owners.

Each layer partition is recomputed for its local VJP; cross-boundary source/KV
cotangents return through every stage. No pipeline overlap is claimed.
"""
(import math time)
(import mlx.core :as mx)
(import sharded_model :as s)

(defn forward [p tokens targets cfg mesh [gradient False] [aux False]]
  (setv compute 0.0 tick (time.monotonic) state None discrete None saved None)
  (when (= mesh.rank 0)
    (setv [state discrete] (s.initial-state (mx.take (get p "embedding") tokens :axis 0) cfg))
    (mx.eval state discrete))
  (+= compute (- (time.monotonic) tick))
  (setv joined (mesh.broadcast (when (= mesh.rank 0) (| state discrete)) 0 "embedding_forward")
        state (dfor key s.FLOATS key (get joined key))
        discrete (dfor key ["main_end" "selection"] key (get joined key)))
  (for [rank (range mesh.world)]
    (when (= mesh.rank rank)
      (setv tick (time.monotonic) [start end] (s.bounds cfg rank mesh.world)
            local (dfor [key value] (.items p) :if (.startswith key "blocks.") key value)
            [outputs ints] (s.stage local state discrete cfg start end))
      (mx.eval outputs ints)
      (when gradient (setv saved #(local state discrete outputs start end)))
      (+= compute (- (time.monotonic) tick)))
    (setv joined (mesh.broadcast (when (= mesh.rank rank) (| outputs ints)) rank "stage_forward")
          state (dfor key s.FLOATS key (get joined key))
          discrete (dfor key ["main_end" "selection"] key (get joined key))))
  (setv gradients {} cotangent None value None diagnostics None tick (time.monotonic))
  (when (= mesh.rank 0)
    (if gradient
      (setv [value gradients cotangent] (s.head-vjp p state targets cfg))
      (setv value (float (s.head p state targets cfg aux))))
    (setv diagnostics {"loss" (mx.array value)
                      "ce" (s.head p state targets cfg False)
                      "index_loss" (get state "total_index")
                      "balance_loss" (get state "total_balance")} )
    (mx.eval diagnostics))
  (+= compute (- (time.monotonic) tick))
  (setv diagnostics (mesh.broadcast diagnostics 0 "loss")
        value (float (get diagnostics "loss")))
  (when gradient
    (setv cotangent (mesh.broadcast cotangent 0 "head_backward"))
    (for [rank (reversed (range mesh.world))]
      (when (= mesh.rank rank)
        (setv tick (time.monotonic) [local inputs ints outputs start end] saved
              [part upstream] (s.stage-vjp local inputs ints outputs cotangent cfg start end))
        (.update gradients part)
        (+= compute (- (time.monotonic) tick)))
      (setv cotangent (mesh.broadcast (when (= mesh.rank rank) upstream) rank "stage_backward")))
    (when (= mesh.rank 0)
      (setv tick (time.monotonic)
            (get gradients "embedding") (+ (get gradients "embedding")
                                            (s.embedding-vjp p tokens (get cotangent "x"))))
      (mx.eval gradients)
      (+= compute (- (time.monotonic) tick)))
    (when (!= (set gradients) (set p)) (raise (ValueError "owned gradient coverage mismatch"))))
  #(value gradients compute (dfor [key value] (.items diagnostics) key (float value))))

(defn global-norm [gradients mesh]
  (setv local 0.0)
  (for [value (.values gradients)]
    ;; One FP32 conversion/square reduction at a time, not all3B temporaries.
    (setv term (mx.sum (mx.square (.astype value mx.float32))))
    (mx.eval term)
    (+= local (float term)))
  (setv total (float (get (mesh.sum (mx.array [local]) "global_norm") 0)))
  (when (not (math.isfinite total)) (raise (FloatingPointError "non-finite global norm")))
  (math.sqrt total))

(defn update [p gradients store lr decay clip norm]
  (setv scale (min 1.0 (/ clip (+ norm 1e-8))))
  (for [key (list p)]
    (setv value (get p key) master (store.read key)
          next-master (- (* master (- 1 (* lr decay)))
                         (* lr scale (.astype (get gradients key) mx.float32)))
          next-value (.astype next-master value.dtype))
    (mx.eval next-master next-value)
    (store.write key next-master)
    (setv (get p key) next-value)
    (.pop gradients key)))
