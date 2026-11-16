"""Fleet-only FP32 AdamW oracle and state-placement control."""
(import json pathlib tempfile)
(import numpy :as np)
(import mlx.core :as mx)
(import mlx.optimizers :as optim)
(import optimizer_shards :as o)
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn run []
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3)))
        options {"optimizer" "adamw" "lr" 0.0003 "warmup_steps" 4
                 "weight_decay" 0.01 "clip" 1.0 "beta1" 0.9 "beta2" 0.95 "eps" 1e-8}
        p {"w" (.reshape (mx.linspace -0.5 0.5 32) [4 8])}
        reference {"w" (mx.array (get p "w"))}
        oracle (.astype (np.array (get p "w")) np.float64)
        moment (np.zeros_like oracle) variance (np.zeros_like oracle)
        library (optim.AdamW :learning_rate 0.0003 :betas [0.9 0.95] :eps 1e-8
                           :weight_decay 0.01 :bias_correction True)
        errors [])
  (with [temporary (tempfile.TemporaryDirectory :dir "." :prefix "optimizer-control-")]
    (setv store (o.OptimizerStore temporary "adamw"))
    (store.initialize p)
    (for [step (range 1 7)]
      (setv g (.reshape (mx.sin (+ (mx.arange 32) step)) [4 8])
            global-norm 7.0 clipped (* g (/ 1.0 (+ global-norm 1e-8)))
            lr (o.learning-rate options step))
      (o.update p {"w" g} store options step global-norm)
      (setv library.learning_rate lr reference (library.apply-gradients {"w" clipped} reference))
      (mx.eval reference library.state)
      (setv ng (.astype (np.array clipped) np.float64)
            moment (+ (* 0.9 moment) (* 0.1 ng))
            variance (+ (* 0.95 variance) (* 0.05 (* ng ng)))
            oracle (- (* oracle (- 1 (* lr 0.01)))
                      (* lr (/ (/ moment (- 1 (** 0.9 step)))
                               (+ (np.sqrt (/ variance (- 1 (** 0.95 step)))) 1e-8))))
            errors-row {})
      (for [[kind expected] [["master" oracle] ["m" moment] ["v" variance]]]
        (setv actual (np.array (store.read kind "w"))
              error (float (np.max (np.abs (- actual expected)))))
        (assert (< error 2e-6) kind)
        (setv (get errors-row kind) error))
      (setv library-error (float (mx.max (mx.abs (- (get p "w") (get reference "w"))))))
      (assert (< library-error 2e-6))
      (setv (get errors-row "mlx_adamw_bias_corrected_weight") library-error)
      (.append errors errors-row))
    (setv result {"pass" True "options" options "steps" 6 "dtype" "float32"
                 "independent_oracle" "NumPy float64 equations plus MLX AdamW bias_correction=True"
                 "errors" errors "read_bytes" store.bytes_read "write_bytes" store.bytes_written
                 "memory_enforcer" guard "process_memory" (counters)})
    (store.close))
  (.write_text (pathlib.Path "adam-optimizer-control.json") (json.dumps result :indent 2))
  (print (json.dumps result)))
(when (= __name__ "__main__") (run))
