"""CPU-only refusal controls. Never initialize model arrays or a collective."""
(import argparse json pathlib sys types)
(import train memory_policy)

(defn never-start [#* args #** kwargs]
  (raise (AssertionError "refusal happened too late: allocation/collective/evaluation reached")))

(defn check [corpus output]
  (defn setter-fails [#* args #** kwargs]
    (raise (OSError "simulated unavailable kernel setter")))
  (setv memory_policy.install-kernel-limit setter-fails)
  (setv train.m.init-parameters never-start
        train.mx.eval never-start
        train.mx.set-memory-limit never-start
        train.mx.distributed.init never-start
        results [])
  (for [[preset seq world] [["tiny" 16 1] ["1b" 256 1] ["1b" 512 2]]]
    (setv target (/ (pathlib.Path output) (+ preset "-" (str seq)))
          args (types.SimpleNamespace
                 :data corpus :output (str target) :preset preset :optimizer "sgd"
                 :memory_limit_gib 10.0 :dtype "bfloat16"
                 :steps 4 :max_seconds 110 :seq seq :batch 1 :world_size world
                 :activation_checkpoint True))
    (assert (not (.exists target)) "control output must be fresh")
    (setv refused False)
    (try
      (train.run args)
      (except [error RuntimeError]
        (assert (in "[hard-memory-enforcer-unqualified]" (str error)) (str error))
        (setv refused True)))
    (assert refused "training was not refused")
    (assert (not (.exists target)) "refused run created an output directory")
    (.append results {"preset" preset "sequence" seq "world_size" world
                       "refused_before_allocation_collective_and_output" True}))
  (print (json.dumps {"ok" True "gpu_training_started" False "controls" results}
                     :sort_keys True)))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--data" :required True)
  (.add_argument parser "--output" :required True)
  (setv args (.parse_args parser))
  (check args.data args.output))
