"""Inventory empirical calibration; refuse unsupported 30B wall-clock claims."""
(import argparse json pathlib statistics)
(import model :as m)

(defn inventory [files]
  (setv runs (lfor path files (json.loads (.read_text (pathlib.Path path))))
        target (m.parameter-report (m.config "30b-target"))
        sizes (set (gfor r runs (get (get r "config") "name")))
        missing [])
  (for [size ["1b" "3b"] :if (not-in size sizes)]
    (.append missing (+ size " actual from-scratch training measurement")))
  (when (not (any (gfor r runs (> (get r "world_size") 1))))
    (.append missing "multi-host synchronized training measurement"))
  (when (not (any (gfor r runs (not (= (get r "communication_wire_bytes") None)))))
    (.append missing "isolated physical wire byte counters / measured bandwidth"))
  ;; Current training harness has only replicated DDP. A 30B sharded learner
  ;; needs measured PP/EP boundaries and shared-source backward traffic.
  (.append missing "30B learner layout and capacity-qualified PP/EP measurements")
  (.append missing "holdout-size/node-count prediction error and sustained availability")
  {"status" "insufficient_calibration" "duration_days" None
   "target_parameters" target "missing" missing
   "observed" (lfor r runs
     {"model" (get (get r "config") "name")
      "device" (get (get r "host") "device_name")
      "world_size" (get r "world_size") "optimizer" (get r "optimizer")
      "training_phase_tokens_per_second" (get r "training_phase_tokens_per_second")
      "forward_backward_step_median_seconds"
        (statistics.median (lfor t (get r "trace") (get t "forward_backward_seconds")))
      "optimizer_step_median_seconds"
        (statistics.median (lfor t (get r "trace") (get t "optimizer_seconds")))
      "wire_bytes" (get r "communication_wire_bytes")})
   "model_to_fit"
     "step = compute critical path + non-overlapped PP/EP/shared-source/sync communication + optimizer + data/checkpoint/failure overhead"
   "duration_formula"
     "target unique non-padding tokens / validated target training tok/s / sustained availability; add costs excluded from that tok/s exactly once"
   "prohibited_shortcuts"
     ["inference tok/s as training tok/s" "total parameter ratio as compute ratio"
      "10x nodes implies 10x throughput" "CSA2 inference KV bytes as training activation bytes"
      "2T tokens assumed necessary without an explicit data budget"]})

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "measurements" :nargs "+")
  (.add_argument parser "--output" :required True)
  (setv args (.parse_args parser) result (inventory args.measurements))
  (.write_text (pathlib.Path args.output) (json.dumps result :indent 2) :encoding "utf-8")
  (print (json.dumps result :sort_keys True)))
