"""Owner-local FP32 master/m/v on NVMe, consumed one tensor at a time."""
(import math pathlib shutil tempfile)
(import mlx.core :as mx)

(defclass OptimizerStore []
  (defn __init__ [self parent optimizer]
    (when (not (in optimizer ["sgd" "adamw"])) (raise (ValueError "unsupported optimizer")))
    (setv self.kinds (if (= optimizer "adamw") ["master" "m" "v"] ["master"])
          self.temporary (tempfile.TemporaryDirectory :prefix ".optimizer-shards-" :dir parent)
          self.root (pathlib.Path self.temporary.name)
          self.bytes_read (dfor kind self.kinds kind 0)
          self.bytes_written (dfor kind self.kinds kind 0)
          self.restore_file_bytes (dfor kind self.kinds kind 0))
    (for [kind self.kinds] (.mkdir (/ self.root kind))))

  (defn path [self kind key]
    (when (not (in kind self.kinds)) (raise (ValueError "unknown optimizer state kind")))
    (/ self.root kind (+ key ".safetensors")))

  (defn read [self kind key]
    (setv value (get (mx.load (str (.path self kind key))) "value"))
    (mx.eval value)
    (when (!= value.dtype mx.float32) (raise (TypeError "optimizer state must remain FP32")))
    (+= (get self.bytes_read kind) value.nbytes)
    value)

  (defn write [self kind key value]
    (when (!= value.dtype mx.float32) (raise (TypeError "optimizer state must remain FP32")))
    (setv path (.path self kind key) temporary (.with_name path (+ key ".partial.safetensors")))
    (mx.eval value)
    (mx.save-safetensors (str temporary) {"value" value})
    (.replace temporary path)
    (+= (get self.bytes_written kind) value.nbytes))

  (defn restore [self kind key source]
    (setv path (.path self kind key))
    (shutil.copyfile source path)
    (+= (get self.restore_file_bytes kind) (. (path.stat) st_size)))

  (defn initialize [self p]
    (for [[key value] (.items p)]
      (.write self "master" key (.astype value mx.float32))
      (for [kind self.kinds]
        (when (!= kind "master") (.write self kind key (mx.zeros value.shape :dtype mx.float32))))))

  (defn close [self] (.cleanup self.temporary)))

(defn learning-rate [options step]
  (when (< step 1) (raise (ValueError "optimizer step is one-based")))
  (* (get options "lr") (if (get options "warmup_steps")
      (min 1.0 (/ step (get options "warmup_steps"))) 1.0)))

(defn update [p gradients store options step global-norm]
  (when (or (not (math.isfinite global-norm)) (< global-norm 0))
    (raise (FloatingPointError "invalid global gradient norm")))
  (setv lr (learning-rate options step) scale (min 1.0 (/ (get options "clip") (+ global-norm 1e-8))))
  (for [key (list p)]
    (setv value (get p key) g (* (.astype (get gradients key) mx.float32) scale)
          master (store.read "master" key))
    (if (= (get options "optimizer") "adamw")
      (do
        (setv beta1 (get options "beta1") beta2 (get options "beta2")
              moment (+ (* beta1 (store.read "m" key)) (* (- 1 beta1) g))
              variance (+ (* beta2 (store.read "v" key)) (* (- 1 beta2) (mx.square g))))
        (mx.eval moment variance)
        (setv direction (/ (/ moment (- 1 (** beta1 step)))
                          (+ (mx.sqrt (/ variance (- 1 (** beta2 step)))) (get options "eps"))))
        (store.write "m" key moment) (store.write "v" key variance))
      (setv direction g))
    (setv next-master (- (* master (- 1 (* lr (get options "weight_decay")))) (* lr direction))
          next-value (.astype next-master value.dtype))
    (mx.eval next-master next-value)
    (store.write "master" key next-master)
    (setv (get p key) next-value)
    (.pop gradients key))
  lr)
