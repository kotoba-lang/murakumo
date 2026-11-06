"""Fleet-only physical2/3-owner SGD vs small full-model oracle."""
(import argparse json pathlib socket types)
(import mlx.core :as mx)
(import model :as m)
(import sharded_model :as s)
(import sharded_pipeline :as pipeline)
(import train)
(import sharded_mesh [Mesh])
(import nvme_master [MasterStore])
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
        master (MasterStore output) ref-master (MasterStore output)
        options (types.SimpleNamespace :master_offload "nvme" :optimizer "sgd"
                   :lr 0.001 :weight_decay 0.01 :clip 1.0)
        owned (mesh.gather (list p)) rows [])
  (assert (= (set (sum owned [])) (set reference)))
  (assert (= (sum (lfor keys owned (len keys))) (len reference)))
  (for [[key value] (.items p)] (master.write key (.astype value mx.float32)))
  (for [[key value] (.items reference)] (ref-master.write key (.astype value mx.float32)))
  (for [step (range 1 4)]
    (setv tokens (.reshape (% (+ (mx.arange 16) step) (get cfg "vocab")) [1 16])
          targets (% (+ tokens 1) (get cfg "vocab"))
          [expected ref-gradients] ((mx.value-and-grad m.loss) reference tokens targets cfg))
    (mx.eval expected ref-gradients)
    (setv [value gradients compute] (pipeline.forward p tokens targets cfg mesh True)
          errors {})
    (assert (< (abs (- value (float expected))) 2e-5))
    (for [key p]
      (setv error (float (mx.max (mx.abs (- (get gradients key) (get ref-gradients key))))))
      (assert (< error 2e-5) key)
      (assert (bool (mx.allclose (get gradients key) (get ref-gradients key) :rtol 1e-4 :atol 2e-5)) key)
      (setv (get errors key) error))
    (setv norm (pipeline.global-norm gradients mesh))
    (pipeline.update p gradients master 0.001 0.01 1.0 norm)
    (setv ref-norm (train.step-update reference ref-gradients {"master" ref-master} step options)
          weight-error 0.0 master-error 0.0)
    (for [key p]
      (setv error (float (mx.max (mx.abs (- (get p key) (get reference key)))))
            me (float (mx.max (mx.abs (- (master.read key) (ref-master.read key))))))
      (assert (< error 2e-5) key) (assert (< me 2e-5) key)
      (setv weight-error (max weight-error error) master-error (max master-error me)))
    (assert (< (abs (- norm ref-norm)) 2e-5))
    (.append rows {"step" step "loss" value "reference_loss" (float expected)
                  "gradient_error" (max (.values errors)) "weight_error" weight-error
                  "master_error" master-error "global_norm" norm "reference_norm" ref-norm}))
  (master.close) (ref-master.close)
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
