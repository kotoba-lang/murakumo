"""Bounded, checkpointable random-init pilot; single-host or replicated DDP.

DDP is explicitly replicated. It does not pool model memory and is not PP/EP.
All clocks synchronize MLX so lazy graph construction is not timed as compute.
"""
(import argparse contextlib fcntl hashlib json math os pathlib platform subprocess time)
(import numpy :as np)
(import mlx.core :as mx)
(import model :as m)
(import data [digest])
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn sample-batch [tokens batch seq rng]
  (setv starts (.integers rng 0 (- (len tokens) seq 1) :size batch)
        rows (np.stack (lfor s starts (get tokens (slice s (+ s seq 1))))))
  #((mx.array (get rows #(... (slice None -1))) :dtype mx.int32)
    (mx.array (get rows #(... (slice 1 None))) :dtype mx.int32)))

(defn ce-value [p tokens cfg seq batches seed]
  (setv rng (np.random.default_rng seed) values [])
  (for [_ (range batches)]
    (setv [x y] (sample-batch tokens 1 seq rng)
          [logits _ _] (m.forward p x cfg)
          flat (.reshape (.astype logits mx.float32) [-1 (get cfg "vocab")])
          target (.reshape y [-1])
          ce (mx.mean (- (mx.logsumexp flat :axis -1)
                       (mx.squeeze (mx.take-along-axis flat (mx.expand_dims target -1)
                                                       :axis -1) :axis -1))))
    (.append values (float ce)))
  (/ (sum values) (len values)))

(defn save-checkpoint [root p state meta]
  "Publish a directory only after all component files are written and hashed."
  (setv root (pathlib.Path root) temporary (.with_name root (+ root.name ".partial")))
  (when (or (.exists root) (.exists temporary))
    (raise (FileExistsError (+ "checkpoint already exists: " (str root)))))
  (.mkdir temporary :parents True)
  (mx.save-safetensors (str (/ temporary "weights.safetensors")) p)
  (for [kind ["master" "m" "v"] :if (in kind state)]
    (mx.save-safetensors (str (/ temporary (+ kind ".safetensors"))) (get state kind)))
  (setv (get meta "files") (dfor f (.glob temporary "*.safetensors") f.name (digest f)))
  (.write_text (/ temporary "state.json") (json.dumps meta :indent 2) :encoding "utf-8")
  (.rename temporary root))

(defn load-checkpoint [root cfg provenance args]
  (setv root (pathlib.Path root) meta (json.loads (.read_text (/ root "state.json"))))
  (assert (= (get meta "config") cfg) "resume model config mismatch")
  (assert (= (get meta "data_manifest_sha256") provenance) "resume corpus mismatch")
  (assert (= (get meta "optimizer") args.optimizer) "resume optimizer mismatch")
  (assert (= (get meta "training_options") (training-options args)) "resume training options mismatch")
  (setv allowed (set ["weights.safetensors" "master.safetensors" "m.safetensors" "v.safetensors"]))
  (for [[name expected] (.items (get meta "files"))]
    (assert (in name allowed) "unexpected checkpoint component")
    (assert (= (digest (/ root name)) expected) (+ "checkpoint digest mismatch: " name)))
  (setv p (dict (mx.load (str (/ root "weights.safetensors")))))
  (assert (= (set p) (set (m.shapes cfg))) "checkpoint parameter keys mismatch")
  (for [[key shape] (.items (m.shapes cfg))]
    (assert (= (list (. (get p key) shape)) shape) (+ "checkpoint shape mismatch: " key)))
  (setv state (dfor kind ["master" "m" "v"]
                   :if (.exists (/ root (+ kind ".safetensors")))
                   kind (dict (mx.load (str (/ root (+ kind ".safetensors")))))))
  (assert (= (set state) (if (= args.optimizer "adamw") #{"master" "m" "v"} #{"master"}))
          "checkpoint optimizer component mismatch")
  (for [values (.values state)]
    (assert (= (set values) (set p)) "optimizer parameter keys mismatch")
    (for [[key value] (.items values)]
      (assert (= value.shape (. (get p key) shape)) "optimizer shape mismatch")
      (assert (= value.dtype mx.float32) "optimizer state must be FP32")))
  #(p state meta))

(defn training-options [args]
  (dfor key ["optimizer" "lr" "weight_decay" "clip" "seq" "batch" "seed" "world_size" "dtype"]
        key (getattr args key)))

(defn step-update [p grads state step args]
  ;; Per-parameter materialization bounds old/new optimizer state overlap.
  (setv grad-norm (mx.sqrt (sum (gfor g (.values grads)
                                  (mx.sum (mx.square (.astype g mx.float32)))))))
  (mx.eval grad-norm)
  (when (not (math.isfinite (float grad-norm)))
    (raise (FloatingPointError "non-finite gradient norm")))
  (setv scale (min 1.0 (/ args.clip (+ (float grad-norm) 1e-8))))
  (for [key (list p)]
    (setv value (get p key)
          g (* (.astype (get grads key) mx.float32) scale)
          master (get (get state "master") key))
    (if (= args.optimizer "adamw")
      (do
        (setv moment (+ (* 0.9 (get (get state "m") key)) (* 0.1 g))
              variance (+ (* 0.95 (get (get state "v") key)) (* 0.05 (mx.square g)))
              update (/ (/ moment (- 1 (** 0.9 step)))
                        (+ (mx.sqrt (/ variance (- 1 (** 0.95 step)))) 1e-8)))
        (mx.eval moment variance)
        (setv (get (get state "m") key) moment
              (get (get state "v") key) variance))
      (setv update g))
    (setv new-master (- (* master (- 1 (* args.lr args.weight_decay))) (* args.lr update))
          new-param (.astype new-master value.dtype))
    (mx.eval new-master new-param)
    (setv (get (get state "master") key) new-master (get p key) new-param)
    (.pop grads key))
  (float grad-norm))

(defn run [args]
  (setv started (time.monotonic) output (pathlib.Path args.output)
        corpus (pathlib.Path args.data) manifest-path (/ corpus "manifest.json")
        manifest (json.loads (.read_text manifest-path))
        provenance (digest manifest-path)
        cfg (m.config args.preset)
        (get cfg "vocab") (get manifest "vocab_size")
        (get cfg "activation_checkpoint") args.activation_checkpoint
        report (m.parameter-report cfg)
        budget (int (* args.memory_limit_gib (** 1024 3)))
        dtype (if (= args.dtype "float32") mx.float32 mx.bfloat16)
        bytes-per-param (+ (if (= args.optimizer "adamw") 12 4)
                           (if (= args.dtype "float32") 8 4))
        minimum (+ (* bytes-per-param (get report "total_parameters")) (* 256 (** 1024 2))))
  (when (> minimum budget)
    (raise (MemoryError (+ "state-only minimum exceeds explicit memory limit; "
                           "use fewer parameters or a verified sharded learner. "
                           (str minimum) " > " (str budget)))))
  (when (or (<= args.steps 0) (<= args.max_seconds 0) (< args.seq 4)
            (<= args.batch 0) (< args.world_size 1))
    (raise (ValueError "invalid bounded run options")))
  ;; Qualify the one-shot kernel setter on this exact host/source first. Its
  ;; fatal process-footprint threshold is 1GiB below the requested ceiling.
  (setv memory-enforcer (require-hard-memory-enforcer budget))
  (when (> minimum (get memory-enforcer "kernel_limit_bytes"))
    (raise (MemoryError "state-only minimum exceeds the lower kernel fatal threshold")))
  (print (json.dumps {"phase" "before_model_initialization"
                      "memory_enforcer" memory-enforcer "process_memory" (counters)}) :flush True)
  (for [split ["train" "validation"]]
    (assert (= (digest (/ corpus (+ split ".bin")))
               (get (get (get manifest "splits") split) "token_file_sha256"))
            (+ "corpus digest mismatch: " split)))
  (setv world None rank 0)
  (when (> args.world_size 1)
    (setv world (mx.distributed.init :strict True :backend args.backend)
          rank (.rank world))
    (assert (= (.size world) args.world_size) "refuse silent singleton distributed run"))
  (mx.set-memory-limit (- (get memory-enforcer "kernel_limit_bytes") (* 256 (** 1024 2))))
  (mx.set-cache-limit 0)
  (.mkdir output :parents True :exist_ok True)
  (setv lock (open (/ output f"rank-{rank}.lock") "w"))
  (fcntl.flock (.fileno lock) (| fcntl.LOCK_EX fcntl.LOCK_NB))
  (setv train (np.memmap (/ corpus "train.bin") :dtype np.uint16 :mode "r")
        validation (np.memmap (/ corpus "validation.bin") :dtype np.uint16 :mode "r")
        rng (np.random.default_rng (+ args.seed rank))
        step 0 seen 0 comm-logical 0 comm-ring-estimate 0)
  (if args.resume
    (do
      (setv [p state old] (load-checkpoint args.resume cfg provenance args)
            step (get old "step") seen (get old "tokens_seen")
            rng.bit_generator.state (get old "rng_state")))
    (do
      (setv p (m.init-parameters cfg args.seed dtype :materialize True)
            state {"master" {}})
      (for [[k v] (.items p)]
        (setv master (.astype v mx.float32))
        (mx.eval master)
        (setv (get (get state "master") k) master))
      (setv master None)
      (when (= args.optimizer "adamw")
        (for [kind ["m" "v"]]
          (setv (get state kind) (dfor [k v] (.items p) k (mx.zeros v.shape)))))))
  (mx.eval p state)
  (setv initial-step step initial-seen seen
        initial-val (ce-value p validation cfg args.seq args.eval_batches args.seed)
        trace [] grad-fn (mx.value-and-grad m.loss)
        phase-start (time.monotonic))
  (while (and (< (- step initial-step) args.steps)
              (< (- (time.monotonic) started) args.max_seconds))
    (setv [x y] (sample-batch train args.batch args.seq rng)
          t0 (time.monotonic)
          [value grads] (grad-fn p x y cfg))
    (mx.eval value grads)
    (when (not (math.isfinite (float value))) (raise (FloatingPointError "non-finite loss")))
    (setv t1 (time.monotonic) communication-seconds 0.0)
    (when world
      ;; A correct replicated baseline. Dense per-tensor reductions are deliberately
      ;; not mislabeled as sparse expert dispatch, PP, or low-frequency DiLoCo.
      (setv tick (time.monotonic))
      (for [[key g] (.items grads)]
        (+= comm-logical g.nbytes)
        (+= comm-ring-estimate (* g.nbytes (/ (* 2 (- args.world_size 1)) args.world_size)))
        (setv (get grads key) (/ (mx.distributed.all-sum g :group world) args.world_size)))
      (mx.eval grads)
      (setv communication-seconds (- (time.monotonic) tick)))
    (+= step 1)
    (setv gradient-norm (step-update p grads state step args)
          t2 (time.monotonic))
    (+= seen (* args.batch args.seq args.world_size))
    (setv row {"step" step "tokens_seen" seen "loss" (float value)
               "forward_backward_seconds" (- t1 t0)
               "communication_seconds" communication-seconds
               "optimizer_seconds" (- t2 t1 communication-seconds)
               "step_seconds" (- t2 t0) "gradient_norm" gradient-norm
               "logical_gradient_bytes_cumulative" comm-logical
               "ring_tx_bytes_estimate_cumulative" comm-ring-estimate
               "mlx_peak_bytes" (mx.get-peak-memory)
               "process_memory" (counters)})
    (.append trace row)
    (print (json.dumps row :sort_keys True) :flush True)
    ;; Do not retain last step's 2GB dense gradient tree while building the
    ;; next step. Updated entries were consumed above; release remaining refs.
    (del grads value)
    (mx.clear-cache)
    (when (= (% step args.eval_every) 0)
      (setv val (ce-value p validation cfg args.seq args.eval_batches args.seed))
      (print (json.dumps {"step" step "validation_ce" val}) :flush True)))
  (mx.synchronize)
  (when (not trace) (raise (TimeoutError "no optimizer step completed within the run budget")))
  (setv training-seconds (- (time.monotonic) phase-start)
        final-val (ce-value p validation cfg args.seq args.eval_batches args.seed)
        meta {"config" cfg "step" step "tokens_seen" seen
              "optimizer" args.optimizer "lr" args.lr
              "weight_decay" args.weight_decay "rng_state" rng.bit_generator.state
              "training_options" (training-options args)
              "data_manifest_sha256" provenance
              "world_size" args.world_size "rank" rank})
  (when args.checkpoint
    (save-checkpoint (/ output f"checkpoint-{step}-rank-{rank}") p state meta))
  (setv result {"schema" "ced-moe-training-pilot/v1" "config" cfg
                "parameters" report "optimizer" args.optimizer
                "dtype" args.dtype
                "host" (mx.device-info) "platform" (platform.platform)
                "hy_version" "1.1.0" "mlx_version" "0.32.4"
                "world_size" args.world_size "rank" rank
                "data_manifest_sha256" provenance
                "steps" (- step initial-step) "tokens" (- seen initial-seen)
                "initial_validation_ce" initial-val "final_validation_ce" final-val
                "training_phase_wall_seconds" training-seconds
                "wall_seconds_including_setup_eval_checkpoint" (- (time.monotonic) started)
                "training_phase_tokens_per_second" (/ (- seen initial-seen) training-seconds)
                "communication_logical_gradient_bytes" comm-logical
                "communication_ring_tx_estimated_bytes" comm-ring-estimate
                "communication_wire_bytes" None
                "communication_wire_bytes_note" "requires dedicated interface/counter measurement"
                "mlx_peak_bytes" (mx.get-peak-memory)
                "memory_limit_bytes" budget "trace" trace
                "memory_enforcer" memory-enforcer "process_memory" (counters)
                "memory_limit_semantics" "kernel fatal process footprint with1GiB headroom; MLX advisory only"
                "architecture" "reduced CSA2-style CED/SWA/Sparse MoE; see README"
                "distributed_mode" (if world "replicated-ddp" "single-host")})
  (.write_text (/ output f"result-rank-{rank}.json") (json.dumps result :indent 2)
               :encoding "utf-8")
  (print (json.dumps {"result" (str (/ output f"result-rank-{rank}.json"))
                      "validation_ce" [initial-val final-val]}) :flush True)
  result)

(defn arguments []
  (setv parser (argparse.ArgumentParser))
  (for [name ["data" "output"]] (.add_argument parser (+ "--" name) :required True))
  (.add_argument parser "--preset" :choices ["tiny" "small" "1b" "3b"] :default "tiny")
  (.add_argument parser "--optimizer" :choices ["adamw" "sgd"] :default "adamw")
  (.add_argument parser "--dtype" :choices ["bfloat16" "float32"] :default "bfloat16")
  (.add_argument parser "--backend" :choices ["ring" "mpi"] :default "ring")
  (.add_argument parser "--lr" :type float :default 0.001)
  (.add_argument parser "--weight-decay" :type float :default 0.01)
  (.add_argument parser "--clip" :type float :default 1.0)
  (.add_argument parser "--memory-limit-gib" :type float :default 2.0
                 :help "Requested ceiling<=10GiB; kernel fatal threshold1GiB lower. Refuse without host/source qualification.")
  (.add_argument parser "--activation-checkpoint" :action argparse.BooleanOptionalAction :default True)
  (.add_argument parser "--max-seconds" :type float :default 300.0)
  (for [[name default] [["steps" 100] ["seq" 128] ["batch" 1] ["seed" 936]
                        ["eval-every" 20] ["eval-batches" 4] ["world-size" 1]]]
    (.add_argument parser (+ "--" name) :type int :default default))
  (.add_argument parser "--resume")
  (.add_argument parser "--checkpoint" :action "store_true")
  (.parse_args parser))

(when (= __name__ "__main__") (run (arguments)))
