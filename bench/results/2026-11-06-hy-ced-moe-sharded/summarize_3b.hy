"""Stdlib-only actual3B evidence checks and global-rate arithmetic."""
(import hashlib json math pathlib)
(setv root (. (pathlib.Path __file__) parent)
      source-root (/ root ".." ".." ".." "experiments" "ced_moe"))
(defn read [name] (json.loads (.read_text (/ root (+ name ".json")))))
(defn ensure [value message]
  (when (not value) (raise (ValueError message))))
(setv expected-parameters 3003341824 source (get (read "3b-w2-prefix-rank0") "source_sha256")
      results {} expected-inputs None)
(for [[name sha] (.items source)]
  (ensure (= sha (.hexdigest (hashlib.sha256 (.read_bytes (/ source-root name))))) (+ "executing source " name)))

(for [world [2 3]]
  (setv phases (dfor phase ["prefix" "suffix"] phase
                (lfor rank (range world) (read (+ "3b-w" (str world) "-" phase "-rank" (str rank)))))
        prefix (get phases "prefix") suffix (get phases "suffix")
        reads (lfor rank (range world) (read (+ "3b-w" (str world) "-readback-rank" (str rank))))
        rates {} all-reports (+ prefix suffix))
  (for [report all-reports]
    (ensure (= (get report "status") "complete") "trial incomplete")
    (ensure (= (get report "world_size") world) "wrong physical group")
    (ensure (= (get report "parameters") expected-parameters) "not actual3B")
    (ensure (= (get report "source_sha256") source) "training source changed")
    (ensure (= (get report "global_training_tokens_counted_once") 128) "wrong per-phase tokens")
    (setv cfg (get report "config") opts (get report "options") guard (get report "memory_enforcer"))
    (for [[key value] (.items {"name" "3b" "width" 1024 "layers" 24 "experts" 64
                              "ff" 608 "heads" 16 "head_dim" 64 "vocab" 32768 "top_k" 4})]
      (ensure (= (get cfg key) value) (+ "model configuration " key)))
    (for [[key value] (.items {"sequence" 64 "batch" 1 "optimizer" "sgd" "dtype" "bfloat16"
                              "lr" 0.001 "weight_decay" 0.01 "clip" 1.0 "requested_gib" 10})]
      (ensure (= (get opts key) value) (+ "optimizer/run configuration " key)))
    (ensure (and (get guard "readback_verified") (get guard "active_fatal") (get guard "inactive_fatal")
                  (= (get guard "requested_bytes") (* 10 (** 1024 3)))
                  (= (get guard "kernel_limit_bytes") (* 9 (** 1024 3)))
                  (< (get (get report "process_memory") "lifetime_peak_phys_footprint_bytes")
                     (get guard "kernel_limit_bytes"))) "memory evidence")
    (setv partition (get report "partition") keys (sum (lfor part partition (get part "keys")) []))
    (ensure (= (len (set keys)) (len keys) 304) "missing/duplicate global parameter ownership")
    (ensure (= (sum (lfor part partition (get part "parameters"))) expected-parameters) "partition count")
    (for [[rank part] (enumerate partition)]
      (for [key (get part "keys")]
        (ensure (= rank (if (.startswith key "blocks.")
                            (// (* (int (get (.split key ".") 1)) world) 24) 0)) "wrong layer/tied owner")))
    (for [row (get report "trace")]
      (ensure (and (math.isfinite (get row "objective")) (math.isfinite (get row "global_gradient_norm"))) "nonfinite update")))
  (for [phase ["prefix" "suffix"]]
    (setv reports (get phases phase) first (get reports 0)
          supervisors (lfor rank (range world) (read (+ "3b-w" (str world) "-" phase "-rank" (str rank) "-supervisor"))))
    (ensure (= (len (set (lfor r reports (get r "host")))) world) "not distinct physical hosts")
    (for [r reports]
      (for [key ["input_sha256" "initial_validation_ce" "final_validation_ce" "partition"]]
        (ensure (= (get r key) (get first key)) (+ "rank mismatch " key)))
      (ensure (= (get (get r "checkpoint") "global_manifest_sha256")
                 (get (get first "checkpoint") "global_manifest_sha256")) "save generation disagreement"))
    (for [sup supervisors]
      (ensure (and (= (get sup "exit_status") 0) (get sup "child_reaped")
                    (not (get sup "timed_out"))) "supervisor incomplete"))
    (setv (get rates phase) {"phase_seconds" (max (lfor r reports (get r "training_phase_seconds")))
          "supervisor_seconds" (max (lfor sup supervisors (get sup "wall_seconds")))
          "save_seconds" (max (lfor r reports (get (get r "checkpoint") "seconds")))
          "load_seconds" (if (= phase "prefix") 0.0
                           (max (lfor r reports (get (get r "restored") "seconds"))))}))
  (setv p (get prefix 0) f (get suffix 0)
        before (get (get p "checkpoint") "global_manifest_sha256")
        after (get (get f "checkpoint") "global_manifest_sha256")
        phase-seconds (sum (gfor r (.values rates) (get r "phase_seconds")))
        outer-seconds (sum (gfor r (.values rates) (get r "supervisor_seconds")))
        inputs (+ (get p "input_sha256") (get f "input_sha256")))
  (for [[rank report] (enumerate suffix)]
    (ensure (= (get report "start_step") 2) "not actual resumed step2")
    (ensure (= (get report "end_step") 4) "not resumed through step4")
    (ensure (= (get (get report "restored") "manifest_sha256") before) "wrong restored generation")
    (setv checked (get reads rank))
    (ensure (get checked "pass") "full artifact readback failed")
    (ensure (= (get checked "manifest_sha256") [before after]) "readback generations differ")
    (ensure (= (get checked "expected_input_sha256") inputs) "sampler/token stream mismatch")
    (ensure (get checked "sampler_states_exact") "saved sampler states differ")
    (ensure (> (get (get checked "changed_shards_2_to_4") "weights") 0) "no weights changed")
    (ensure (> (get (get checked "changed_shards_2_to_4") "master") 0) "no master changed")
    (setv sup (read (+ "3b-w" (str world) "-readback-rank" (str rank) "-supervisor")))
    (ensure (and (= (get sup "exit_status") 0) (get sup "child_reaped") (not (get sup "timed_out"))) "readback child incomplete"))
  (when expected-inputs (ensure (= expected-inputs inputs) "layouts did not consume same tokens"))
  (setv expected-inputs inputs
        meshes (lfor r all-reports (get r "mesh"))
        payload (sum (gfor r [p f] value (.values (get r "mesh")) (get value "useful_owner_payload_bytes")))
        (get results (str world))
        {"parameters" expected-parameters "world_size" world "global_training_tokens_counted_once" 256
         "global_batch" 1 "sequence" 64 "optimizer_updates" 4 "partition" (get p "partition")
         "phase_timings" rates "total_training_phase_seconds" phase-seconds
         "global_training_phase_tokens_per_second" (/ 256 phase-seconds)
         "total_supervisor_seconds" outer-seconds "global_supervisor_tokens_per_second" (/ 256 outer-seconds)
         "initial_validation_ce" (get p "initial_validation_ce") "final_validation_ce" (get f "final_validation_ce")
         "checkpoint_boundary_ce_error" (abs (- (get p "final_validation_ce") (get f "initial_validation_ce")))
         "training_objectives" (+ (lfor row (get p "trace") (get row "objective"))
                                   (lfor row (get f "trace") (get row "objective")))
         "peak_process_footprint_gib" (max (lfor r all-reports (/ (get (get r "process_memory") "lifetime_peak_phys_footprint_bytes") (** 1024 3))))
         "checkpoint_manifests" [before after]
         "checkpoint_file_bytes_per_global_generation" (sum (lfor r reads (get (get r "file_bytes_per_generation") 0)))
         "tensor_files_per_global_generation" (sum (lfor r reads (get r "owned_tensor_shards_per_generation")))
         "changed_weight_tensor_shards_2_to_4" (sum (lfor r reads (get (get r "changed_shards_2_to_4") "weights")))
         "changed_master_tensor_shards_2_to_4" (sum (lfor r reads (get (get r "changed_shards_2_to_4") "master")))
         "global_owner_boundary_tensor_payload_bytes_including_evaluation" payload
         "per_rank_totals" (lfor rank (range world)
            (do (setv a (get prefix rank) b (get suffix rank))
              {"rank" rank "footprint_peak_gib" (/ (max (get (get a "process_memory") "lifetime_peak_phys_footprint_bytes")
                                                       (get (get b "process_memory") "lifetime_peak_phys_footprint_bytes")) (** 1024 3))
               "interface_tx_bytes" (+ (get (get a "interface_delta") "tx") (get (get b "interface_delta") "tx"))
               "interface_rx_bytes" (+ (get (get a "interface_delta") "rx") (get (get b "interface_delta") "rx"))
               "ring_tx_estimated_bytes" (sum (gfor r [a b] v (.values (get r "mesh")) (get v "ring_tx_estimated_bytes_per_rank")))
               "process_disk_read_bytes" (+ (get (get a "process_io") "read_bytes") (get (get b "process_io") "read_bytes"))
               "process_disk_write_bytes" (+ (get (get a "process_io") "write_bytes") (get (get b "process_io") "write_bytes"))
               "local_compute_seconds" (sum (gfor r [a b] row (get r "trace") (get row "local_compute_seconds")))}))
         "wire_bytes" None "scope" "serial model parallel, same-layout global restart; short observation, no convergence/bitwise BF16/scaling qualification"}))
(setv summary {"executing_source_commit" "c6fd4fa0" "layouts" results "input_sha256" expected-inputs
                "source_sha256" source "rate_scope" "256 globaltokens once / sum of maximum rank durations for prefix+suffix; reporting gap and separate readback excluded"
                "network_scope" "all init/eval/train/checkpoint collective traffic; logical boundary payload counted globally once; NIC includes background/control"
                "timing_scope" "barrier_wait includes peer compute/IO waits; optimizer+finish_wait includes its barrier, so timings must not be added twice"
                "developer_mac_model_execution" False})
(.write_text (/ root "3b-summary.json") (+ (json.dumps summary :indent 2) "\n"))
(print (json.dumps (dfor [world row] (.items results) world
   {"phase_tps" (get row "global_training_phase_tokens_per_second")
    "all_in_tps" (get row "global_supervisor_tokens_per_second")
    "peak_gib" (get row "peak_process_footprint_gib")
    "initial_ce" (get row "initial_validation_ce") "final_ce" (get row "final_validation_ce")
    "checkpoint_boundary_ce_error" (get row "checkpoint_boundary_ce_error")
    "boundary_payload_bytes" (get row "global_owner_boundary_tensor_payload_bytes_including_evaluation")
    "per_rank_totals" (get row "per_rank_totals")} ) :indent 2))
