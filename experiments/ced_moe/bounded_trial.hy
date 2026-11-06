"""Own-process timeout for an explicitly admitted fleet experiment.

This is not a scheduler, reservation authority, or replacement for the native
resource governor. It never discovers, kills or reconfigures unrelated jobs.
"""
(import argparse json os pathlib signal subprocess sys time)

(defn supervise [command seconds output]
  (setv started (time.monotonic) child (subprocess.Popen command :start_new_session True)
        timed-out False)
  (try
    (setv status (.wait child :timeout seconds))
    (except [subprocess.TimeoutExpired]
      (setv timed-out True)
      (os.killpg child.pid signal.SIGTERM)
      (try
        (.wait child :timeout 5)
        (except [subprocess.TimeoutExpired]
          (os.killpg child.pid signal.SIGKILL)
          (.wait child)))
      (setv status 124)))
  (setv result {"child_pid" child.pid "exit_status" status "timed_out" timed-out
                "deadline_seconds" seconds "wall_seconds" (- (time.monotonic) started)
                "child_reaped" (not (= (.poll child) None))})
  (.write_text (pathlib.Path output) (json.dumps result :indent 2) :encoding "utf-8")
  (print (json.dumps result :sort_keys True) :flush True)
  status)

(when (= __name__ "__main__")
  (setv p (argparse.ArgumentParser))
  (.add_argument p "--seconds" :type float :required True)
  (.add_argument p "--output" :required True)
  (.add_argument p "command" :nargs argparse.REMAINDER)
  (setv args (.parse_args p) command args.command)
  (when (and command (= (get command 0) "--")) (setv command (cut command 1 None)))
  (when (or (not command) (<= args.seconds 0)) (raise (ValueError "positive deadline and command required")))
  (sys.exit (supervise command args.seconds args.output)))
