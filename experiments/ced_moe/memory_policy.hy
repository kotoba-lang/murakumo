"""Per-process kernel backstop; MLX's setting remains advisory.

No acknowledgement or bypass. A failed setter/readback prevents allocation.
"""
(import hashlib json os pathlib platform socket subprocess sys)

(defn ensure [condition [message "kernel memory qualification mismatch"]]
  (when (not condition) (raise (RuntimeError message))))

(defn install-kernel-limit [requested-bytes [reserve-bytes (** 1024 3)]]
  (when (not (= (platform.system) "Darwin"))
    (raise (RuntimeError "[hard-memory-enforcer-unavailable] requires qualified macOS kernel")))
  (when (not (<= (* 64 (** 1024 2)) requested-bytes (* 10 (** 1024 3))))
    (raise (ValueError "requested memory must be64MiB..10GiB")))
  (setv limit-mib (// (- requested-bytes reserve-bytes) (** 1024 2)))
  (when (< limit-mib 64) (raise (ValueError "insufficient budget after safety reserve")))
  (setv helper (.with_name (pathlib.Path __file__) "memory_limit_helper.hy")
        paths (os.pathsep.join (lfor p sys.path :if p p))
        command ["/usr/bin/sudo" "-n" "--" "/usr/bin/env" (+ "PYTHONPATH=" paths)
                 sys._base_executable "-m" "hy" (str helper)
                 "--pid" (str (os.getpid)) "--mib" (str limit-mib)]
        result (subprocess.run command :text True :capture_output True :timeout 15))
  (when (!= result.returncode 0)
    (raise (RuntimeError (+ "[hard-memory-enforcer-unqualified] setter failed: " result.stderr))))
  (setv receipt (json.loads result.stdout))
  (ensure (and (get receipt "readback_verified") (get receipt "active_fatal")
               (get receipt "inactive_fatal")
               (= (get receipt "active_limit_mib") limit-mib)
               (= (get receipt "pid") (os.getpid))))
  (setv (get receipt "requested_bytes") requested-bytes
        (get receipt "kernel_limit_bytes") (* limit-mib (** 1024 2))
        (get receipt "safety_reserve_bytes") reserve-bytes)
  receipt)

(defn require-hard-memory-enforcer [requested-bytes]
  (try
    (setv root (getattr (pathlib.Path __file__) "parent")
          proof (json.loads (.read_text (/ root "kernel-limit-qualified.json"))))
    (ensure (and (get proof "ok") (= (get proof "host") (socket.gethostname))
                 (= (get proof "platform") (platform.platform))
                 (= (set (lfor r (get proof "controls") (get r "kind"))) #{"cpu" "metal"})))
    (for [[name expected] (.items (get proof "source_sha256"))]
      (ensure (in name ["memory_policy.hy" "memory_limit_helper.hy" "check_kernel_limit.hy"]))
      (ensure (= (.hexdigest (hashlib.sha256 (.read_bytes (/ root name)))) expected)))
    (ensure (= (len (get proof "source_sha256")) 3))
    (for [row (get proof "controls")]
      (ensure (= (get row "exit_status") -9))
      (ensure (get row "positive_allocation_completed")))
    (setv receipt (install-kernel-limit requested-bytes)
          (get receipt "qualification_source_sha256") (get proof "source_sha256")
          (get receipt "qualification_sha256")
            (.hexdigest (hashlib.sha256 (.read_bytes (/ root "kernel-limit-qualified.json")))))
    receipt
    (except [error Exception]
      (raise (RuntimeError (+ "[hard-memory-enforcer-unqualified] " (str error))) :from error))))
