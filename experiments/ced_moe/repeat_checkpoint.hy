"""Owner-local tensor shards plus an agreed all-rank checkpoint certificate.

Loading validates every local shard before any tensor allocation, then requires
all current ranks to agree on the same global manifest. A lone prepared rank is
not resumable global state. World/layout changes require explicit resharding.
"""
(import hashlib json math pathlib shutil time)
(import mlx.core :as mx)
(import model :as m)
(import fleet_model :as s)
(import stream_checkpoint :as base)
(import fleet_optimizer [OptimizerStore])

(setv SCHEMA "ced-moe-repeat-shards/v1")

(defn sources []
  (setv root (. (pathlib.Path __file__) parent) hashes (base.source-fingerprint))
  (for [name ["fleet_model.hy" "fleet_mesh.hy" "fleet_pipeline.hy"
              "optimizer_shards.hy" "fleet_optimizer.hy" "repeat_checkpoint.hy" "repeat_train.hy"]]
    (setv (get hashes name) (base.digest (/ root name))))
  hashes)

(defn write-json [path value]
  (.write_text path (json.dumps value :sort_keys True :indent 2))
  (base.sync-file path))

(defn owned-shapes [cfg rank world]
  (dfor [key shape] (.items (m.shapes cfg)) :if (= (s.owner key cfg world) rank) key shape))

(defn prepare [root p store common rank world]
  (setv temporary (.with_name root (+ root.name ".partial"))
        expected (sum (gfor value (.values p) (* (+ 2 (* 4 (len store.kinds))) value.size))))
  (base.ensure (not (or (.exists root) (.exists temporary))) "checkpoint destination exists")
  (base.ensure (> (. (shutil.disk_usage root.parent) free) (+ expected (* 512 (** 1024 2)))) "checkpoint disk capacity")
  (.mkdir temporary :mode 0o700)
  (for [kind (+ ["weights"] store.kinds)] (.mkdir (/ temporary kind)))
  (setv files {})
  (for [[key value] (.items p)]
    (setv relative (+ "weights/" key ".safetensors") target (/ temporary relative))
    (mx.save-safetensors (str target) {"value" value})
    (base.sync-file target)
    (setv (get files relative) (base.descriptor target "weights" key value))
    (for [kind store.kinds]
      (setv relative (+ kind "/" key ".safetensors") target (/ temporary relative))
      (store.export kind key target)
      (base.sync-file target)
      (setv (get files relative) {"kind" kind "key" key "shape" (list value.shape)
           "dtype" "F32" "tensor_bytes" (* 4 value.size) "file_bytes" (. (target.stat) st_size)
           "sha256" (base.digest target)})))
  (setv meta {"schema" SCHEMA "rank" rank "world_size" world "common" common
             "parameter_order" (list p) "files" files
             "mlx_rng_state" (lfor key mx.random.state (.tolist key))})
  (write-json (/ temporary "state.json") meta)
  (.write_text (/ temporary "COMPLETE") (+ (base.digest (/ temporary "state.json")) "\n"))
  (base.sync-file (/ temporary "COMPLETE"))
  (for [kind (+ ["weights"] store.kinds)] (base.sync-directory (/ temporary kind)))
  (base.sync-directory temporary)
  (.rename temporary root) (base.sync-directory root.parent)
  {"rank" rank "state_sha256" (base.digest (/ root "state.json"))
   "keys" (list p) "parameters" (sum (gfor v (.values p) v.size))
   "file_bytes" (sum (gfor v (.values files) (get v "file_bytes")))})

(defn save [root p store common mesh]
  (setv root (pathlib.Path root) tick (time.monotonic) vote None error None)
  (try
    (setv vote (prepare root p store common mesh.rank mesh.world))
    (except [failure Exception] (setv error (str failure))))
  (setv votes (mesh.gather {"ready" (is error None) "error" error "shard" vote
                           "common_sha256" (.hexdigest (hashlib.sha256 (.encode (json.dumps common :sort_keys True))))}))
  ;; Common identity is serialized explicitly below; no local-only completion.
  (for [v votes] (base.ensure (get v "ready") (+ "rank prepare refused: " (str (get v "error")))))
  (base.ensure (= (len (set (lfor v votes (get v "common_sha256")))) 1) "checkpoint common state differs")
  (setv ranks (lfor v votes (get v "shard"))
        manifest {"schema" SCHEMA "common" common "ranks" ranks})
  (write-json (/ root "GLOBAL.json") manifest)
  (setv digest (base.digest (/ root "GLOBAL.json")) all-digests (mesh.gather digest))
  (base.ensure (= (len (set all-digests)) 1) "global manifest disagreement")
  (write-json (/ root "COMMITTED.json") {"manifest_sha256" digest "rank_votes" all-digests})
  (base.sync-directory root)
  (setv certificates (mesh.gather (base.digest (/ root "COMMITTED.json"))))
  (base.ensure (= (len (set certificates)) 1) "global commit certificate disagreement")
  {"step" (get common "step") "seconds" (- (time.monotonic) tick)
   "global_manifest_sha256" digest "certificate_sha256" (get certificates 0)
   "shards" ranks "local_file_bytes" (get vote "file_bytes")})

