"""Stdlib-only evidence arithmetic. No model/runtime import or execution."""
(import hashlib json pathlib)
(setv root (. (pathlib.Path __file__) parent))

(defn read [name] (json.loads (.read_text (/ root (+ name ".json")))))
(defn check [condition message]
  (when (not condition) (raise (ValueError message))))
(defn total [rows field] (sum (lfor row rows (get row field))))

(setv reference (read "oneb-reference")
      modes (dfor mode ["micro" "update"] mode
              (lfor rank (range 2) (read (+ "oneb-" mode "-rank" (str rank)))))
      sources (get reference "source_sha256")
      source-root (/ root ".." ".." ".." "experiments" "ced_moe"))
(for [[name expected] (.items sources)]
  (check (= expected (.hexdigest (hashlib.sha256 (.read_bytes (/ source-root name))))) (+ "source " name)))
(for [report (+ [reference] (get modes "micro") (get modes "update"))]
  (for [[key expected] (.items {"status" "complete" "preset" "1b" "steps" 2
                               "sequence" 64 "accumulation_steps" 4
                               "global_training_tokens" 1024 "global_microbatches" 8})]
    (check (= (get report key) expected) (+ "condition " key)))
  (check (= (get report "source_sha256") sources) "source cohort differs")
  (check (= (get report "lineage") (get reference "lineage")) "initial state differs")
  (check (= (get report "data_manifest_sha256") (get reference "data_manifest_sha256")) "corpus differs")
  (check (= (len (get report "input_hashes")) 8) "microbatch count")
  (setv guard (get report "memory_enforcer"))
  (check (and (get guard "readback_verified") (get guard "active_fatal") (get guard "inactive_fatal")
              (= (get guard "requested_bytes") (* 10 (** 1024 3)))
              (= (get guard "kernel_limit_bytes") (* 9 (** 1024 3)))
              (< (get (get report "process_memory") "lifetime_peak_phys_footprint_bytes")
                 (get guard "kernel_limit_bytes"))) "memory condition"))

(defn metrics [report]
  {"phase_seconds" (get report "training_phase_seconds")
   "phase_tokens_per_second" (/ 1024 (get report "training_phase_seconds"))
   "initial_validation_ce" (get report "initial_validation_ce")
   "final_validation_ce" (get report "final_validation_ce")
   "training_objectives" (lfor row (get report "trace") (get row "mean_local_objective"))
   "gradient_norms" (lfor row (get report "trace") (get row "gradient_norm"))
   "compute_seconds" (total (get report "trace") "compute_seconds")
   "gradient_communication_seconds" (total (get report "trace") "gradient_communication_seconds")
   "optimizer_and_finish_sync_seconds" (total (get report "trace") "optimizer_and_finish_sync_seconds")
   "footprint_peak_gib" (/ (get (get report "process_memory") "lifetime_peak_phys_footprint_bytes") (** 1024 3))
   "mlx_peak_gib" (/ (get report "mlx_peak_bytes") (** 1024 3))
   "logical_gradient_bytes" (get report "logical_gradient_bytes")
   "ring_tx_estimated_bytes" (get report "ring_tx_estimated_bytes")
   "interface_delta" (get report "interface_delta")
   "process_io" (get report "process_io") "logical_master_io" (get report "logical_master_io")})

(defn supervisor [name]
  (setv result (read (+ name "-supervisor")))
  (check (and (= (get result "exit_status") 0) (get result "child_reaped")
              (not (get result "timed_out"))) (+ "supervisor " name))
  (get result "wall_seconds"))

(setv ref-seconds (supervisor "oneb-reference")
      result {"global_tokens_counted_once" 1024 "effective_global_batch" 8 "optimizer_updates" 2
              "sequence" 64 "accumulation_per_rank" 4 "gradient_accumulation_dtype" "bfloat16"
              "reference" (metrics reference) "ddp" {}
              "wire_bytes" None "source_sha256" sources "lineage" (get reference "lineage")})
(setv (get (get result "reference") "supervisor_seconds") ref-seconds
      (get (get result "reference") "supervisor_tokens_per_second") (/ 1024 ref-seconds))
