"""Fleet-only FP32 gradient/update equivalence with a real kernel limit."""
(import argparse json math pathlib)
(import mlx.core :as mx)
(import model :as m)
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn check [output]
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3)))
        cfg (m.config "tiny")
        (get cfg "vocab") 97 (get cfg "window") 4
        p (m.init-parameters cfg 936 mx.float32 :materialize True)
        x (.reshape (% (mx.arange 11) 97) [1 11])
        y (% (+ x 1) 97) diff (mx.value-and-grad m.loss)
        [loss0 grad0] (diff p x y cfg))
  (mx.eval loss0 grad0)
  (setv remat (dict cfg) (get remat "activation_checkpoint") True
        [loss1 grad1] (diff p x y remat))
  (mx.eval loss1 grad1)
  (assert (= (set grad0) (set grad1) (set p)))
  (setv errors (dfor k p k (float (mx.max (mx.abs (- (get grad0 k) (get grad1 k))))))
        worst (max (.values errors))
        loss-error (abs (- (float loss0) (float loss1))))
  (assert (< loss-error 1e-6) (str loss-error))
  (assert (< worst 1e-5) (str worst))
  (setv master0 (dfor [k v] (.items p) k v)
        update-error (max (gfor k p
          (float (mx.max (mx.abs (- (- (get master0 k) (* 0.001 (get grad0 k)))
                                    (- (get master0 k) (* 0.001 (get grad1 k))))))))))
  (assert (< update-error 1e-6))
  (setv result {"ok" True "dtype" "float32" "loss_absolute_error" loss-error
               "max_gradient_absolute_error" worst "max_update_absolute_error" update-error
               "gradient_keys_compared" (len errors) "gradient_errors" errors
               "kernel_limit" guard "process_memory" (counters)
               "scope" "all layer/source/shared-KV/tied-embedding gradients; no detach"})
  (.write_text (pathlib.Path output) (json.dumps result :indent 2))
  (print (json.dumps {"ok" True "loss_error" loss-error "gradient_error" worst
                      "update_error" update-error "keys" (len errors)})))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--output" :required True)
  (setv args (.parse_args parser))
  (check args.output))
