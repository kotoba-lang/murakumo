"""Contiguous layer ownership and explicit boundary VJPs, including shared state.

No model weights travel with activations. Embedding/head and final norm have
one logical owner (rank0); embedding gradients from both uses are summed.
"""
(import hashlib math)
(import mlx.core :as mx)
(import model :as m)

(setv FLOATS ["x" "source" "main_k" "main_v" "index_k" "total_index" "total_balance"])

(defn owner [key cfg world]
  ;; Rank0 owns tied embedding/head only; ranks1..world-1 own contiguous layers.
  (if (.startswith key "blocks.")
    (if (= world 1) 0
      (+ 1 (min (- world 2) (// (* (int (get (.split key ".") 1)) (- world 1)) (get cfg "layers"))))) 0))

(defn bounds [cfg rank world]
  (if (and (> world 1) (= rank 0)) #(0 0)
    (do
      (setv layers (lfor i (range (get cfg "layers"))
                        :if (= (owner (+ "blocks." (str i) ".q") cfg world) rank) i))
      #((min layers) (+ 1 (max layers))))))

(defn initialize [cfg rank world seed dtype]
  (setv p {})
  (for [[key shape] (.items (m.shapes cfg))]
    (when (= (owner key cfg world) rank)
      ;; Per-key RNG makes the same global initial model independent of layout.
      (setv key-seed (int (.hexdigest (hashlib.sha256 (.encode (+ (str seed) ":" key)))) 16))
      (mx.random.seed (% key-seed (** 2 32)))
      (setv value (if (in "norm" key) (mx.ones shape :dtype dtype)
                       (* (mx.random.normal shape :dtype dtype)
                          (if (= key "embedding") 0.02 (/ 1.0 (math.sqrt (get shape -2)))))))
      (mx.eval value)
      (setv (get p key) value)))
  p)

(defn initial-state [x cfg]
  (setv batch (get x.shape 0) seq (get x.shape 1) hd (get cfg "head_dim"))
  #({"x" x "source" (mx.zeros [batch 0 (get cfg "width")] :dtype x.dtype)
     "main_k" (mx.zeros [batch 0 hd] :dtype x.dtype)
     "main_v" (mx.zeros [batch 0 hd] :dtype x.dtype)
     "index_k" (mx.zeros [batch 0 (get cfg "index_dim")] :dtype x.dtype)
     "total_index" (mx.array 0.0) "total_balance" (mx.array 0.0)}
    {"main_end" (mx.zeros [0] :dtype mx.int32)
     "selection" (mx.zeros [batch seq 0] :dtype mx.int32)}))

(defn stage [p state discrete cfg start end]
  (setv x (get state "x") batch (get x.shape 0) seq (get x.shape 1)
        source (when (. (get state "source") size) (get state "source"))
        main-k (when (. (get state "main_k") size) (get state "main_k"))
        main-v (when (. (get state "main_v") size) (get state "main_v"))
        index-k (when (. (get state "index_k") size) (get state "index_k"))
        main-end (when (. (get discrete "main_end") size) (get discrete "main_end"))
        selection (when (. (get discrete "selection") size) (get discrete "selection"))
        total-index (get state "total_index") total-balance (get state "total_balance")
        half (// (get cfg "layers") 2) modes (m.modes cfg))
  (for [i (range start end)]
    (when (= i half)
      (setv source x main-k None main-v None index-k None main-end None selection None))
    (setv prefix (+ "blocks." (str i) ".")
          local (dfor [key value] (.items p) :if (.startswith key prefix) key value)
          [x main-k main-v main-end index-k selection il bl _]
          (m.block local x source main-k main-v main-end index-k selection cfg i (get modes i)))
    (+= total-index il) (+= total-balance bl))
  (setv [empty floats-discrete] (initial-state x cfg)
        values {"x" x "source" source "main_k" main-k "main_v" main-v "index_k" index-k
                "total_index" total-index "total_balance" total-balance})
  #((dfor key FLOATS key (if (is (get values key) None) (get empty key) (get values key)))
    {"main_end" (if (is main-end None) (get floats-discrete "main_end") main-end)
     "selection" (if (is selection None) (get floats-discrete "selection") selection)}))

(defn head [p state targets cfg [aux True]]
  (setv logits (@ (m.norm (get state "x") (get p "final_norm"))
                  (.transpose (get p "embedding") [1 0]))
        flat (.reshape (.astype logits mx.float32) [-1 (get cfg "vocab")])
        target (.reshape targets [-1])
        selected (mx.squeeze (mx.take-along-axis flat (mx.expand_dims target -1) :axis -1) :axis -1)
        ce (mx.mean (- (mx.logsumexp flat :axis -1) selected)))
  (if aux (+ ce (* (get cfg "index_loss_weight") (get state "total_index"))
                 (* (get cfg "balance_loss_weight") (get state "total_balance"))) ce))

(defn stage-vjp [p state discrete output-state cotangents cfg start end]
  (setv keys (list p) ins (lfor key FLOATS :if (. (get state key) size) key)
        outs (lfor key FLOATS :if (. (get output-state key) size) key)
        count (len keys))
  (defn fun [#* args]
    (setv params (dict (zip keys (cut args 0 count))) floats (dict state))
    (for [[key value] (zip ins (cut args count None))] (setv (get floats key) value))
    (setv [result _] (stage params floats discrete cfg start end))
    (lfor key outs (get result key)))
  (setv [_ gradients] (mx.vjp fun (+ (lfor key keys (get p key)) (lfor key ins (get state key)))
                                (lfor key outs (get cotangents key)))
        params (dict (zip keys (cut gradients 0 count)))
        inputs (dfor key FLOATS key (mx.zeros-like (get state key))))
  (for [[key value] (zip ins (cut gradients count None))] (setv (get inputs key) value))
  (mx.eval params inputs)
  #(params inputs))

(defn head-vjp [p state targets cfg]
  (setv keys ["embedding" "final_norm"] ins (lfor key FLOATS :if (. (get state key) size) key))
  (defn fun [#* args]
    (setv params (dict (zip keys (cut args 0 2))) floats (dict state))
    (for [[key value] (zip ins (cut args 2 None))] (setv (get floats key) value))
    (head params floats targets cfg))
  (setv [values gradients] (mx.vjp fun (+ (lfor key keys (get p key)) (lfor key ins (get state key)))
                                  [(mx.array 1.0)])
        params (dict (zip keys (cut gradients 0 2)))
        inputs (dfor key FLOATS key (mx.zeros-like (get state key))))
  (for [[key value] (zip ins (cut gradients 2 None))] (setv (get inputs key) value))
  (mx.eval values params inputs)
  #((float (get values 0)) params inputs))

(defn embedding-vjp [p tokens cotangent]
  (setv [_ gradients] (mx.vjp (fn [embedding] (mx.take embedding tokens :axis 0))
                              [(get p "embedding")] [cotangent]))
  (mx.eval gradients)
  (get gradients 0))

(defn local-pipeline [p tokens targets cfg world]
  "Small full-model oracle only: no actual full3B model is allowed here."
  (when (> (get (m.parameter-report cfg) "total_parameters") 10000000)
    (raise (ValueError "local reference is limited to the small control")))
  (setv [state discrete] (initial-state (mx.take (get p "embedding") tokens :axis 0) cfg)
        stages [])
  (for [rank (range world)]
    (setv [start end] (bounds cfg rank world)
          local (dfor [key value] (.items p) :if (and (.startswith key "blocks.")
                                                     (= (owner key cfg world) rank)) key value)
          [output out-discrete] (stage local state discrete cfg start end))
    (mx.eval output out-discrete)
    (.append stages #(local state discrete output start end))
    (setv state output discrete out-discrete))
  (setv [value gradients cotangent] (head-vjp p state targets cfg))
  (for [[local inputs ints outputs start end] (reversed stages)]
    (setv [part cotangent] (stage-vjp local inputs ints outputs cotangent cfg start end))
    (.update gradients part))
  (setv (get gradients "embedding") (+ (get gradients "embedding")
                                      (embedding-vjp p tokens (get cotangent "x"))))
  (mx.eval gradients)
  #(value gradients))
