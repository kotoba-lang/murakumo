"""Small fleet-only boundary/continuation and cooperative refusal controls."""
(import argparse hashlib json pathlib shutil)
(import mlx.core :as mx)
(import fleet_checkpoint :as c)
(import fleet_mesh [Mesh])
(import memory_policy [require-hard-memory-enforcer])
(import process_memory [counters])

(defn maximum [a b]
  (setv value (float (mx.max (mx.abs (- a b)))))
  (assert (< value 2e-5))
  value)

(defn run [args]
  (setv guard (require-hard-memory-enforcer (* 2 (** 1024 3))) mesh (Mesh 10)
        prefix (pathlib.Path (+ "ten-tiny-" args.state_placement "-prefix/checkpoint-2"))
        suffix (pathlib.Path (+ "ten-tiny-" args.state_placement "-suffix/checkpoint-4"))
        continuous (pathlib.Path (+ "ten-tiny-" args.state_placement "-continuous/checkpoint-4"))
        output (pathlib.Path args.output)
        prefix-meta (json.loads (.read_text (/ prefix "state.json")))
        common (get prefix-meta "common") cfg (get common "config") options (get common "options")
        provenance (get common "data_manifest_sha256"))
  (assert (= (get cfg "name") "tiny"))
  (.mkdir output :exist_ok False)
  (setv [p store restored receipt] (c.load prefix cfg options provenance mesh output)
        boundary {"weights" 0.0 "master" 0.0 "m" 0.0 "v" 0.0})
  (assert (= restored common))
  (assert (= (lfor key mx.random.state (.tolist key)) (get prefix-meta "mlx_rng_state")))
  (for [kind ["weights" "master" "m" "v"] key p]
    (setv expected (get (mx.load (str (/ prefix kind (+ key ".safetensors")))) "value")
          restored-value (if (= kind "weights") (get p key) (store.read kind key))
          (get boundary kind) (max (get boundary kind) (maximum restored-value expected))))
  (assert (all (gfor value (.values boundary) (= value 0.0))))
  (store.close)
  (setv continuation {"weights" 0.0 "master" 0.0 "m" 0.0 "v" 0.0})
  (for [kind ["weights" "master" "m" "v"] key p]
    (setv a (get (mx.load (str (/ suffix kind (+ key ".safetensors")))) "value")
          b (get (mx.load (str (/ continuous kind (+ key ".safetensors")))) "value")
          (get continuation kind) (max (get continuation kind) (maximum a b))))
  (setv prefix-run (json.loads (.read_text (pathlib.Path (+ "ten-tiny-" args.state_placement "-prefix/result.json"))))
        suffix-run (json.loads (.read_text (pathlib.Path (+ "ten-tiny-" args.state_placement "-suffix/result.json"))))
        full-run (json.loads (.read_text (pathlib.Path (+ "ten-tiny-" args.state_placement "-continuous/result.json")))))
  (assert (= (+ (get prefix-run "input_sha256") (get suffix-run "input_sha256"))
             (get full-run "input_sha256")))
  (assert (= (get prefix-run "final_validation_ce") (get suffix-run "initial_validation_ce")))
  (setv refusals [])
  (for [case ["missing_commit" "corrupt_tensor" "missing_master" "missing_m" "missing_v" "wrong_options" "wrong_beta" "wrong_placement" "mixed_generation" "partial"]]
    (setv root (/ output (if (= case "partial") (+ case ".partial") case))
          source (if (and (= case "mixed_generation") (= mesh.rank 1)) continuous prefix)
          opts (dict options))
    (shutil.copytree source root)
    (when (= mesh.rank 1)
      (cond
        (= case "missing_commit") (.unlink (/ root "COMMITTED.json"))
        (= case "corrupt_tensor")
          (do
            (setv path (next (.glob (/ root "weights") "*.safetensors")))
            (with [handle (open path "r+b")]
              (.seek handle -1 2) (setv value (.read handle 1))
              (.seek handle -1 2) (.write handle (bytes [(^ (get value 0) 1)]))))
        (= case "missing_master") (.unlink (next (.glob (/ root "master") "*.safetensors")))
        (= case "missing_m") (.unlink (next (.glob (/ root "m") "*.safetensors")))
        (= case "missing_v") (.unlink (next (.glob (/ root "v") "*.safetensors")))
        (= case "wrong_beta") (setv (get opts "beta2") 0.99)
        (= case "wrong_placement") (setv (get opts "state_placement") (if (= args.state_placement "ram") "nvme" "ram"))
        (= case "wrong_options") (setv (get opts "lr") 0.2)))
    (setv attempted False old-load mx.load old-store c.OptimizerStore error None)
    (defn forbidden [#* a #** k]
      (nonlocal attempted)
      (setv attempted True)
      (raise (AssertionError "tensor loading reached before refusal")))
    (setv mx.load forbidden c.OptimizerStore forbidden)
    (try
      (c.load root cfg opts provenance mesh output)
      (except [failure ValueError] (setv error (str failure)))
      (finally (setv mx.load old-load c.OptimizerStore old-store)))
    (assert (and error (not attempted)))
    (.append refusals {"case" case "refused" True "tensor_load_attempted" attempted "error" error}))
  ;; A failed prepare on one rank must never yield a committed global snapshot.
  (setv root (/ output "prepare_refused"))
  (when (= mesh.rank 1) (.mkdir root))
  (setv refused False store (c.OptimizerStore output "adamw" args.state_placement))
  (store.initialize p)
  (try
    (c.save root p store common mesh)
    (except [failure ValueError] (setv refused True)))
  (assert refused)
  (assert (not (.exists (/ root "COMMITTED.json"))))
  (store.close)
  (setv result {"pass" True "rank" mesh.rank "world_size" mesh.world
                "owned_keys" (len p) "boundary_error" boundary "continuation_error" continuation
                "sampler_inputs_exact" True "mlx_rng_exact" True
                "refusals" refusals "prepare_failure_uncommitted" True
                "memory_enforcer" guard "process_memory" (counters)})
  (.write_text (/ output "result.json") (json.dumps result :indent 2))
  (print (json.dumps {"pass" True "rank" mesh.rank "continuation_error" continuation
                     "refusals" (len refusals)})))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--state-placement" :choices ["ram" "nvme"] :required True)
  (.add_argument parser "--output" :required True)
  (run (.parse_args parser)))
