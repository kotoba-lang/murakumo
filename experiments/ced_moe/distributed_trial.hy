"""Two physical ranks vs sequential local-objective reference, no global resume.

The single-rank checkpoint is explicitly imported as replica initialization.
Its immutable source/options validation remains unchanged. No global checkpoint
or model-state sharding is claimed. All actual model work belongs on fleet.
"""
(import argparse fcntl hashlib json math pathlib socket time types)
(import numpy :as np)
(import mlx.core :as mx)
(import model :as m)
(import train)
(import stream_checkpoint :as ckpt)
(import ddp_ops [average-gradients])
(import accumulation_ops [accumulate mean-gradients])
(import memory_policy [require-hard-memory-enforcer])
(import nvme_master [MasterStore])
(import process_memory [counters])
(import comm_probe [interface-bytes])

(defn gather-digests [values group]
  (setv data (.join b"" (lfor value values (bytes.fromhex value)))
        gathered (mx.distributed.all-gather (mx.array (list data) :dtype mx.uint8) :group group))
  (mx.eval gathered)
  (setv rows (.tolist (.reshape gathered [(.size group) (len data)])))
  (lfor row rows (lfor start (range 0 (len data) 32) (.hex (bytes (get row (slice start (+ start 32))))))))

(defn barrier [group]
  (when group
    (mx.eval (mx.distributed.all-sum (mx.array [1.0]) :group group))))

(defn source-digest []
  (setv root (getattr (pathlib.Path __file__) "parent")
        files (ckpt.source-fingerprint))
  (for [name ["distributed_trial.hy" "ddp_ops.hy" "accumulation_ops.hy"]]
    (setv (get files name) (ckpt.digest (/ root name))))
  files)

