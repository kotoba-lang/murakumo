"""Fleet-only physical2/3-owner AdamW vs small full-model oracle."""
(import argparse json pathlib socket types)
(import mlx.core :as mx)
(import model :as m)
(import sharded_model :as s)
(import adamw_pipeline :as pipeline)
(import train)
(import sharded_mesh [Mesh])
(import optimizer_shards :as optimizer)
(import mlx.optimizers :as optim)
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn run [args]
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3)))
        mesh (Mesh args.world_size) hosts (mesh.gather (socket.gethostname))
        cfg (m.config "tiny") (get cfg "window") 4
        (get cfg "activation_checkpoint") True (get cfg "expert_token_chunk") 4
        output (pathlib.Path args.output))
  (assert (= (len (set hosts)) args.world_size))
  (.mkdir output :exist_ok False)
  (mx.set-cache-limit 0)
  (setv p (s.initialize cfg mesh.rank mesh.world 936 mx.float32)
        reference (s.initialize cfg 0 1 936 mx.float32)
        master (optimizer.OptimizerStore output "adamw")
        reference-optimizer (optim.AdamW :learning_rate 0.0001 :betas [0.9 0.95]
                          :eps 1e-8 :weight_decay 0.01 :bias_correction True)
        options {"optimizer" "adamw" "lr" 0.0001 "warmup_steps" 4
                 "beta1" 0.9 "beta2" 0.95 "eps" 1e-8 "weight_decay" 0.01 "clip" 1.0}
        owned (mesh.gather (list p)) rows [])
  (assert (= (set (sum owned [])) (set reference)))
  (assert (= (sum (lfor keys owned (len keys))) (len reference)))
  (master.initialize p)
  (for [step (range 1 4)]
    (setv tokens (.reshape (% (+ (mx.arange 16) step) (get cfg "vocab")) [1 16])
          targets (% (+ tokens 1) (get cfg "vocab"))
          [expected ref-gradients] ((mx.value-and-grad m.loss) reference tokens targets cfg))
    (mx.eval expected ref-gradients)
    (setv [value gradients compute diagnostics] (pipeline.forward p tokens targets cfg mesh True)
          errors {})
    (assert (< (abs (- value (float expected))) 2e-5))
    (for [key p]
      (setv error (float (mx.max (mx.abs (- (get gradients key) (get ref-gradients key))))))
      (assert (< error 2e-5) key)
      (assert (bool (mx.allclose (get gradients key) (get ref-gradients key) :rtol 1e-4 :atol 2e-5)) key)
      (setv (get errors key) error))
    (setv norm (pipeline.global-norm gradients mesh))
    (optimizer.update p gradients master options step norm)
    (setv ref-norm (float (mx.sqrt (sum (gfor g (.values ref-gradients) (mx.sum (mx.square g))))))
          reference-optimizer.learning_rate (optimizer.learning-rate options step)
          clipped (dfor [key value] (.items ref-gradients) key (* value (min 1.0 (/ 1.0 (+ ref-norm 1e-8)))))
          reference (reference-optimizer.apply-gradients clipped reference)
          weight-error 0.0 master-error 0.0)
    (mx.eval reference reference-optimizer.state)
    (for [key p]
      (setv error (float (mx.max (mx.abs (- (get p key) (get reference key)))))
            me (float (mx.max (mx.abs (- (master.read "master" key) (get reference key))))))
      (assert (< error 2e-5) key) (assert (< me 2e-5) key)
      (for [kind ["m" "v"]]
        (assert (< (float (mx.max (mx.abs (- (master.read kind key)
                               (get (get reference-optimizer.state key) kind))))) 2e-5) kind))
      (setv weight-error (max weight-error error) master-error (max master-error me)))
    (assert (< (abs (- norm ref-norm)) 2e-5))
    (.append rows {"step" step "loss" value "reference_loss" (float expected)
                  "gradient_error" (max (.values errors)) "weight_error" weight-error
                  "master_error" master-error "global_norm" norm "reference_norm" ref-norm}))
  (master.close)
  (.write_text (/ output "result.json") (json.dumps {"pass" True "rank" mesh.rank
      "world_size" mesh.world "host" (socket.gethostname) "owned_keys" (list p)
      "owned_key_counts" (lfor keys owned (len keys)) "trace" rows "mesh" mesh.stats
      "kernel_limit" guard "process_memory" (counters)} :indent 2))
  (print (json.dumps {"pass" True "rank" mesh.rank "trace" rows}) :flush True))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--world-size" :type int :required True)
  (.add_argument parser "--output" :required True)
  (run (.parse_args parser)))
