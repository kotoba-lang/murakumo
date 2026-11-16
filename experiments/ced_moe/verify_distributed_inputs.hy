"""CPU-only reconstruction of logged global and rank-local sampler inputs."""
(import argparse hashlib json pathlib)
(import numpy :as np)

(defn verify [args]
  (setv root (pathlib.Path args.root)
        snapshot (pathlib.Path args.snapshot)
        state (json.loads (.read_text (/ snapshot "state.json")))
        reference (json.loads (.read_text (/ root "reference.json")))
        ranks (lfor i (range 2) (json.loads (.read_text (/ root (+ "rank" (str i) ".json")))))
        rng (np.random.default_rng 936)
        rng.bit_generator.state (get state "rng_state")
        tokens (np.memmap args.tokens :dtype np.uint16 :mode "r")
        seq (get reference "sequence") hashes [])
  (assert (= (.hexdigest (hashlib.sha256 (.read_bytes (/ snapshot "state.json"))))
             (get (get reference "lineage") "source_state_sha256")))
  (for [step (range (* (get reference "steps") (.get reference "accumulation_steps" 1)))]
    (setv starts (.integers rng 0 (- (len tokens) seq 1) :size 2)
          rows (np.stack (lfor s starts (get tokens (slice s (+ s seq 1)))))
          x (.astype (get rows #(... (slice None -1))) np.int32)
          y (.astype (get rows #(... (slice 1 None))) np.int32)
          global-hash (.hexdigest (hashlib.sha256 (+ (.tobytes x) (.tobytes y))))
          local-hashes [])
    (assert (= global-hash (get (get reference "input_hashes") step)))
    (for [rank (range 2)]
      (setv section (slice rank (+ rank 1))
            local-hash (.hexdigest (hashlib.sha256 (+ (.tobytes (get x section)) (.tobytes (get y section))))))
      (assert (= local-hash (get (get (get ranks rank) "input_hashes") step)))
      (.append local-hashes local-hash))
    (assert (!= (get local-hashes 0) (get local-hashes 1)))
    (.append hashes {"global" global-hash "rank_local" local-hashes}))
  (setv result {"pass" True "optimizer_updates" (get reference "steps")
               "global_microsteps" (len hashes) "input_hashes" hashes
               "scope" "CPU-only reconstruction from checkpoint sampler and corpus; no model execution"})
  (.write_text (pathlib.Path args.output) (json.dumps result :indent 2))
  (print (json.dumps {"pass" True "global_microsteps" (len hashes)})))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (for [name ["root" "snapshot" "tokens" "output"]]
    (.add_argument parser (+ "--" name) :required True))
  (verify (.parse_args parser)))
