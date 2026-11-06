"""Fleet-only comparison of all tiny-model gradients, weights and masters."""
(import argparse json pathlib)
(import mlx.core :as mx)
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn verify [reference replica output]
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3)))
        errors {})
  (for [part ["gradient-step1" "weights" "masters"]]
    (setv left (mx.load (str (/ (pathlib.Path reference) (+ part ".safetensors"))))
          right (mx.load (str (/ (pathlib.Path replica) (+ part ".safetensors")))))
    (assert (= (set left) (set right)) "parameter set mismatch")
    (setv maximum 0.0)
    (for [key left]
      (setv a (get left key) b (get right key)
            error (float (mx.max (mx.abs (- a b)))))
      (assert (bool (mx.allclose a b :rtol 1e-4 :atol 1e-5)) (+ part ":" key))
      (setv maximum (max maximum error)))
    (assert (< maximum 1e-4) (+ part ":" (str maximum)))
    (setv (get errors part) {"keys" (len left) "max_absolute_error" maximum}))
  (setv result {"ok" True "comparisons" errors "dtype" "float32"
               "objective" "mean of same two local objectives; local MoE balance"
               "kernel_limit" guard "process_memory" (counters)})
  (.write_text (pathlib.Path output) (json.dumps result :indent 2))
  (print (json.dumps result)))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--reference" :required True)
  (.add_argument parser "--replica" :required True)
  (.add_argument parser "--output" :required True)
  (setv args (.parse_args parser))
  (verify args.reference args.replica args.output))
