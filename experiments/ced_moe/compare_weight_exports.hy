"""Fleet-only observational BF16 comparison; no inferred pass tolerance."""
(import argparse hashlib json math pathlib socket)
(import mlx.core :as mx)
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn digest [path]
  (setv h (hashlib.sha256))
  (with [stream (open path "rb")]
    (while (setx chunk (.read stream (* 8 1024 1024))) (.update h chunk)))
  (.hexdigest h))

(defn compare [left right output]
  (setv guard (require-hard-memory-enforcer (* 10 (** 1024 3))))
  (mx.set-cache-limit 0)
  (setv left-sha (digest left) right-sha (digest right)
        a (mx.load left) b (mx.load right) errors {} count 0 different 0 maximum 0.0)
  (assert (= (set a) (set b)) "parameter keys differ")
  (for [key a]
    (setv x (get a key) y (get b key))
    (assert (and (= x.shape y.shape) (= x.dtype y.dtype)) key)
    (setv error (float (mx.max (mx.abs (- (.astype x mx.float32) (.astype y mx.float32)))))
          changed (int (mx.sum (!= x y))))
    (assert (math.isfinite error) key)
    (+= count x.size) (+= different changed)
    (setv maximum (max maximum error)
          (get errors key) {"max_abs_error" error "different_elements" changed}))
  (setv result {"dtype" "bfloat16" "parameter_keys" (len a) "parameters" count
               "host" (socket.gethostname) "left_sha256" left-sha "right_sha256" right-sha
               "comparison_source_sha256" (digest __file__)
               "max_absolute_error" maximum "different_elements" different
               "bitwise_equal" (= different 0) "errors" errors
               "scope" "observed sequential-vs-DDP weights; no inferred BF16 tolerance qualification"
               "kernel_limit" guard "process_memory" (counters)})
  (.write_text (pathlib.Path output) (json.dumps result :indent 2))
  (print (json.dumps {"keys" (len a) "parameters" count "max_abs_error" maximum
                      "different_elements" different "bitwise_equal" (= different 0)})))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--left" :required True)
  (.add_argument parser "--right" :required True)
  (.add_argument parser "--output" :required True)
  (setv args (.parse_args parser))
  (compare args.left args.right args.output))
