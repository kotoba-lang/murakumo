"""Fleet-only full tiny oracle vs boundary VJP, including shared/tied paths."""
(import json pathlib)
(import mlx.core :as mx)
(import model :as m)
(import sharded_model :as s)
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn run []
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3)))
        cfg (m.config "tiny") (get cfg "window") 4
        (get cfg "activation_checkpoint") True (get cfg "expert_token_chunk") 4
        p (s.initialize cfg 0 1 936 mx.float32)
        tokens (.reshape (% (mx.arange 16) (get cfg "vocab")) [1 16])
        targets (% (+ tokens 1) (get cfg "vocab"))
        [loss gradients] ((mx.value-and-grad m.loss) p tokens targets cfg))
  (mx.eval loss gradients)
  (setv results [])
  (for [world [2 3]]
    (setv [value actual] (s.local-pipeline p tokens targets cfg world) errors {})
    (assert (= (set actual) (set gradients)))
    (for [key gradients]
      (setv error (float (mx.max (mx.abs (- (get gradients key) (get actual key))))))
      (assert (bool (mx.allclose (get gradients key) (get actual key) :rtol 1e-4 :atol 2e-5)) key)
      (assert (< error 2e-5) key)
      (setv (get errors key) error))
    (assert (< (abs (- value (float loss))) 2e-5))
    (.append results {"world_size" world "loss" value "reference_loss" (float loss)
                     "keys" (len actual) "maximum_gradient_error" (max (.values errors)) "errors" errors}))
  (setv result {"pass" True "dtype" "float32" "comparisons" results
               "kernel_limit" guard "process_memory" (counters)})
  (.write_text (pathlib.Path "sharded-model-control.json") (json.dumps result :indent 2))
  (print (json.dumps result)))
(when (= __name__ "__main__") (run))
