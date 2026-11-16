"""Materialized, consumed-gradient accumulation; no per-micro optimizer step."""
(import mlx.core :as mx)

(defn accumulate [total gradients]
  (when (is total None) (return gradients))
  (assert (= (set total) (set gradients)) "accumulated parameter set differs")
  (for [key (list total)]
    (setv gradient (.pop gradients key))
    (assert (= (. (get total key) dtype) gradient.dtype) "gradient dtype differs")
    (setv (get total key) (+ (get total key) gradient))
    (mx.eval (get total key)))
  total)

(defn mean-gradients [total count]
  (assert (> count 0))
  (for [key (list total)]
    (setv value (get total key)
          (get total key) (.astype (/ value count) value.dtype))
    (mx.eval (get total key)))
  total)
