;; murakumo.infer.postproc — media post-processing work the browser swarm does.
;;
;; When a native node generates an image, the useful embarrassingly-parallel
;; follow-on work — feature extraction for the gallery index (luminance,
;; histogram, a perceptual-ish signature for dedup) — can flow to the browser/
;; wasm swarm over the relay. It is exactly the tier of work join.cljc marks
;; browser-eligible: no large model resident, pure per-pixel reduction, WebGPU
;; (WGSL) or a JS fallback. This namespace is the PURE reference reduction, so
;; the WGSL kernel's output can be VERIFIED against it byte-for-byte.

(ns murakumo.infer.postproc)

(def ^:const bins 16)

(defn luma
  "Rec.601 luminance of an [r g b] pixel (0–255) → 0–255 double."
  [[r g b]]
  (+ (* 0.299 r) (* 0.587 g) (* 0.114 b)))

(defn features
  "Reduce a seq of [r g b] pixels to gallery-index features:
   {:n :mean-luma :histogram [16 bins] :signature <16-hex>}. The signature is a
   coarse luminance-histogram fingerprint — cheap dedup / near-dup detection.
   PURE: this is the contract the WGSL kernel must reproduce."
  [pixels]
  (let [n (count pixels)
        lumas (map luma pixels)
        sum (reduce + lumas)
        hist (reduce (fn [h l]
                       (update h (min (dec bins) (int (/ l (/ 256.0 bins)))) inc))
                     (vec (repeat bins 0)) lumas)
        ;; signature: each bin → 1 if above the mean-per-bin, packed to hex
        mean-bin (if (pos? n) (/ n bins) 0)
        bits (map #(if (> % mean-bin) 1 0) hist)
        sig (reduce (fn [acc [i b]] (if (pos? b) (bit-or acc (bit-shift-left 1 i)) acc))
                    0 (map-indexed vector bits))]
    {:n n
     :mean-luma (if (pos? n) (/ sum n) 0.0)
     :histogram hist
     :signature (format "%04x" sig)}))

(defn wgsl-reduce-kernel
  "The WGSL compute shader the browser worker runs — a workgroup reduction over
   an RGBA input buffer producing per-bin luminance counts. Kept here (as data)
   so it ships with the contract it satisfies. Host binds input/output buffers."
  []
  "@group(0) @binding(0) var<storage,read> pixels: array<u32>;   // packed rgba8
@group(0) @binding(1) var<storage,read_write> hist: array<atomic<u32>,16>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= arrayLength(&pixels)) { return; }
  let p = pixels[i];
  let r = f32(p & 0xffu);
  let g = f32((p >> 8u) & 0xffu);
  let b = f32((p >> 16u) & 0xffu);
  let l = 0.299*r + 0.587*g + 0.114*b;
  let bin = min(15u, u32(l / 16.0));
  atomicAdd(&hist[bin], 1u);
}")

(defn verify
  "Does a worker's returned histogram match the pure reference for these pixels?
   The relay/dispatcher uses this to reject a bogus result before crediting —
   the seed of proof-of-compute (a fraction of jobs are re-verified)."
  [pixels claimed-histogram]
  (= (:histogram (features pixels)) (vec claimed-histogram)))
