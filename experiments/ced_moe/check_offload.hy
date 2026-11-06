"""Fleet-only comparison: resident vs NVMe FP32 SGD masters, three updates."""
(import argparse json pathlib tempfile types)
(import mlx.core :as mx)
(import model :as m)
(import train)
(import memory_policy [require-hard-memory-enforcer])
(import nvme_master [MasterStore])
(import process_memory [counters])

(defn check [output]
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3)))
        cfg (m.config "tiny") (get cfg "vocab") 97
        (get cfg "window") 4 (get cfg "activation_checkpoint") True
        p (m.init-parameters cfg 936 mx.bfloat16 :materialize True) q (dict p)
        state {"master" (dfor [k v] (.items p) k (.astype v mx.float32))}
        temp (tempfile.TemporaryDirectory) store (MasterStore temp.name)
        disk {"master" store}
        x (.reshape (mx.arange 11) [1 11]) y (+ x 1)
        diff (mx.value-and-grad m.loss)
        plain (types.SimpleNamespace :master_offload "none" :optimizer "sgd"
                :lr 0.001 :weight_decay 0.01 :clip 1.0)
        offload (types.SimpleNamespace :master_offload "nvme" :optimizer "sgd"
                  :lr 0.001 :weight_decay 0.01 :clip 1.0)
        worst-weight 0.0 worst-master 0.0)
  (for [[k v] (.items (get state "master"))]
    (.write store k v)
    (assert (bool (mx.array-equal v (.read store k))) "FP32 disk roundtrip must be exact"))
  (for [step (range 1 4)]
    ;; Compare optimizer placement under identical gradient inputs; two
    ;; independent gather/scatter backward passes need not be bitwise equal.
    (setv [loss-p grad-p] (diff p x y cfg) grad-q (dict grad-p))
    (mx.eval loss-p grad-p)
    (train.step-update p grad-p state step plain)
    (train.step-update q grad-q disk step offload)
    (assert (and (not grad-p) (not grad-q)) "consumed gradients must be released")
    (for [k p]
      (setv disk-master (.read store k)
            worst-weight (max worst-weight (float (mx.max (mx.abs (- (get p k) (get q k))))))
            worst-master (max worst-master
              (float (mx.max (mx.abs (- (get (get state "master") k) disk-master))))))))
  ;; Placement/loading can change fused GPU evaluation order. Serialization
  ;; itself is exact above; require a tight numerical update comparison.
  (assert (<= worst-weight 1e-6) (str worst-weight))
  (assert (<= worst-master 1e-6) (str worst-master))
  (setv result {"ok" True "steps" 3 "weight_dtype" "bfloat16" "master_dtype" "float32"
               "max_weight_error" worst-weight "max_master_error" worst-master
               "update_absolute_tolerance" 1e-6 "initial_master_roundtrip_exact" True
               "scope" "three SGD updates with identical gradients; not BF16 end-to-end reproducibility"
               "gradient_trees_consumed" True "kernel_limit" guard
               "logical_offload_read_bytes" store.bytes-read
               "logical_offload_write_bytes" store.bytes-written
               "process_memory" (counters)})
  (.close store) (.cleanup temp)
  (.write_text (pathlib.Path output) (json.dumps result :indent 2))
  (print (json.dumps result)))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--output" :required True)
  (setv args (.parse_args parser))
  (check args.output))
