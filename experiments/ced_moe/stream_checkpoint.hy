"""Versioned single-rank SGD checkpoints, bounded to one tensor at a time.

Weights and FP32 masters are separate shards. Hash/shape/dtype/source checks
finish before tensor loading. COMPLETE seals metadata; fsync precedes rename.
No link to the mutable optimizer workspace is published.
"""
(import hashlib importlib.metadata json math os pathlib shutil struct time)
(import mlx.core :as mx)
(import model :as m)
(import data [digest])
(import nvme_master [MasterStore])

(setv SCHEMA "ced-moe-nvme-checkpoint/v2")

(defn ensure [condition message]
  (when (not condition) (raise (ValueError (+ "[checkpoint-refused] " message)))))

(defn source-fingerprint []
  (setv root (getattr (pathlib.Path __file__) "parent"))
  (dfor name ["model.hy" "train.hy" "nvme_master.hy" "stream_checkpoint.hy"
              "memory_policy.hy" "memory_limit_helper.hy" "process_memory.hy"]
        name (digest (/ root name))))

(defn runtime-fingerprint []
  (dfor name ["hy" "mlx" "numpy"] name (importlib.metadata.version name)))

(defn sync-file [path]
  (with [handle (open path "rb")] (os.fsync (.fileno handle))))

(defn sync-directory [path]
  (setv fd (os.open path os.O_RDONLY))
  (try (os.fsync fd) (finally (os.close fd))))

(defn descriptor [path kind key value]
  {"kind" kind "key" key "shape" (list value.shape)
   "dtype" (if (= value.dtype mx.float32) "F32" "BF16")
   "tensor_bytes" value.nbytes "file_bytes" (. (path.stat) st_size)
   "sha256" (digest path)})

(defn save [root p state meta]
  (setv started (time.monotonic) root (pathlib.Path root)
        temporary (.with_name root (+ root.name ".partial"))
        expected (sum (gfor value (.values p) (+ value.nbytes (* 4 value.size)))))
  (ensure (= (get meta "optimizer") "sgd") "NVMe format supports SGD only")
  (ensure (= (get meta "world_size") 1) "global multi-rank checkpoint is not qualified")
  (ensure (not (or (.exists root) (.exists temporary))) "checkpoint target already exists")
  (ensure (> (. (shutil.disk_usage root.parent) free) (+ expected (* 512 (** 1024 2))))
           "insufficient disk space for checkpoint")
  (.mkdir temporary :mode 0o700)
  (for [kind ["weights" "master"]] (.mkdir (/ temporary kind) :mode 0o700))
  (setv files {})
  (for [[key value] (.items p)]
    (setv relative (+ "weights/" key ".safetensors") path (/ temporary relative))
    (mx.eval value)
    (mx.save-safetensors (str path) {"value" value})
    (sync-file path)
    (setv (get files relative) (descriptor path "weights" key value))
    (setv relative (+ "master/" key ".safetensors") path (/ temporary relative)
          source (/ (. (get state "master") root) (+ key ".safetensors")))
    (shutil.copyfile source path)
    (sync-file path)
    (setv (get files relative) {"kind" "master" "key" key "shape" (list value.shape)
                               "dtype" "F32" "tensor_bytes" (* 4 value.size)
                               "file_bytes" (. (path.stat) st_size) "sha256" (digest path)}))
  (setv payload (dict meta)
        (get payload "schema") SCHEMA (get payload "files") files
        (get payload "parameter_order") (list p)
        (get payload "source_sha256") (source-fingerprint)
        (get payload "runtime_versions") (runtime-fingerprint)
        (get payload "mlx_rng_state") (lfor key mx.random.state (.tolist key)))
  (setv meta-path (/ temporary "state.json"))
  (.write_text meta-path (json.dumps payload :sort_keys True :indent 2) :encoding "utf-8")
  (sync-file meta-path)
  (.write_text (/ temporary "COMPLETE") (+ (digest meta-path) "\n") :encoding "ascii")
  (sync-file (/ temporary "COMPLETE"))
  (for [kind ["weights" "master"]] (sync-directory (/ temporary kind)))
  (sync-directory temporary)
  (.rename temporary root)
  (sync-directory root.parent)
  {"path" (str root) "step" (get meta "step") "seconds" (- (time.monotonic) started)
   "file_bytes" (sum (gfor entry (.values files) (get entry "file_bytes")))
   "state_sha256" (digest (/ root "state.json")) "schema" SCHEMA})

