"""Explicit per-tensor FP32 SGD workspace; immutable snapshots are separate."""
(import pathlib shutil tempfile)
(import mlx.core :as mx)

(defclass MasterStore []
  (defn __init__ [self parent]
    (setv self.temporary (tempfile.TemporaryDirectory :prefix ".sgd-master-" :dir parent)
          self.root (pathlib.Path self.temporary.name)
          self.bytes-read 0 self.bytes-written 0 self.restore-file-bytes 0))
  (defn restore [self key source]
    (setv target (/ self.root (+ key ".safetensors")))
    (shutil.copyfile source target)
    (+= self.restore-file-bytes (. (target.stat) st_size)))
  (defn read [self key]
    (setv path (/ self.root (+ key ".safetensors"))
          value (get (mx.load (str path)) "value"))
    (mx.eval value)
    (when (!= value.dtype mx.float32) (raise (TypeError "NVMe master must remain FP32")))
    (+= self.bytes-read value.nbytes)
    value)
  (defn write [self key value]
    (when (!= value.dtype mx.float32) (raise (TypeError "NVMe master must remain FP32")))
    (setv path (/ self.root (+ key ".safetensors"))
          temporary (.with_name path (+ key ".partial.safetensors")))
    (mx.eval value)
    (mx.save-safetensors (str temporary) {"value" value})
    (.replace temporary path)
    (+= self.bytes-written value.nbytes))
  (defn close [self] (.cleanup self.temporary)))
