"""Bounded-size exact-dtype gradient averaging for replicated trials."""
(import mlx.core :as mx)

(defn average-gradients [grads group [bucket-bytes (* 64 1024 1024)]]
  (setv buckets [] keys [] size 0 dtype None logical 0)
  (for [[key g] (.items grads)]
    (when (> g.nbytes bucket-bytes)
      (raise (ValueError "gradient tensor exceeds admitted communication bucket")))
    (when (and keys (or (!= dtype g.dtype) (> (+ size g.nbytes) bucket-bytes)))
      (.append buckets keys) (setv keys [] size 0))
    (.append keys key)
    (setv dtype g.dtype)
    (+= size g.nbytes) (+= logical g.nbytes))
  (when keys (.append buckets keys))
  (for [keys buckets]
    (setv shapes (lfor key keys (. (get grads key) shape))
          sizes (lfor key keys (. (get grads key) size))
          dtype (. (get grads (get keys 0)) dtype)
          packed (mx.concatenate (lfor key keys (.reshape (get grads key) [-1]))))
    (mx.eval packed)
    (setv averaged (.astype (/ (mx.distributed.all-sum packed :group group) (.size group)) dtype))
    (mx.eval averaged)
    (setv offset 0)
    (for [[key shape count] (zip keys shapes sizes)]
      (setv (get grads key) (.reshape (get averaged (slice offset (+ offset count))) shape))
      (+= offset count))
    (mx.eval grads)
    (del packed averaged))
  {"logical_gradient_bytes" logical "collectives" (len buckets)
   "ring_tx_estimated_bytes" (* logical (/ (* 2 (- (.size group) 1)) (.size group)))
   "bucket_limit_bytes" bucket-bytes})

(defn sequential-mean [gradient-fn p x y cfg micros local-batch]
  "Mean of the same rank-local objectives, including local router balancing."
  (setv total None values [])
  (for [rank (range micros)]
    (setv section (slice (* rank local-batch) (* (+ rank 1) local-batch))
          [value gradients] (gradient-fn p (get x section) (get y section) cfg))
    (mx.eval value gradients)
    (.append values (float value))
    (if (is total None)
      (setv total gradients)
      (for [key (list total)]
        (setv (get total key) (+ (get total key) (.pop gradients key)))
        (mx.eval (get total key)))))
  (for [key (list total)]
    (setv (get total key) (.astype (/ (get total key) micros) (. (get total key) dtype)))
    (mx.eval (get total key)))
  #((/ (sum values) micros) total))
