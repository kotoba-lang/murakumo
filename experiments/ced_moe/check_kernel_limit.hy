"""Fleet-only kill controls; CPU payload<=256MiB, Metal payload<=512MiB.

Run each child in its own process group under the outer time supervisor.
An ineffective kernel limit cannot turn this test into a large allocation.
"""
(import argparse hashlib json os pathlib platform signal socket subprocess sys time)
(import memory_policy [install-kernel-limit])
(import process_memory [counters])

(defn child [kind]
  (when (= kind "metal") (import mlx.core :as mx))
  (setv limit (if (= kind "cpu") 128 384)
        receipt (install-kernel-limit (* limit (** 1024 2)) :reserve-bytes 0))
  (print (json.dumps {"installed" receipt "kind" kind}) :flush True)
  (setv held [])
  (if (= kind "cpu")
    (for [i (range 256)]
      (setv a (bytearray (** 1024 2)))
      (for [page (range 0 (len a) 16384)] (setv (get a page) 1))
      (.append held a)
      (when (= i 0) (print "POSITIVE_ALLOCATION_COMPLETED" :flush True)))
    (do
      (mx.set-cache-limit 0)
      (for [i (range 32)]
        (setv a (mx.ones [(* 4 1024 1024)] :dtype mx.float32))
        (mx.eval a)
        (.append held a)
        (when (= i 0) (print "POSITIVE_ALLOCATION_COMPLETED" :flush True)))))
  (print "LIMIT_INEFFECTIVE" :flush True)
  (raise (SystemExit 3)))

(defn check [output]
  (setv results [])
  (for [kind ["cpu" "metal"]]
    (setv command [sys._base_executable "-m" "hy" __file__ "--child" kind]
          start (time.monotonic)
          proc (subprocess.Popen command :stdout subprocess.PIPE
                  :stderr subprocess.PIPE :text True :start_new_session True))
    (try
      (setv [stdout stderr] (.communicate proc :timeout 20))
      (except [subprocess.TimeoutExpired]
        (os.killpg proc.pid signal.SIGKILL)
        (.communicate proc)
        (raise (RuntimeError "kernel control timed out"))))
    (assert (= proc.returncode (- signal.SIGKILL))
            (+ kind " limit did not kill child: " (str proc.returncode) " " stderr stdout))
    (assert (not (in "LIMIT_INEFFECTIVE" stdout)))
    (assert (in "POSITIVE_ALLOCATION_COMPLETED" stdout)
            "child died before positive allocation; not a valid limit test")
    (setv installed (json.loads (get (.splitlines stdout) 0)))
    (assert (get (get installed "installed") "readback_verified"))
    (.append results {"kind" kind "exit_status" proc.returncode
                      "positive_allocation_completed" True
                      "seconds" (- (time.monotonic) start) "receipt" installed}))
  (setv root (getattr (pathlib.Path __file__) "parent")
        hashes (dfor name ["memory_policy.hy" "memory_limit_helper.hy" "check_kernel_limit.hy"]
                      name (.hexdigest (hashlib.sha256 (.read_bytes (/ root name)))))
        result {"ok" True "controls" results "host" (socket.gethostname)
               "platform" (platform.platform) "source_sha256" hashes
               "scope" "fatal per-process footprint; not an MLX allocator guideline"})
  (.write_text (pathlib.Path output) (json.dumps result :indent 2))
  (.write_text (/ root "kernel-limit-qualified.json") (json.dumps result :indent 2))
  (print (json.dumps result)))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--child" :choices ["cpu" "metal"])
  (.add_argument parser "--output")
  (setv args (.parse_args parser))
  (if args.child (child args.child) (check args.output)))
