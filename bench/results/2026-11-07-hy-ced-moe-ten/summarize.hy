"""Stdlib-only checks/arithmetic on dated raw fleet receipts; no model work."""
(import datetime hashlib json math pathlib re)
(setv root (. (pathlib.Path __file__) parent) source-root (/ root "../../../experiments/ced_moe"))
(defn read [path] (json.loads (.read_text (/ root path))))
(defn checked [name]
  (setv reports (lfor rank (range 10) (read (+ "rank" (str rank) "/" name "/result.json")))
        supervisors (lfor rank (range 10) (read (+ "rank" (str rank) "/" name "-supervisor.json")))
        first (get reports 0))
  (assert (= (len (set (lfor r reports (get r "host")))) 10))
  (for [[rank r] (enumerate reports)]
    (assert (= (get r "status") "complete"))
    (assert (= (get r "rank") rank))
    (assert (= (get r "world_size") 10))
    (assert (= (get r "parameters") 3003341824))
    (for [key ["options" "config" "partition" "source_sha256" "data_manifest_sha256" "input_sha256"
               "initial_validation_ce" "final_validation_ce" "confirmation_initial" "confirmation_final"]]
      (assert (= (get r key) (get first key)) key))
    (for [[name sha] (.items (get r "source_sha256"))]
      (assert (= sha (.hexdigest (hashlib.sha256 (.read_bytes (/ source-root name))))) name))
    (setv guard (get r "memory_enforcer") mem (get r "process_memory"))
    (assert (and (get guard "readback_verified") (get guard "active_fatal") (get guard "inactive_fatal")))
    (assert (= (get guard "requested_bytes") (* 10 (** 1024 3))))
    (assert (= (get guard "kernel_limit_bytes") (* 9 (** 1024 3))))
    (assert (< (get mem "lifetime_peak_phys_footprint_bytes") (get guard "kernel_limit_bytes")))
    (setv part (get (get r "partition") rank) opts (get r "options")
          kinds (if (= (get opts "optimizer") "adamw") ["master" "m" "v"] ["master"]))
    (assert (= (get part "optimizer_state_kinds") kinds))
    (assert (= (get part "optimizer_state_bytes") (* 4 (len kinds) (get part "parameters"))))
    (setv updates (- (get r "end_step") (get r "start_step")) io (get r "logical_optimizer_io")
          state-bytes (* 4 (get part "parameters")))
    (for [kind kinds]
      (assert (= (get (get io "read_bytes") kind) (* updates state-bytes)))
      (assert (= (get (get io "write_bytes") kind)
                 (* (+ updates (if (= (get r "start_step") 0) 1 0)) state-bytes)))
      (if (get r "start_step")
        (assert (> (get (get io "restore_file_bytes") kind) state-bytes))
        (assert (= (get (get io "restore_file_bytes") kind) 0))))
    (for [row (get r "trace")]
      (assert (math.isfinite (get row "objective")))
      (assert (math.isfinite (get row "global_gradient_norm")))
      (assert (= (get row "learning_rate") (* (get opts "lr") (min 1.0 (/ (get row "step") 4)))))))
  (for [sup supervisors]
    (assert (and (= (get sup "exit_status") 0) (get sup "child_reaped") (not (get sup "timed_out")))))
  (setv keys (sum (lfor p (get first "partition") (get p "keys")) []))
  (assert (= (len keys) (len (set keys)) 304))
  (assert (= (sum (lfor p (get first "partition") (get p "parameters"))) 3003341824))
  #(reports supervisors))
(defn measure [groups]
  (setv reports (sum (lfor g groups (get g 0)) [])
        tokens (sum (lfor g groups (get (get (get g 0) 0) "global_training_tokens_counted_once")))
        phase (sum (lfor g groups (max (lfor r (get g 0) (get r "training_phase_seconds")))))
        steps (sum (lfor g groups (max (lfor r (get g 0) (sum (gfor row (get r "trace") (get row "step_seconds")))))))
        outer (sum (lfor g groups (max (lfor sup (get g 1) (get sup "wall_seconds"))))))
  {"tokens_counted_globally_once" tokens "phase_with_periodic_dev_seconds" phase
   "phase_with_periodic_dev_tok_s" (/ tokens phase) "update_seconds" steps "update_only_tok_s" (/ tokens steps)
   "supervisor_seconds" outer "all_in_tok_s" (/ tokens outer)
   "initial_dev_ce" (get (get reports 0) "initial_validation_ce")
   "final_dev_ce" (get (get reports -1) "final_validation_ce")
   "peak_footprint_gib" (/ (max (lfor r reports (get (get r "process_memory") "lifetime_peak_phys_footprint_bytes"))) (** 1024 3))
   "global_logical_boundary_payload_bytes" (sum (gfor g groups value (.values (get (get (get g 0) 0) "mesh")) (get value "useful_owner_payload_bytes")))
   "per_rank" (lfor rank (range 10)
     (do (setv runs (lfor g groups (get (get g 0) rank)))
       {"rank" rank
        "whole_nic_tx_bytes" (sum (lfor r runs (get (get r "interface_delta") "tx")))
        "whole_nic_rx_bytes" (sum (lfor r runs (get (get r "interface_delta") "rx")))
        "process_disk_read_bytes" (sum (lfor r runs (get (get r "process_io") "read_bytes")))
        "process_disk_write_bytes" (sum (lfor r runs (get (get r "process_io") "write_bytes")))
        "peak_footprint_gib" (/ (max (lfor r runs (get (get r "process_memory") "lifetime_peak_phys_footprint_bytes"))) (** 1024 3))
        "logical_state_reads" (dfor kind (get (get (get runs 0) "logical_optimizer_io") "read_bytes")
           kind (sum (lfor r runs (get (get (get r "logical_optimizer_io") "read_bytes") kind))))
        "logical_state_writes" (dfor kind (get (get (get runs 0) "logical_optimizer_io") "write_bytes")
           kind (sum (lfor r runs (get (get (get r "logical_optimizer_io") "write_bytes") kind))))}))})
(setv results {} streams {} cohorts {})
(for [mode ["nvme" "ram"]]
  (when (.exists (/ root (+ "rank0/ten-3b-" mode "-suffix/result.json")))
    (setv groups (lfor phase ["prefix" "suffix"] (checked (+ "ten-3b-" mode "-" phase)))
          p (get (get (get groups 0) 0) 0) f (get (get (get groups 1) 0) 0))
    (assert (= (get p "start_step") 0))
    (assert (= (get p "end_step") (get f "start_step") 16))
    (assert (= (get f "end_step") 32))
    (assert (= (get p "final_validation_ce") (get f "initial_validation_ce")))
    (assert (= (get (get p "options") "state_placement") mode))
    (setv row (measure groups)
          (get row "save_seconds") (lfor g groups (max (lfor r (get g 0) (get (get r "checkpoint") "seconds"))))
          (get row "load_seconds") (max (lfor r (get (get groups 1) 0) (get (get r "restored") "seconds")))
          manifests [(get (get p "checkpoint") "global_manifest_sha256") (get (get f "checkpoint") "global_manifest_sha256")]
          (get row "checkpoint_manifest_sha256") manifests
          (get row "boundary_ce_error") (- (get p "final_validation_ce") (get f "initial_validation_ce"))
          (get row "partition") (get p "partition")
          (get streams mode) (+ (get p "input_sha256") (get f "input_sha256"))
          opts (dict (get p "options")))
    (.pop opts "state_placement") (.pop opts "state_offload")
    (setv (get cohorts mode) {"options_except_placement" opts "config" (get p "config")
                              "sources" (get p "source_sha256") "corpus" (get p "data_manifest_sha256")})
    (when (.exists (/ root (+ "rank0/ten-readback-" mode ".json")))
      (setv reads (lfor rank (range 10) (read (+ "rank" (str rank) "/ten-readback-" mode ".json"))))
      (for [[rank receipt] (enumerate reads)]
        (setv sup (read (+ "rank" (str rank) "/ten-readback-" mode "-supervisor.json")))
        (assert (and (get receipt "pass") (get receipt "sampler_states_exact")
                     (get receipt "optimizer_step_schedule_exact") (get receipt "boundary_ce_exact") (get receipt "training_inputs_exact")))
        (assert (= (get receipt "manifest_sha256") manifests))
        (assert (= (get receipt "expected_input_sha256") (get streams mode)))
        (assert (and (= (get sup "exit_status") 0) (get sup "child_reaped") (not (get sup "timed_out")))))
      (setv total (sum (lfor r reads (get r "owned_tensor_shards_per_generation"))))
      (assert (= total 1216))
      (setv (get row "readback") {"files_per_global_generation" total
            "bytes_per_global_generation" (lfor i (range 2) (sum (lfor r reads (get (get r "file_bytes_per_generation") i))))
            "changed_shards_16_to_32" (dfor kind ["weights" "master" "m" "v"] kind
                (sum (lfor r reads (get (get r "changed_shards_16_to_32") kind))))}))
    (setv (get results mode) row)))
(setv comparison None)
(when (= (len results) 2)
  (assert (= (get streams "nvme") (get streams "ram")))
  (assert (= (get cohorts "nvme") (get cohorts "ram")))
  (setv a (get results "nvme") b (get results "ram")
        comparison {"same_input_stream_and_options_except_placement" True
          "initial_dev_ce_difference" (- (get b "initial_dev_ce") (get a "initial_dev_ce"))
          "final_dev_ce_difference_ram_minus_nvme" (- (get b "final_dev_ce") (get a "final_dev_ce"))
          "ram_to_nvme_phase_speed_ratio" (/ (get b "phase_with_periodic_dev_tok_s") (get a "phase_with_periodic_dev_tok_s"))
          "ram_to_nvme_all_in_speed_ratio" (/ (get b "all_in_tok_s") (get a "all_in_tok_s"))}))
(defn swap-mib [snapshot]
  (float (get (re.search r"used = ([0-9.]+)M" (get snapshot "swap")) 1)))
(for [mode results]
  (setv per-rank (get (get results mode) "per_rank"))
  (for [rank (range 10)]
    (setv a (read (+ "rank" (str rank) "/3b-" mode "-prefix-before.json"))
          b (read (+ "rank" (str rank) "/3b-" mode "-suffix-after.json"))
          (get (get per-rank rank) "host_swap_mib_before") (swap-mib a)
          (get (get per-rank rank) "host_swap_mib_after") (swap-mib b)
          (get (get per-rank rank) "host_swap_mib_delta") (- (swap-mib b) (swap-mib a)))))
(setv summary {"source_commit" "087603fd" "world_size" 10 "placements" results "comparison" comparison
               "developer_mac_model_execution" False "wire_bytes" None
               "timing_scope" "globaltokens once; sum maxrank phase/supervisor durations; phase includes periodicdev; record gaps/readback excluded"
               "io_scope" "logicalstateaccess in eitherbackend is notdiskIO; processrusage and devicewide SSDtransfer counters differ"
               "network_scope" "logicalboundarypayload globally once,wholeNIC includes background/control; no exclusivewirebytes"
               "limits" "one trial/backend,NVMe first,no cacheflush,BF16crossrun bitwise parity unqualified,headnode inference retained"})
(.write_text (/ root "summary.json") (+ (json.dumps summary :indent 2) "\n"))
(print (json.dumps summary :indent 2))
