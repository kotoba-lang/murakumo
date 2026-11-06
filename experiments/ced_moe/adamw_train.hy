"""Owner-state AdamW study: fixed evaluations, m/v restart and bounded fleet runs."""
(import argparse fcntl hashlib json math pathlib socket time)
(import numpy :as np)
(import mlx.core :as mx)
(import model :as m)
(import train)
(import sharded_model :as s)
(import adamw_pipeline :as pipeline)
(import adamw_checkpoint :as ckpt)
(import sharded_mesh [Mesh])
(import optimizer_shards [OptimizerStore update])
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])
(import comm_probe [interface-bytes])

(defn evaluate [p validation cfg mesh seq batches seed]
  (setv rng (np.random.default_rng seed) values [])
  (for [_ (range batches)]
    (setv [x y] (train.sample-batch validation 1 seq rng)
          [value _ _ _] (pipeline.forward p x y cfg mesh False False))
    (.append values value))
  {"ce" (/ (sum values) batches) "per_batch_ce" values "seed" seed "tokens" (* batches seq)})

(defn run [args]
  (setv started (time.monotonic)
        guard (require-hard-memory-enforcer (* args.memory_limit_gib (** 1024 3))))
  (when (or (!= args.world_size 3) (not (<= 1 args.steps 32))
            (not (<= 8 args.seq 64))
            (and (= args.preset "3b") (!= args.memory_limit_gib 10)))
    (raise (ValueError "admit3 physical ranks,1..32 updates,seq8..64,3B requires10GiB envelope")))
  (when (or (not (<= 0 args.warmup_steps 32)) (not (<= 1 args.eval_batches 16))
            (not (<= 1 args.eval_every 32)) (not (< 0 args.lr 0.01)))
    (raise (ValueError "invalid preregistered optimization/evaluation bounds")))
  (setv cfg (m.config args.preset) corpus (pathlib.Path args.data)
        manifest (json.loads (.read_text (/ corpus "manifest.json")))
        (get cfg "vocab") (get manifest "vocab_size")
        (get cfg "activation_checkpoint") True (get cfg "expert_token_chunk") 64
        provenance (ckpt.base.digest (/ corpus "manifest.json"))
        dtype (if (= args.preset "tiny") mx.float32 mx.bfloat16)
        options {"world_size" args.world_size "layout" "contiguous-layers-tied-head-rank0/v1"
                 "sequence" args.seq "batch" 1 "seed" 936 "optimizer" args.optimizer
                 "lr" args.lr "weight_decay" 0.01 "clip" 1.0
                 "beta1" 0.9 "beta2" 0.95 "eps" 1e-8 "bias_correction" True
                 "warmup_steps" args.warmup_steps "schedule" "linear-warmup-then-constant"
                 "eval_batches" args.eval_batches "eval_every" args.eval_every "eval_seed" 936
                 "confirmation" args.confirm "confirmation_batches" 16 "confirmation_seed" 2027
                 "dtype" (if (= args.preset "tiny") "float32" "bfloat16")
                 "state_offload" "owner-local-nvme-float32" "requested_gib" args.memory_limit_gib})
  (when (= args.preset "tiny")
    (setv (get cfg "window") 4 (get cfg "expert_token_chunk") 4))
  (for [split (if args.confirm ["train" "validation" "test"] ["train" "validation"])]
    (ckpt.base.ensure (= (ckpt.base.digest (/ corpus (+ split ".bin")))
                        (get (get (get manifest "splits") split) "token_file_sha256")) "training corpus changed"))
  (setv mesh (Mesh args.world_size)
        cohort (mesh.gather {"host" (socket.gethostname) "config" cfg "options" options
                             "sources" (ckpt.sources) "runtime" (ckpt.base.runtime-fingerprint)
                             "corpus" provenance "steps" args.steps "resume" (bool args.resume)
                             "save_final" (not args.no_save)}))
  (ckpt.base.ensure (= (len (set (lfor c cohort (get c "host")))) mesh.world) "distinct physical hosts required")
  (setv reference (dict (get cohort 0))) (.pop reference "host")
  (for [entry cohort]
    (setv candidate (dict entry)) (.pop candidate "host")
    (ckpt.base.ensure (= candidate reference) "rank source/settings/data cohort mismatch"))
  (setv output (pathlib.Path args.output))
  (.mkdir output :exist_ok False :parents True)
  (setv lock (open (/ output "rank.lock") "w"))
  (fcntl.flock (.fileno lock) (| fcntl.LOCK_EX fcntl.LOCK_NB))
  (mx.set-cache-limit 0)
  (mx.set-memory-limit (- (get guard "kernel_limit_bytes") (* 256 1024 1024)))
  (setv rng (np.random.default_rng 936) start-step 0 restored None confirmation-initial None
        io-start (counters) nic-start (interface-bytes "en0"))
  (if args.resume
    (do
      (setv [p store common restored] (ckpt.load args.resume cfg options provenance mesh output)
            start-step (get common "step") rng.bit_generator.state (get common "sampler_rng_state")
            confirmation-initial (.get common "confirmation_initial")))
    (do
      (setv p (s.initialize cfg mesh.rank mesh.world 936 dtype) store (OptimizerStore output args.optimizer))
      (store.initialize p)))
  (setv partition (mesh.gather {"rank" mesh.rank "keys" (list p)
                      "parameters" (sum (gfor value (.values p) value.size))
                      "weight_bytes" (sum (gfor value (.values p) value.nbytes))
                      "optimizer_state_bytes" (sum (gfor value (.values p) (* 4 (len store.kinds) value.size)))
                      "optimizer_state_kinds" store.kinds})
        all-keys (sum (lfor part partition (get part "keys")) [])
        total-parameters (get (m.parameter-report cfg) "total_parameters"))
  (ckpt.base.ensure (= (set all-keys) (set (m.shapes cfg))) "partition missing global parameters")
  (ckpt.base.ensure (= (len all-keys) (len (set all-keys))) "duplicated parameter ownership")
  (ckpt.base.ensure (= (sum (lfor part partition (get part "parameters"))) total-parameters) "partition parameter count mismatch")
  (setv training (np.memmap (/ corpus "train.bin") :dtype np.uint16 :mode "r")
        validation (np.memmap (/ corpus "validation.bin") :dtype np.uint16 :mode "r")
        initial-metrics (evaluate p validation cfg mesh args.seq args.eval_batches 936)
        rows [] inputs [] evaluations [{"step" start-step "metrics" initial-metrics}]
        test (when args.confirm (np.memmap (/ corpus "test.bin") :dtype np.uint16 :mode "r")))
  (when (and args.confirm (= start-step 0))
    (setv confirmation-initial (evaluate p test cfg mesh args.seq 16 2027)))
  (mesh.barrier)
  (setv phase-start (time.monotonic))
  (for [offset (range 1 (+ args.steps 1))]
    (setv tick (time.monotonic) step (+ start-step offset)
          [x y] (train.sample-batch training 1 args.seq rng)
          input-sha (.hexdigest (hashlib.sha256 (+ (.tobytes (np.array x)) (.tobytes (np.array y)))))
          [value gradients compute diagnostics] (pipeline.forward p x y cfg mesh True)
          norm (pipeline.global-norm gradients mesh)
          update-start (time.monotonic))
    (setv lr (update p gradients store options step norm))
    (del gradients)
    (mx.clear-cache)
    (mesh.barrier)
    (.append inputs input-sha)
    (setv row {"step" step "cumulative_global_tokens" (* step args.seq)
               "objective" value "ce_and_aux" diagnostics "learning_rate" lr
               "global_gradient_norm" norm "local_compute_seconds" compute
               "optimizer_and_finish_wait_seconds" (- (time.monotonic) update-start)
               "step_seconds" (- (time.monotonic) tick) "memory" (counters)
               "mlx_peak_bytes" (mx.get-peak-memory)})
    (ckpt.base.ensure (math.isfinite value) "non-finite training loss")
    (.append rows row)
    (print (json.dumps row) :flush True)
    (when (= (% step args.eval_every) 0)
      (setv observed (evaluate p validation cfg mesh args.seq args.eval_batches 936))
      (.append evaluations {"step" step "metrics" observed})
      (print (json.dumps {"evaluation_step" step "metrics" observed}) :flush True)))
  (setv phase-seconds (- (time.monotonic) phase-start)
        final-step (+ start-step args.steps)
        final-metrics (if (= (get (get evaluations -1) "step") final-step)
                         (get (get evaluations -1) "metrics")
                         (evaluate p validation cfg mesh args.seq args.eval_batches 936))
        confirmation-final (when (and args.confirm (= final-step 64))
                              (evaluate p test cfg mesh args.seq 16 2027))
        common {"config" cfg "options" options "step" (+ start-step args.steps)
                "tokens_seen" (* (+ start-step args.steps) args.seq)
                "sampler_rng_state" rng.bit_generator.state "data_manifest_sha256" provenance
                "source_sha256" (ckpt.sources) "runtime_versions" (ckpt.base.runtime-fingerprint)
                "confirmation_initial" confirmation-initial}
        checkpoint (when (not args.no_save)
                     (ckpt.save (/ output (+ "checkpoint-" (str final-step))) p store common mesh))
        master-io {"read_bytes" store.bytes_read "write_bytes" store.bytes_written
                   "restore_file_bytes" store.restore_file_bytes})
  (store.close)
  (setv memory (counters) nic-end (interface-bytes "en0")
        result {"schema" "ced-moe-adamw-study/v1" "status" "complete"
                "rank" mesh.rank "world_size" mesh.world "host" (socket.gethostname)
                "config" cfg "options" options "parameters" total-parameters "partition" partition
                "start_step" start-step "end_step" (+ start-step args.steps)
                "global_training_tokens_counted_once" (* args.steps args.seq)
                "training_phase_seconds" phase-seconds "training_global_tokens_per_second" (/ (* args.steps args.seq) phase-seconds)
                "process_seconds_including_init_evaluation_checkpoint" (- (time.monotonic) started)
                "initial_validation_ce" (get initial-metrics "ce") "final_validation_ce" (get final-metrics "ce")
                "validation_tokens" (* args.eval_batches args.seq) "evaluations" evaluations
                "confirmation_initial" confirmation-initial "confirmation_final" confirmation-final
                "trace" rows "input_sha256" inputs
                "restored" restored "checkpoint" checkpoint "mesh" mesh.stats
                "source_sha256" (ckpt.sources) "data_manifest_sha256" provenance
                "logical_optimizer_io" master-io "process_memory" memory "mlx_peak_bytes" (mx.get-peak-memory)
                "process_io" {"read_bytes" (- (get memory "disk_read_bytes") (get io-start "disk_read_bytes"))
                              "write_bytes" (- (get memory "disk_write_bytes") (get io-start "disk_write_bytes"))}
                "interface_delta" {"rx" (- (get nic-end "rx") (get nic-start "rx"))
                                   "tx" (- (get nic-end "tx") (get nic-start "tx"))}
                "wire_bytes" None "network_scope" "whole en0; background/control included"
                "memory_enforcer" guard "scope" "serial owner-state optimizer study; no pipeline overlap, EP or layout-changing resume"})
  (.write_text (/ output "result.json") (json.dumps result :indent 2))
  (print (json.dumps {"complete" True "rank" mesh.rank "step" (+ start-step args.steps)
                     "final_validation_ce" (get final-metrics "ce")
                     "checkpoint" (when checkpoint (get checkpoint "global_manifest_sha256"))}) :flush True))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--preset" :choices ["tiny" "3b"] :required True)
  (.add_argument parser "--world-size" :type int :required True)
  (.add_argument parser "--data" :required True)
  (.add_argument parser "--output" :required True)
  (.add_argument parser "--memory-limit-gib" :type int :required True)
  (.add_argument parser "--steps" :type int :default 2)
  (.add_argument parser "--seq" :type int :default 64)
  (.add_argument parser "--resume")
  (.add_argument parser "--optimizer" :choices ["sgd" "adamw"] :default "adamw")
  (.add_argument parser "--lr" :type float :default 1e-4)
  (.add_argument parser "--warmup-steps" :type int :default 4)
  (.add_argument parser "--eval-batches" :type int :default 8)
  (.add_argument parser "--eval-every" :type int :default 8)
  (.add_argument parser "--confirm" :action "store_true")
  (.add_argument parser "--no-save" :action "store_true")
  (run (.parse_args parser)))