(defn inspect-header [path expected-dtype shape]
  (with [handle (open path "rb")]
    (setv size-bytes (.read handle 8))
    (ensure (= (len size-bytes) 8) "truncated tensor header")
    (setv size (get (struct.unpack "<Q" size-bytes) 0))
    (ensure (<= 2 size (* 1024 1024)) "invalid tensor header size")
    (setv header (json.loads (.read handle size))))
  (.pop header "__metadata__" None)
  (ensure (= (set header) #{"value"}) "unexpected shard tensor keys")
  (setv value (get header "value") bytes (* (math.prod shape) (if (= expected-dtype "F32") 4 2)))
  (ensure (= (get value "shape") shape) "tensor shape mismatch")
  (ensure (= (get value "dtype") expected-dtype) "tensor dtype mismatch")
  (ensure (= (get value "data_offsets") [0 bytes]) "tensor offsets mismatch")
  (ensure (= (. (path.stat) st_size) (+ 8 size bytes)) "tensor size mismatch"))

(defn inspect [root cfg provenance options evaluation]
  (setv root (pathlib.Path root))
  (ensure (and (.is_dir root) (not (.is_symlink root))
                (not (.endswith root.name ".partial"))) "incomplete checkpoint directory")
  (setv meta-path (/ root "state.json") complete (/ root "COMPLETE"))
  (ensure (and (.is_file complete) (not (.is_symlink complete))
                (.is_file meta-path) (not (.is_symlink meta-path))) "missing completion seal")
  (ensure (and (<= (. (complete.stat) st_size) 128)
               (<= (. (meta-path.stat) st_size) (* 16 1024 1024))) "oversized metadata")
  (ensure (= (.strip (.read_text complete)) (digest meta-path)) "metadata seal mismatch")
  (setv meta (json.loads (.read_text meta-path)) shapes (m.shapes cfg))
  (ensure (= (get meta "schema") SCHEMA) "checkpoint schema mismatch")
  (ensure (= (get meta "config") cfg) "model config mismatch")
  (ensure (= (get meta "data_manifest_sha256") provenance) "corpus mismatch")
  (ensure (= (get meta "training_options") options) "training options mismatch")
  (ensure (= (get meta "evaluation_options") evaluation) "evaluation options mismatch")
  (ensure (and (= (get meta "world_size") 1) (= (get meta "rank") 0)
                (= (get meta "optimizer") "sgd")) "rank/optimizer mismatch")
  (ensure (= (get meta "source_sha256") (source-fingerprint)) "source digest mismatch")
  (ensure (= (get meta "runtime_versions") (runtime-fingerprint)) "runtime version mismatch")
  (ensure (= (get meta "parameter_order") (list shapes)) "parameter order mismatch")
  (setv step (get meta "step") tokens (get meta "tokens_seen"))
  (ensure (and (= (type step) int) (>= step 0) (= (type tokens) int)
                (= tokens (* step (get options "batch") (get options "seq")))) "step/token mismatch")
  (ensure (and (= (len (get meta "mlx_rng_state")) 1)
                (= (len (get (get meta "mlx_rng_state") 0)) 2)) "model RNG state mismatch")
  (setv expected (set (gfor kind ["weights" "master"] key shapes
                          (+ kind "/" key ".safetensors"))))
  (ensure (= (set (get meta "files")) expected) "checkpoint component set mismatch")
  (setv actual (set))
  (for [path (.rglob root "*")]
    (ensure (not (.is_symlink path)) "symlink checkpoint component")
    (when (.is_file path) (.add actual (str (.relative_to path root)))))
  (ensure (= actual (| expected #{"state.json" "COMPLETE"})) "unexpected/missing checkpoint file")
  (for [[relative entry] (.items (get meta "files"))]
    (setv path (/ root relative) [kind filename] (.split relative "/")
          key (get filename (slice None (- (len ".safetensors"))))
          shape (get shapes key) dtype (if (= kind "master") "F32"
                  (if (= (get options "dtype") "float32") "F32" "BF16")))
    (ensure (and (= (get entry "kind") kind) (= (get entry "key") key)
                  (= (get entry "shape") shape) (= (get entry "dtype") dtype)) "component metadata mismatch")
    (ensure (= (. (path.stat) st_size) (get entry "file_bytes")) "component size mismatch")
    (ensure (= (digest path) (get entry "sha256")) "component digest mismatch")
    (inspect-header path dtype shape))
  meta)

(defn load [root cfg provenance options evaluation parent]
  (setv started (time.monotonic) meta (inspect root cfg provenance options evaluation)
        root (pathlib.Path root) p {} store (MasterStore parent))
  (for [key (get meta "parameter_order")]
    (setv value (get (mx.load (str (/ root "weights" (+ key ".safetensors")))) "value"))
    (mx.eval value)
    (setv (get p key) value)
    (.restore store key (/ root "master" (+ key ".safetensors"))))
  (setv mx.random.state (lfor key (get meta "mlx_rng_state") (mx.array key :dtype mx.uint32)))
  (mx.eval mx.random.state)
  (setv (get meta "load_seconds") (- (time.monotonic) started))
  #(p {"master" store} meta))
