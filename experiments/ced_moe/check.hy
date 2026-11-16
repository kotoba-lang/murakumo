"""Meaningful numerical controls before allocating a billion parameters."""
(import json sys)
(import mlx.core :as mx)
(import model :as m)

(defn check []
  (setv c (m.config) (get c "width") 16 (get c "layers") 12
        (get c "heads") 2 (get c "head_dim") 8
        (get c "ff") 16 (get c "experts") 4 (get c "vocab") 32
        (get c "window") 4 (get c "index_top_k") 3
        p (m.init-parameters c :dtype mx.float32)
        x (mx.array [[1 2 3 4 5 6 7 8 9 10 11]])
        y (mx.array [[2 3 4 5 6 7 8 9 10 11 12]])
        [a aux stats] (m.forward p x c)
        changed (mx.array [[1 2 3 4 5 6 7 18 19 20 21]])
        [b _ _] (m.forward p changed c)
        causal-error (float (mx.max (mx.abs (- (get a #(0 (slice 0 7)))
                                                (get b #(0 (slice 0 7))))))))
  (assert (< causal-error 1e-5) f"future-token leakage: {causal-error}")
  (assert (= (get c "top_k") (int (mx.max (mx.sum (get stats "expert_loads") :axis -1)))))
  (setv [value grads] ((mx.value-and-grad m.loss) p x y c))
  (assert (all (gfor g (.values grads) (bool (mx.all (mx.isfinite g))))))
  (setv probes ["blocks.2.main_kv" "blocks.2.compress_z" "blocks.2.index_q"
               "blocks.2.index_k" "blocks.6.main_kv" "blocks.10.index_q"
               "blocks.6.router" "embedding"]
        norms {})
  (for [key probes]
    (setv magnitude (float (mx.max (mx.abs (get grads key))))
          (get norms key) magnitude)
    (assert (> magnitude 1e-9) f"missing gradient: {key}"))
  ;; A decoder-only objective must backpropagate through CED to the encoder.
  ;; Central differences also check repeated shared-source gradient accumulation.
  (setv key "blocks.2.main_kv" idx #(0 0) epsilon 0.002
        plus (dict p) minus (dict p)
        delta (mx.zeros-like (get p key)))
  (setv delta (.add (get delta.at idx) epsilon)
        (get plus key) (+ (get p key) delta)
        (get minus key) (- (get p key) delta)
        numeric (/ (- (float (m.loss plus x y c)) (float (m.loss minus x y c)))
                   (* 2 epsilon))
        analytic (float (get (get grads key) idx))
        difference (abs (- numeric analytic)))
  (assert (< difference (+ 0.0005 (* 0.05 (abs analytic))))
          f"shared KV VJP: numerical={numeric}, analytic={analytic}")
  ;; A failed causal mask must be observable: position 7's changed token changes its own logits.
  (assert (> (float (mx.max (mx.abs (- (get a #(0 7)) (get b #(0 7)))))) 1e-4))
  (setv low (m.init-parameters c :dtype mx.bfloat16)
        [low-logits _ _] (m.forward low x c))
  (assert (= low-logits.dtype mx.bfloat16) "accidental FP32 residual promotion")
  (print (json.dumps {"ok" True "loss" (float value) "causal_error" causal-error
                     "shared_kv_vjp_error" difference "gradient_max_abs" norms
                     "modes" (m.modes c)} :sort_keys True)))

(when (= __name__ "__main__") (check))
