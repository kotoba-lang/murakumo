"""Fleet-only FP32 gradient/update equivalence with a real kernel limit."""
(import argparse hashlib json math pathlib runpy)
(import mlx.core :as mx)
(import model :as m)
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn check [output [reference-source None]]
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3)))
        cfg (m.config "tiny")
        (get cfg "vocab") 97 (get cfg "window") 4 (get cfg "expert_token_chunk") 4
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
  (setv unchunked (dict cfg) (get unchunked "expert_token_chunk") 64
        [whole-loss whole-grad] (diff p x y unchunked))
  (mx.eval whole-loss whole-grad)
  (setv chunk-only-error (max (gfor k p
    (float (mx.max (mx.abs (- (get whole-grad k) (get grad0 k))))))))
  (assert (< chunk-only-error 1e-5) (str chunk-only-error))
  (assert (< (abs (- (float whole-loss) (float loss0))) 1e-6))
  (setv reference-error None)
  (when reference-source
    (setv path (pathlib.Path reference-source)
          expected "5c0915427f140de1327a63fea920da705638e5feef4fae36a2ef32271fe28628")
    (assert (= (.hexdigest (hashlib.sha256 (.read_bytes path))) expected))
    (setv reference (runpy.run_path (str path))
          [old-loss old-grad] ((mx.value-and-grad (get reference "loss")) p x y cfg))
    (mx.eval old-loss old-grad)
    (setv reference-error (max (gfor k p
      (float (mx.max (mx.abs (- (get old-grad k) (get grad0 k))))))))
    (assert (< (abs (- (float old-loss) (float loss0))) 1e-6))
    ;; Batched products and chunked gradient sums change FP32 reduction order.
    ;; Check each element with absolute+relative bounds, including near-zero
    ;; gradients, as well as retaining the observed maximum absolute error.
    (for [k p]
      (assert (bool (mx.allclose (get old-grad k) (get grad0 k) :rtol 1e-4 :atol 2e-5)) k))
    (assert (< reference-error 2e-5) (str reference-error)))
  (setv result {"ok" True "dtype" "float32" "loss_absolute_error" loss-error
               "max_gradient_absolute_error" worst "max_update_absolute_error" update-error
               "gradient_keys_compared" (len errors) "gradient_errors" errors
               "kernel_limit" guard "process_memory" (counters)
               "prior_source_gradient_error" reference-error "expert_token_chunk" 4 "sequence" 11
               "prior_source_elementwise_rtol" 1e-4 "prior_source_elementwise_atol" 2e-5
               "chunk_only_gradient_error" chunk-only-error
               "scope" "all layer/source/shared-KV/tied-embedding gradients; no detach"})
  (.write_text (pathlib.Path output) (json.dumps result :indent 2))
  (print (json.dumps {"ok" True "loss_error" loss-error "gradient_error" worst
                      "update_error" update-error "keys" (len errors)})))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--output" :required True)
  (.add_argument parser "--reference-source")
  (setv args (.parse_args parser))
  (check args.output args.reference_source))
