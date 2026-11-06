"""Equivalent FP32 owner state in RAM or NVMe; checkpoint backend is explicit."""
(import pathlib shutil)
(import mlx.core :as mx)
(import optimizer_shards :as legacy)

(defclass OptimizerStore []
  (defn __init__ [self parent optimizer placement]
    (when (not (in placement ["ram" "nvme"])) (raise (ValueError "unsupported state placement")))
    (when (not (in optimizer ["sgd" "adamw"])) (raise (ValueError "unsupported optimizer")))
    (setv self.placement placement self.kinds (if (= optimizer "adamw") ["master" "m" "v"] ["master"])
          self.backend (when (= placement "nvme") (legacy.OptimizerStore parent optimizer))
          self.values (dfor kind self.kinds kind {})
          self.bytes_read (dfor kind self.kinds kind 0)
          self.bytes_written (dfor kind self.kinds kind 0)
          self.restore_file_bytes (dfor kind self.kinds kind 0)))

  (defn read [self kind key]
    (setv value (if self.backend (self.backend.read kind key) (get (get self.values kind) key)))
    (mx.eval value)
    (when (!= value.dtype mx.float32) (raise (TypeError "optimizer state must remain FP32")))
    (+= (get self.bytes_read kind) value.nbytes)
    value)

  (defn write [self kind key value]
    (when (!= value.dtype mx.float32) (raise (TypeError "optimizer state must remain FP32")))
    (mx.eval value)
    (if self.backend (self.backend.write kind key value)
      (setv (get (get self.values kind) key) (mx.stop-gradient value)))
    (+= (get self.bytes_written kind) value.nbytes))

  (defn initialize [self p]
    (for [[key value] (.items p)]
      (self.write "master" key (.astype value mx.float32))
      (for [kind self.kinds]
        (when (!= kind "master") (self.write kind key (mx.zeros value.shape :dtype mx.float32))))))

  (defn restore [self kind key source]
    (if self.backend (self.backend.restore kind key source)
      (do
        (setv value (get (mx.load (str source)) "value"))
        (mx.eval value)
        (when (!= value.dtype mx.float32) (raise (TypeError "restored optimizer state must be FP32")))
        (setv (get (get self.values kind) key) value)))
    (+= (get self.restore_file_bytes kind) (. (source.stat) st_size)))

  (defn export [self kind key target]
    (if self.backend (shutil.copyfile (self.backend.path kind key) target)
      (mx.save-safetensors (str target) {"value" (get (get self.values kind) key)})))

  (defn close [self]
    (when self.backend (self.backend.close))
    (self.values.clear)))