(for [mode ["micro" "update"]]
  (setv ranks (get modes mode) a (get ranks 0) b (get ranks 1)
        syncs (if (= mode "micro") 8 2)
        expected-bytes (* syncs 2 1043698432)
        phase (max (lfor rank ranks (get rank "training_phase_seconds")))
        outer (max (lfor i (range 2) (supervisor (+ "oneb-" mode "-rank" (str i))))))
  (check (!= (get a "host") (get b "host")) "distinct physical hosts")
  (check (get (read (+ "oneb-" mode "-input-alignment")) "pass") "input reconstruction")
  (for [field ["weight_sha256" "master_sha256"]]
    (check (= (get a field) (get b field)) (+ "replica " field)))
  (for [rank ranks]
    (check (= (get rank "logical_gradient_bytes") expected-bytes) "logical bytes")
    (check (= (get rank "ring_tx_estimated_bytes") expected-bytes) "ring bytes")
    (check (= (sum (lfor row (get rank "trace") (get (get row "collectives") "gradient_synchronizations"))) syncs) "sync count")
    (check (= (sum (lfor row (get rank "trace") (get (get row "collectives") "collectives"))) (* syncs 49)) "bucket count"))
  (setv comparison (read (+ "oneb-reference-vs-" mode)))
  (check (and (= (get comparison "left_sha256") (get reference "weight_sha256"))
              (= (get comparison "right_sha256") (get a "weight_sha256"))
              (= (get comparison "parameters") 1043698432)
              (= (get comparison "parameter_keys") 203)) "comparison binding")
  (setv (get (get result "ddp") mode)
        {"ranks" (lfor rank ranks (metrics rank)) "phase_seconds" phase
         "phase_global_tokens_per_second" (/ 1024 phase)
         "supervisor_seconds" outer "supervisor_global_tokens_per_second" (/ 1024 outer)
         "gradient_synchronizations_per_rank" syncs "logical_gradient_bytes_per_rank" expected-bytes
         "weight_sha256" (get a "weight_sha256") "master_sha256" (get a "master_sha256")
         "reference_weight_max_abs_error" (get comparison "max_absolute_error")
         "reference_weight_different_elements" (get comparison "different_elements")
         "bf16_tolerance_qualified" False}))
(for [rank (range 2)]
  (check (= (get (get (get modes "micro") rank) "input_hashes")
            (get (get (get modes "update") rank) "input_hashes")) "same ordered micro inputs"))
(setv micro (get (get result "ddp") "micro") update (get (get result "ddp") "update")
      cross (read "oneb-micro-vs-update")
      comparison-digest (.hexdigest (hashlib.sha256 (.read_bytes (/ source-root "compare_weight_exports.hy")))))
(check (and (= (get cross "left_sha256") (get micro "weight_sha256"))
            (= (get cross "right_sha256") (get update "weight_sha256"))) "cross-mode weight binding")
(for [name ["reference-vs-micro" "reference-vs-update" "micro-vs-update"]]
  (setv compared (read (+ "oneb-" name)))
  (check (= (get compared "comparison_source_sha256") comparison-digest) "comparison source")
  (check (= (get compared "parameters") 1043698432) "all coefficients compared")
  (check (< (get (get compared "process_memory") "lifetime_peak_phys_footprint_bytes")
            (* 9 (** 1024 3))) "comparison memory")
  (supervisor (+ "oneb-" name)))
(setv (get result "micro_vs_update_weight_max_abs_error") (get cross "max_absolute_error")
      (get result "micro_vs_update_weight_different_elements") (get cross "different_elements")
      (get result "phase_speedup_update_over_micro") (/ (get micro "phase_seconds") (get update "phase_seconds"))
      (get result "supervisor_speedup_update_over_micro") (/ (get micro "supervisor_seconds") (get update "supervisor_seconds"))
      (get result "logical_gradient_traffic_reduction_fraction")
        (- 1 (/ (get update "logical_gradient_bytes_per_rank") (get micro "logical_gradient_bytes_per_rank")))
      (get result "scope") "one fixed-order short observation; no convergence, sustained scaling or exclusive wire/SSD attribution")
(.write_text (/ root "oneb-summary.json") (+ (json.dumps result :indent 2) "\n"))
(print (json.dumps result :indent 2))