(defn run [args]
  (setv start (time.monotonic) output (pathlib.Path args.output)
        corpus (pathlib.Path args.data) manifest (json.loads (.read_text (/ corpus "manifest.json")))
        provenance (ckpt.digest (/ corpus "manifest.json"))
        guard (require-hard-memory-enforcer (* args.memory_limit_gib (** 1024 3)))
        io-start (counters) network-start (interface-bytes args.interface))
  (when (or (<= args.steps 0) (< args.seq 4) (> args.steps 4))
    (raise (ValueError "trial admits1..4 updates and sequence>=4")))
  (when (or (not (in args.accumulation_steps [1 2 4]))
            (and (= args.mode "reference") (!= args.sync_frequency "update"))
            (> (* args.steps 2 args.accumulation_steps args.seq) 2048))
    (raise (ValueError "admit accumulation1/2/4, reference update-only and <=2048 global tokens")))
  (when (and (= args.preset "1b") (or (not args.initialize_from_single) (>= args.fail_rank 0)))
    (raise (ValueError "1B requires explicit checkpoint initialization and no fault injection")))
  (for [split ["train" "validation"]]
    (ckpt.ensure (= (ckpt.digest (/ corpus (+ split ".bin")))
                    (get (get (get manifest "splits") split) "token_file_sha256")) "corpus digest mismatch"))
  (setv group (when (= args.mode "ddp") (mx.distributed.init :strict True :backend "ring"))
        rank (if group (.rank group) 0) micros 2)
  (when group (ckpt.ensure (= (.size group) 2) "two physical ranks required; singleton refused"))
  (.mkdir output :parents True :exist_ok False)
  (setv lock (open (/ output "trial.lock") "w"))
  (fcntl.flock (.fileno lock) (| fcntl.LOCK_EX fcntl.LOCK_NB))
  (mx.set-cache-limit 0)
  (mx.set-memory-limit (- (get guard "kernel_limit_bytes") (* 256 1024 1024)))
  (setv sources (source-digest)
        source-hash (.hexdigest (hashlib.sha256 (.encode (json.dumps sources :sort_keys True))))
        host-hash (.hexdigest (hashlib.sha256 (.encode (socket.gethostname))))
        spec-hash (.hexdigest (hashlib.sha256 (.encode (json.dumps
          {"preset" args.preset "steps" args.steps "seq" args.seq
           "memory_limit_gib" args.memory_limit_gib "fail_rank" args.fail_rank
           "accumulation_steps" args.accumulation_steps "sync_frequency" args.sync_frequency} :sort_keys True)))))
  (when group
    (setv peers (gather-digests [source-hash host-hash provenance spec-hash] group))
    (ckpt.ensure (= (get (get peers 0) 0) (get (get peers 1) 0)) "trial source differs")
    (ckpt.ensure (!= (get (get peers 0) 1) (get (get peers 1) 1)) "ranks must be distinct hosts")
    (ckpt.ensure (= (get (get peers 0) 2) (get (get peers 1) 2)) "rank corpora differ")
    (ckpt.ensure (= (get (get peers 0) 3) (get (get peers 1) 3)) "rank trial settings differ"))
  (setv rng (np.random.default_rng 936) source-step 0 lineage None)
  (if args.initialize_from_single
    (do
      (setv root (pathlib.Path args.initialize_from_single)
            original (json.loads (.read_text (/ root "state.json")))
            cfg (get original "config") options (get original "training_options")
            evaluation (get original "evaluation_options"))
      (ckpt.ensure (and (= (get cfg "name") "1b") (= (get options "optimizer") "sgd")
                       (= (get options "dtype") "bfloat16") (= (get options "master_offload") "nvme")
                       (= (get original "step") 64)) "admitted source must be the SGD1B step64 snapshot")
      ;; Validate against the original single-rank experiment, then explicitly
      ;; initialize a new global-batch/context trial. Never call this resume.
      (setv [p state imported] (ckpt.load root cfg provenance options evaluation output)
            rng.bit_generator.state (get imported "rng_state")
            source-step (get imported "step")
            lineage {"mode" "explicit-replica-initialization-not-resume"
                     "source_step" source-step "source_state_sha256" (ckpt.digest (/ root "state.json"))})
      (setv update-args (types.SimpleNamespace :master_offload "nvme" :optimizer "sgd"
                :lr (get options "lr") :weight_decay (get options "weight_decay") :clip (get options "clip"))))
    (do
      (setv cfg (m.config "tiny") (get cfg "vocab") (get manifest "vocab_size")
            (get cfg "window") 4 (get cfg "activation_checkpoint") True
            (get cfg "expert_token_chunk") 4
            p (m.init-parameters cfg 936 mx.float32 :materialize True)
            state {"master" (MasterStore output)}
            update-args (types.SimpleNamespace :master_offload "nvme" :optimizer "sgd"
                          :lr 0.001 :weight_decay 0.01 :clip 1.0))
      (for [[key value] (.items p)] (.write (get state "master") key (.astype value mx.float32)))))
  (when (and group lineage)
    (setv imports (gather-digests [(get lineage "source_state_sha256")] group))
    (ckpt.ensure (= (get imports 0) (get imports 1)) "replicas initialized from different snapshots"))
  (setv tokens (np.memmap (/ corpus "train.bin") :dtype np.uint16 :mode "r")
        validation (np.memmap (/ corpus "validation.bin") :dtype np.uint16 :mode "r")
        initial-ce (train.ce-value p validation cfg args.seq 2 936)
        gradient-fn (mx.value-and-grad m.loss) rows [] input-hashes [] logical 0 tx-estimate 0)
  (barrier group)
  (when (= rank args.fail_rank) (raise (RuntimeError "deliberate owned-rank failure")))
  (setv phase-start (time.monotonic))
  (for [step (range 1 (+ args.steps 1))]
    (barrier group)
    (setv tick (time.monotonic) gradients None values [] comm-seconds 0.0
          syncs 0 logical-step 0 buckets 0 ring-step 0.0)
    (for [_ (range args.accumulation_steps)]
      (setv [x y] (train.sample-batch tokens 2 args.seq rng)
            local-section (slice rank (+ rank 1))
            local-x (if group (get x local-section) x)
            local-y (if group (get y local-section) y)
            data-hash (.hexdigest (hashlib.sha256 (+ (.tobytes (np.array local-x)) (.tobytes (np.array local-y))))))
      (.append input-hashes data-hash)
      (for [micro (range (if group 1 2))]
        (setv section (slice micro (+ micro 1))
              [loss fresh] (gradient-fn p (get local-x section) (get local-y section) cfg))
        (mx.eval loss fresh)
        (.append values (float loss))
        (when (and group (= args.sync_frequency "micro"))
          (setv communication-start (time.monotonic)
                stats (average-gradients fresh group))
          (+= comm-seconds (- (time.monotonic) communication-start))
          (+= syncs 1) (+= logical-step (get stats "logical_gradient_bytes"))
          (+= buckets (get stats "collectives"))
          (+= ring-step (get stats "ring_tx_estimated_bytes")))
        (setv gradients (accumulate gradients fresh))
        (del fresh)))
    (setv gradients (mean-gradients gradients (len values))
          value (/ (sum values) (len values)))
    (when group
      (setv communication-start (time.monotonic))
      (when (= args.sync_frequency "update")
        (setv stats (average-gradients gradients group))
        (+= syncs 1) (+= logical-step (get stats "logical_gradient_bytes"))
        (+= buckets (get stats "collectives"))
        (+= ring-step (get stats "ring_tx_estimated_bytes")))
      (setv value (float (/ (mx.distributed.all-sum (mx.array value) :group group) 2)))
      (+= comm-seconds (- (time.monotonic) communication-start)))
    (+= logical logical-step) (+= tx-estimate ring-step)
    (setv after-comm (time.monotonic)
          stats (when group {"logical_gradient_bytes" logical-step "collectives" buckets
                             "gradient_synchronizations" syncs "ring_tx_estimated_bytes" ring-step
                             "bucket_limit_bytes" (* 64 1024 1024)}))
    (when (and (= args.preset "tiny") (= step 1))
      (mx.save-safetensors (str (/ output "gradient-step1.safetensors")) gradients))
    (setv norm (train.step-update p gradients state (+ source-step step) update-args))
    (del gradients)
    (mx.clear-cache)
    (barrier group)
    (setv row {"trial_step" step "global_tokens" (* step 2 args.accumulation_steps args.seq)
               "mean_local_objective" value "gradient_norm" norm
               "compute_seconds" (- after-comm tick comm-seconds)
               "gradient_communication_seconds" comm-seconds
               "optimizer_and_finish_sync_seconds" (- (time.monotonic) after-comm)
               "step_seconds" (- (time.monotonic) tick) "collectives" stats
               "process_memory" (counters) "mlx_peak_bytes" (mx.get-peak-memory)})
    (.append rows row)
    (print (json.dumps row) :flush True))
  (setv phase-seconds (- (time.monotonic) phase-start)
        final-ce (train.ce-value p validation cfg args.seq 2 936))
  (mx.save-safetensors (str (/ output "weights.safetensors")) p)
  (when (= args.preset "tiny")
    (mx.save-safetensors (str (/ output "masters.safetensors"))
       (dfor key p key (.read (get state "master") key))))
  (setv weight-sha (ckpt.digest (/ output "weights.safetensors"))
        master-shards (dfor key p key (ckpt.digest (/ (. (get state "master") root) (+ key ".safetensors"))))
        master-sha (.hexdigest (hashlib.sha256 (.encode (json.dumps master-shards :sort_keys True))))
        replicas None)
  (when group
    (setv replicas (gather-digests [weight-sha master-sha] group))
    (ckpt.ensure (= (get replicas 0) (get replicas 1)) "final replica weight/master hashes differ"))
  (setv offload {"read_bytes" (. (get state "master") bytes_read)
                "write_bytes" (. (get state "master") bytes_written)
                "restore_file_bytes" (. (get state "master") restore_file_bytes)})
  (.close (get state "master"))
  (setv memory (counters) network-end (interface-bytes args.interface)
        result {"schema" "ced-moe-replicated-trial/v2" "status" "complete"
                "mode" args.mode "preset" args.preset "rank" rank "world_size" (if group 2 1)
                "host" (socket.gethostname) "device" (mx.device-info)
                "global_microbatches" (* 2 args.accumulation_steps) "local_batch" 1 "sequence" args.seq "steps" args.steps
                "accumulation_steps" args.accumulation_steps "sync_frequency" args.sync_frequency
                "gradient_accumulation_dtype" (if (= args.preset "tiny") "float32" "bfloat16")
                "objective_semantics" "mean of 2*accumulation_steps rank-local objectives, including local balancing"
                "global_training_tokens" (* args.steps 2 args.accumulation_steps args.seq)
                "local_tokens" (* args.steps args.accumulation_steps (if group 1 2) args.seq)
                "training_phase_seconds" phase-seconds
                "training_global_tokens_per_second" (/ (* args.steps 2 args.accumulation_steps args.seq) phase-seconds)
                "process_seconds_including_init_and_verification" (- (time.monotonic) start)
                "lineage" lineage "source_sha256" sources "data_manifest_sha256" provenance
                "input_hashes" input-hashes "trace" rows
                "initial_validation_ce" initial-ce "final_validation_ce" final-ce
                "logical_gradient_bytes" logical "ring_tx_estimated_bytes" tx-estimate
                "wire_bytes" None "interface" args.interface
                "interface_delta" {"rx" (- (get network-end "rx") (get network-start "rx"))
                                   "tx" (- (get network-end "tx") (get network-start "tx"))}
                "network_counter_scope" "whole interface; background/control traffic included"
                "process_io" {"read" (- (get memory "disk_read_bytes") (get io-start "disk_read_bytes"))
                              "write" (- (get memory "disk_write_bytes") (get io-start "disk_write_bytes"))}
                "logical_master_io" offload "memory_enforcer" guard "process_memory" memory
                "mlx_peak_bytes" (mx.get-peak-memory) "weight_sha256" weight-sha
                "master_sha256" master-sha "replica_hashes" replicas
                "scope" "replicated DDP; no model-state pooling or global checkpoint"})
  (.write_text (/ output "result.json") (json.dumps result :indent 2))
  (print (json.dumps {"complete" True "rank" rank "weights" weight-sha "masters" master-sha}) :flush True))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--mode" :choices ["reference" "ddp"] :required True)
  (.add_argument parser "--preset" :choices ["tiny" "1b"] :required True)
  (.add_argument parser "--data" :required True)
  (.add_argument parser "--output" :required True)
  (.add_argument parser "--initialize-from-single")
  (.add_argument parser "--memory-limit-gib" :type int :required True)
  (.add_argument parser "--steps" :type int :default 2)
  (.add_argument parser "--seq" :type int :default 64)
  (.add_argument parser "--accumulation-steps" :type int :default 1)
  (.add_argument parser "--sync-frequency" :choices ["micro" "update"] :default "update")
  (.add_argument parser "--interface" :default "en0")
  (.add_argument parser "--fail-rank" :type int :default -1)
  (run (.parse_args parser)))