(defn inspect-local [root cfg options provenance rank world]
  (setv root (pathlib.Path root))
  (base.ensure (and (.is_dir root) (not (.is_symlink root))
                    (not (.endswith root.name ".partial"))) "incomplete shard directory")
  (for [name ["state.json" "COMPLETE" "GLOBAL.json" "COMMITTED.json"]]
    (setv path (/ root name))
    (base.ensure (and (.is_file path) (not (.is_symlink path))
                      (< (. (path.stat) st_size) (* 16 1024 1024))) "missing/oversized checkpoint metadata"))
  (base.ensure (= (.strip (.read_text (/ root "COMPLETE"))) (base.digest (/ root "state.json"))) "local seal mismatch")
  (setv manifest (json.loads (.read_text (/ root "GLOBAL.json")))
        cert (json.loads (.read_text (/ root "COMMITTED.json")))
        digest (base.digest (/ root "GLOBAL.json"))
        meta (json.loads (.read_text (/ root "state.json")))
        common (get manifest "common") shapes (owned-shapes cfg rank world))
  (base.ensure (= (get cert "manifest_sha256") digest) "global certificate seal mismatch")
  (base.ensure (= (get cert "rank_votes") (* [digest] world)) "global certificate missing rank votes")
  (base.ensure (= (get manifest "schema") SCHEMA (get meta "schema")) "sharded schema mismatch")
  (base.ensure (and (= (get meta "rank") rank) (= (get meta "world_size") world)
                   (= (get meta "common") common)) "rank or common metadata mismatch")
  (base.ensure (= (get common "config") cfg) "sharded config mismatch")
  (base.ensure (= (get common "options") options) "sharded options/layout mismatch")
  (base.ensure (= (get common "data_manifest_sha256") provenance) "sharded corpus mismatch")
  (base.ensure (= (get common "source_sha256") (sources)) "sharded source mismatch")
  (base.ensure (= (get common "runtime_versions") (base.runtime-fingerprint)) "sharded runtime mismatch")
  (setv step (get common "step"))
  (base.ensure (and (= (type step) int) (>= step 0)
                   (= (get common "tokens_seen") (* step (get options "sequence") (get options "batch")))) "sharded step/token mismatch")
  (setv ranks (get manifest "ranks"))
  (base.ensure (= (len ranks) world) "global shard count mismatch")
  (for [i (range world)]
    (setv item (get ranks i) keys (list (owned-shapes cfg i world)))
    (base.ensure (and (= (get item "rank") i) (= (get item "keys") keys)) "global partition coverage mismatch"))
  (base.ensure (= (get (get ranks rank) "state_sha256") (base.digest (/ root "state.json"))) "wrong local shard in global generation")
  (base.ensure (= (get meta "parameter_order") (list shapes)) "local parameter order/ownership mismatch")
  (setv kinds (if (= (get options "optimizer") "adamw") ["master" "m" "v"] ["master"])
        expected (set (gfor kind (+ ["weights"] kinds) key shapes (+ kind "/" key ".safetensors")))
        actual (set))
  (base.ensure (= (set (get meta "files")) expected) "local shard component mismatch")
  (for [path (.rglob root "*")]
    (base.ensure (not (.is_symlink path)) "symlink shard component")
    (when (.is_file path) (.add actual (str (.relative_to path root)))))
  (base.ensure (= actual (| expected #{"state.json" "COMPLETE" "GLOBAL.json" "COMMITTED.json"})) "extra/missing global checkpoint file")
  (for [[relative entry] (.items (get meta "files"))]
    (setv [kind name] (.split relative "/") key (cut name 0 (- (len ".safetensors")))
          path (/ root relative) dtype (if (!= kind "weights") "F32"
                         (if (= (get options "dtype") "float32") "F32" "BF16")))
    (base.ensure (and (= (get entry "key") key) (= (get entry "kind") kind)
                     (= (get entry "shape") (get shapes key)) (= (get entry "dtype") dtype)) "shard descriptor mismatch")
    (base.ensure (= (. (path.stat) st_size) (get entry "file_bytes")) "shard file size mismatch")
    (base.ensure (= (base.digest path) (get entry "sha256")) "shard file digest mismatch")
    (base.inspect-header path dtype (get shapes key)))
  #(meta digest))

(defn load [root cfg options provenance mesh output]
  (setv tick (time.monotonic) error None meta None digest None)
  (try
    (setv [meta digest] (inspect-local root cfg options provenance mesh.rank mesh.world))
    (except [failure Exception] (setv error (str failure))))
  (setv votes (mesh.gather {"ready" (is error None) "error" error "manifest" digest}))
  (for [v votes] (base.ensure (get v "ready") (+ "global load refused: " (str (get v "error")))))
  (base.ensure (= (len (set (lfor v votes (get v "manifest")))) 1) "mixed checkpoint generations")
  ;; All local integrity checks and global agreement precede tensor loading.
  (setv root (pathlib.Path root) p {} store (OptimizerStore output (get options "optimizer") (get options "state_placement")))
  (for [key (get meta "parameter_order")]
    (setv value (get (mx.load (str (/ root "weights" (+ key ".safetensors")))) "value"))
    (mx.eval value) (setv (get p key) value)
    (for [kind store.kinds]
      (store.restore kind key (/ root kind (+ key ".safetensors")))))
  (setv mx.random.state (lfor key (get meta "mlx_rng_state") (mx.array key :dtype mx.uint32)))
  (mx.eval mx.random.state)
  #(p store (get meta "common") {"seconds" (- (time.monotonic) tick) "manifest_sha256" digest}))
