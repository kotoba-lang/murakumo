"""Fail closed: MLX 0.32.4's limit is advisory, not a hard memory guard.

Do not add an acknowledgement/override flag. A future host/runtime integration
must prove the actual allocation bound before this refusal can be replaced.
"""

(defn require-hard-memory-enforcer [requested-bytes]
  (raise (RuntimeError
    (+ "[hard-memory-enforcer-unqualified] requested ceiling=" (str requested-bytes)
       " bytes. MLX 0.32.4 set_memory_limit is only a guideline and can exceed it. "
       "No qualified hard memory enforcer is integrated; refusing before model "
       "initialization, distributed startup or output creation."))))
