"""Compare an uninterrupted run with a checkpoint continuation."""
(import argparse json pathlib)
(import mlx.core :as mx)

(defn verify [left right]
  (setv errors {})
  (for [part ["weights" "master" "m" "v"]]
    (setv a (dict (mx.load (str (/ (pathlib.Path left) (+ part ".safetensors")))))
          b (dict (mx.load (str (/ (pathlib.Path right) (+ part ".safetensors"))))))
    (assert (= (set a) (set b)) (+ part " keys mismatch"))
    (setv error (max (lfor key a (float (mx.max (mx.abs (- (get a key) (get b key))))))))
    (assert (<= error 1e-4) (+ part " restart diverged: " (str error)))
    (setv (get errors part) error))
  (setv a (json.loads (.read_text (/ (pathlib.Path left) "state.json")))
        b (json.loads (.read_text (/ (pathlib.Path right) "state.json"))))
  (for [key ["step" "tokens_seen" "rng_state" "config" "training_options"]]
    (assert (= (get a key) (get b key)) (+ "resume metadata mismatch: " key)))
  (print (json.dumps {"ok" True "checkpoint_max_abs_errors" errors} :sort_keys True)))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "uninterrupted")
  (.add_argument parser "resumed")
  (setv args (.parse_args parser))
  (verify args.uninterrupted args.resumed))
