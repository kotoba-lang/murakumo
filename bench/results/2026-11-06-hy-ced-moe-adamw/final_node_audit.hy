(import json subprocess)
(setv processes (subprocess.run ["ps" "-axo" "pid,command"] :capture_output True :text True :check True)
      matching (lfor line (.splitlines processes.stdout)
                   :if (any (gfor name ["adamw_train.hy" "bounded_trial.hy" "verify_adamw_artifacts.hy"]
                              (in (+ "hy code/" name) line))) line)
      listener (subprocess.run ["lsof" "-nP" "-iTCP:25701" "-sTCP:LISTEN"] :capture_output True :text True))
(assert (in listener.returncode [0 1]))
(assert (not matching))
(assert (= listener.returncode 1))
(print (json.dumps {"pass" True "owned_training_processes" matching "listener_25701" False
                    "lsof_no_match_status" listener.returncode}))
