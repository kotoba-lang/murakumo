"""Materialized ring transport with explicit payload/wait accounting.

Broadcast uses owner values plus zeros in all_sum, not implicit autograd.
Boundary VJPs are handled by the model-parallel driver. Whole-interface traffic
is measured separately; logical/ring estimates are not exclusive wire bytes.
"""
(import json time)
(import mlx.core :as mx)

(defclass Mesh []
  (defn __init__ [self world]
    (setv self.group (mx.distributed.init :strict True :backend "ring")
          self.rank (.rank self.group) self.world (.size self.group)
          self.stats {})
    (when (or (not (<= 2 world 10)) (!= self.world world))
      (raise (ValueError "physical group must match requested2..10-rank layout"))))

  (defn record [self channel started bytes [useful 0]]
    (setv entry (.setdefault self.stats channel {"calls" 0 "seconds" 0.0
                "collective_input_bytes_per_rank" 0 "ring_tx_estimated_bytes_per_rank" 0.0
                "useful_owner_payload_bytes" 0}))
    (+= (get entry "calls") 1)
    (+= (get entry "seconds") (- (time.monotonic) started))
    (+= (get entry "collective_input_bytes_per_rank") bytes)
    (+= (get entry "useful_owner_payload_bytes") useful))

  (defn gather [self value [limit 8192] [channel "metadata"]]
    (setv payload (.encode (json.dumps value :sort_keys True :separators #("," ":"))))
    (when (> (+ 4 (len payload)) limit) (raise (ValueError "bounded control payload exceeded")))
    (setv data (+ (.to_bytes (len payload) 4 "little") payload)
          buffer (mx.array (+ (list data) (* [0] (- limit (len data)))) :dtype mx.uint8)
          tick (time.monotonic)
          result (mx.distributed.all-gather buffer :group self.group))
    (mx.eval result)
    (.record self channel tick limit)
    (+= (get (get self.stats channel) "ring_tx_estimated_bytes_per_rank") (* limit (- self.world 1)))
    (setv rows (.tolist (.reshape result [self.world limit])) values [])
    (for [row rows]
      (setv size (int.from_bytes (bytes (cut row 0 4)) "little"))
      (when (> (+ size 4) limit) (raise (ValueError "invalid gathered control length")))
      (.append values (json.loads (bytes (cut row 4 (+ 4 size))))))
    values)

  (defn sum [self value channel]
    (setv tick (time.monotonic) result (mx.distributed.all-sum value :group self.group))
    (mx.eval result)
    (.record self channel tick value.nbytes)
    (+= (get (get self.stats channel) "ring_tx_estimated_bytes_per_rank")
        (* value.nbytes (/ (* 2 (- self.world 1)) self.world)))
    (mx.stop-gradient result))

  (defn barrier [self]
    (.sum self (mx.array [1.0]) "barrier_wait"))

  (defn broadcast [self values owner channel]
    ;; Peers' waiting for owner compute is separated from tensor transfer.
    (.barrier self)
    (setv dtypes {"float32" mx.float32 "bfloat16" mx.bfloat16 "int32" mx.int32 "uint32" mx.uint32}
          description (when (= self.rank owner)
            (dfor [key value] (.items values) key
               {"shape" (list value.shape)
                "dtype" (next (gfor [name dtype] (.items dtypes) :if (= value.dtype dtype) name))}))
          rows (.gather self description 8192 (+ channel "_metadata"))
          description (get rows owner) result {})
    (for [[key desc] (.items description)]
      (setv value (if (= self.rank owner) (get values key)
                      (mx.zeros (get desc "shape") :dtype (get dtypes (get desc "dtype")))))
      (if value.size
        (do
          (setv (get result key) (.sum self value channel))
          (+= (get (get self.stats channel) "useful_owner_payload_bytes") value.nbytes))
        (setv (get result key) value)))
    result))
