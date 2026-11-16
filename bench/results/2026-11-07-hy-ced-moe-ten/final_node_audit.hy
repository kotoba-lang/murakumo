(import json subprocess)
(setv processes (subprocess.run ["ps" "-axo" "pid,args"] :capture_output True :text True :check True)
      matching (lfor line (.splitlines processes.stdout)
                   :if (any (gfor name ["fleet_train.hy" "bounded_trial.hy" "verify_fleet_artifacts.hy"]
                              (in (+ "hy code/" name) line))) line)
      listener (subprocess.run ["lsof" "-nP" "-iTCP:25711" "-sTCP:LISTEN"] :capture_output True :text True))
(assert (in listener.returncode [0 1]))
(assert (not matching))
(assert (= listener.returncode 1))
(print (json.dumps {"pass" True "owned_training_processes" matching "listener_25711" False
                    "lsof_no_match_status" listener.returncode}))
