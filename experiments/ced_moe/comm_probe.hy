"""Small CPU-only ring probe; interface counters include background traffic."""
(import argparse json pathlib statistics subprocess time)
(import mlx.core :as mx)

(defn interface-bytes [interface]
  (setv text (subprocess.check-output ["netstat" "-I" interface "-b" "-n"] :text True))
  (for [line (.splitlines text) :if (and (.startswith line (+ interface " "))
                                       (in "<Link#" line))]
    (setv columns (.split line))
    (return {"rx" (int (get columns 6)) "tx" (int (get columns 9))}))
  (raise (ValueError "interface counters unavailable")))

(defn probe [output interface]
  (mx.set-default-device mx.cpu)
  (setv world (mx.distributed.init :strict True :backend "ring"))
  (assert (= (.size world) 2) "this probe requires exactly two physical ranks")
  (setv before (interface-bytes interface) rows [])
  (for [elements [1024 262144 4194304]]
    (setv samples [])
    (for [iteration (range 3)]
      (setv value (+ (.rank world) iteration 1)
            x (mx.full [elements] value :dtype mx.float32)
            expected (+ 3 (* 2 iteration)))
      (mx.eval x)
      (setv start (time.monotonic) y (mx.distributed.all-sum x :group world))
      (mx.eval y)
      (.append samples (- (time.monotonic) start))
      (assert (bool (mx.all (= y expected))) "ring result differs from the two-rank sum"))
    (.append rows {"payload_bytes" (* 4 elements) "samples_seconds" samples
                   "median_seconds" (statistics.median samples)}))
  (setv after (interface-bytes interface)
        result {"schema" "ced-moe-cpu-ring-probe/v1" "rank" (.rank world)
                "world_size" (.size world) "device" "cpu" "interface" interface
                "all_sums_verified" True "rows" rows
                "interface_rx_delta_bytes" (- (get after "rx") (get before "rx"))
                "interface_tx_delta_bytes" (- (get after "tx") (get before "tx"))
                "counter_scope" "whole interface, includes background/control traffic; not exclusive job wire bytes"})
  (.write_text (pathlib.Path output) (json.dumps result :indent 2) :encoding "utf-8")
  (print (json.dumps result :sort_keys True) :flush True))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--output" :required True)
  (.add_argument parser "--interface" :default "en0")
  (setv args (.parse_args parser))
  (probe args.output args.interface))
