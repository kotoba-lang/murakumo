;; murakumo.infer.media — media generation over the fleet's resident ComfyUI.
;;
;; Image/video/audio jobs are the media-first face of murakumo inference
;; (ADR-2607030030 amendment): each job runs WHOLE on one node — job-parallel,
;; no layer sharding, no RPC pipeline — so the fleet is a render farm and the
;; economics are Civitai-Buzz-shaped (credits per image/second), settled into
;; the same memory×time ledger as text inference.
;;
;; The workflow wire format is ComfyUI's API JSON — the same shape
;; comfyui-clj (kotoba-lang/comfyui) models as portable cljc data, so this
;; bb operator can later hand graphs to that engine unchanged.
;;
;;   bb murakumo infer media nodes                  which nodes serve which checkpoints
;;   bb murakumo infer media generate <node> "<prompt>" [neg] [WxH] [steps]
;;
;; Fleet ports stay unexposed: dispatch + polling ride ssh loopback curls,
;; the image comes home over scp.

(ns murakumo.infer.media
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [murakumo.fleet :as fleet]
            [murakumo.infer.credits :as credits]
            [murakumo.infer.schedule :as sched]
            [murakumo.ssh :as ssh]))

(def ^:private comfy-port 8188)
(def ^:private runs-file ".murakumo-infer-runs.edn")  ; local settled-run ledger

(defn- comfy [host path]
  (let [out (ssh/curl-local host (str "http://localhost:" comfy-port path))]
    (when-not (str/blank? out)
      (json/parse-string out true))))

(defn- checkpoints [host]
  (some-> (comfy host "/object_info/CheckpointLoaderSimple")
          :CheckpointLoaderSimple :input :required :ckpt_name first))

(defn txt2img-workflow
  "ComfyUI API-format graph as data (checkpoint → clip± → ksampler → vae →
   save). Deterministic given :seed — reproducibility is what makes the run
   receipt-able."
  [{:keys [ckpt prompt negative width height steps cfg seed]
    :or {negative "lowres, bad anatomy, watermark" width 832 height 1216
         steps 24 cfg 6.0 seed 20260703}}]
  {"1" {:class_type "CheckpointLoaderSimple" :inputs {:ckpt_name ckpt}}
   "2" {:class_type "CLIPTextEncode" :inputs {:clip ["1" 1] :text prompt}}
   "3" {:class_type "CLIPTextEncode" :inputs {:clip ["1" 1] :text negative}}
   "4" {:class_type "EmptyLatentImage" :inputs {:width width :height height :batch_size 1}}
   "5" {:class_type "KSampler" :inputs {:model ["1" 0] :positive ["2" 0] :negative ["3" 0]
                                        :latent_image ["4" 0] :seed seed :steps steps
                                        :cfg cfg :sampler_name "euler_ancestral"
                                        :scheduler "normal" :denoise 1.0}}
   "6" {:class_type "VAEDecode" :inputs {:samples ["5" 0] :vae ["1" 2]}}
   "7" {:class_type "SaveImage" :inputs {:images ["6" 0] :filename_prefix "murakumo"}}})

(defn i2v-workflow
  "Image-to-video (Wan/LTX-style) ComfyUI graph as data: load an i2v diffusion
   model, encode the driving image + prompt, sample N frames, decode, save as an
   animated webp. Frame count = fps×seconds (per-second billing lines up)."
  [{:keys [ckpt image prompt width height seconds fps steps seed]
    :or {width 640 height 640 seconds 3 fps 16 steps 20 seed 20260703}}]
  ;; SVD is image-only conditioning: the driving image + clip-vision carry the
  ;; signal; there is no text-prompt node (`prompt` kept for API symmetry/logs).
  (let [frames (int (* fps seconds))
        _ prompt]
    {"1" {:class_type "ImageOnlyCheckpointLoader" :inputs {:ckpt_name ckpt}}
     "2" {:class_type "LoadImage" :inputs {:image image}}
     "4" {:class_type "SVD_img2vid_Conditioning"
          :inputs {:clip_vision ["1" 1] :init_image ["2" 0] :vae ["1" 2]
                   :width width :height height :video_frames frames
                   :motion_bucket_id 127 :fps fps :augmentation_level 0.0}}
     "5" {:class_type "KSampler" :inputs {:model ["1" 0] :positive ["4" 0] :negative ["4" 1]
                                          :latent_image ["4" 2] :seed seed :steps steps
                                          :cfg 3.0 :sampler_name "euler" :scheduler "karras"
                                          :denoise 1.0}}
     "6" {:class_type "VAEDecode" :inputs {:samples ["5" 0] :vae ["1" 2]}}
     "7" {:class_type "SaveAnimatedWEBP"
          :inputs {:images ["6" 0] :filename_prefix "murakumo-vid"
                   :fps fps :lossless false :quality 85 :method "default"}}}))

(defn ltxv-t2v-workflow
  "LTX-Video text-to-video (DiT) ComfyUI graph as data. Native ComfyUI LTX
   nodes: EmptyLTXVLatentVideo sizes the clip (length must be 8k+1 frames),
   LTXVConditioning carries the frame_rate. Distilled 2B fits a 16 GB mini with
   headroom — the DiT samples far faster per step than SVD's UNet."
  [{:keys [ckpt clip prompt negative width height seconds fps steps cfg seed]
    :or {clip "t5xxl_fp8_e4m3fn.safetensors"
         negative "worst quality, blurry, jittery, distorted"
         width 704 height 480 seconds 3 fps 24 steps 24 cfg 3.0 seed 20260703}}]
  ;; LTX 2B is diffusion-only: the text encoder (T5-XXL) is loaded SEPARATELY via
  ;; CLIPLoader type=ltxv — the checkpoint's own CLIP output is nil.
  (let [length (inc (* 8 (max 1 (int (/ (* fps seconds) 8)))))]  ; 8k+1 frames
    {"1" {:class_type "CheckpointLoaderSimple" :inputs {:ckpt_name ckpt}}
     "9" {:class_type "CLIPLoader" :inputs {:clip_name clip :type "ltxv"}}
     "2" {:class_type "CLIPTextEncode" :inputs {:clip ["9" 0] :text prompt}}
     "3" {:class_type "CLIPTextEncode" :inputs {:clip ["9" 0] :text negative}}
     "4" {:class_type "EmptyLTXVLatentVideo" :inputs {:width width :height height
                                                      :length length :batch_size 1}}
     "5" {:class_type "LTXVConditioning" :inputs {:positive ["2" 0] :negative ["3" 0]
                                                  :frame_rate fps}}
     "6" {:class_type "KSampler" :inputs {:model ["1" 0] :positive ["5" 0] :negative ["5" 1]
                                          :latent_image ["4" 0] :seed seed :steps steps
                                          :cfg cfg :sampler_name "euler" :scheduler "normal"
                                          :denoise 1.0}}
     "7" {:class_type "VAEDecode" :inputs {:samples ["6" 0] :vae ["1" 2]}}
     "8" {:class_type "SaveAnimatedWEBP"
          :inputs {:images ["7" 0] :filename_prefix "murakumo-ltx"
                   :fps fps :lossless false :quality 85 :method "default"}}}))

(defn txt2audio-workflow
  "Text-to-audio (Stable-Audio / audio-diffusion style) ComfyUI graph as data.
   Duration in seconds → per-audio-second billing."
  [{:keys [ckpt prompt negative seconds steps seed]
    :or {negative "" seconds 10 steps 50 seed 20260703}}]
  {"1" {:class_type "CheckpointLoaderSimple" :inputs {:ckpt_name ckpt}}
   "2" {:class_type "CLIPTextEncode" :inputs {:clip ["1" 1] :text prompt}}
   "3" {:class_type "CLIPTextEncode" :inputs {:clip ["1" 1] :text negative}}
   "4" {:class_type "EmptyLatentAudio" :inputs {:seconds seconds}}
   "5" {:class_type "KSampler" :inputs {:model ["1" 0] :positive ["2" 0] :negative ["3" 0]
                                        :latent_image ["4" 0] :seed seed :steps steps
                                        :cfg 5.0 :sampler_name "dpmpp_3m_sde_gpu"
                                        :scheduler "exponential" :denoise 1.0}}
   "6" {:class_type "VAEDecodeAudio" :inputs {:samples ["5" 0] :vae ["1" 2]}}
   "7" {:class_type "SaveAudio" :inputs {:audio ["6" 0] :filename_prefix "murakumo-aud"}}})

(defn- submit! [host workflow]
  (let [body (json/generate-string {:prompt workflow})
        out (:out (ssh/sh host (format "curl -s -m 30 -X POST http://localhost:%d/prompt -H 'Content-Type: application/json' -d %s"
                                       comfy-port (pr-str body))))]
    (:prompt_id (json/parse-string out true))))

(defn- await-history [host prompt-id timeout-s]
  (loop [waited 0]
    (when (> waited timeout-s)
      (throw (ex-info "generation timed out" {:host host :prompt-id prompt-id})))
    (let [h (get (comfy host (str "/history/" prompt-id)) (keyword prompt-id))]
      (if (and h (get-in h [:status :completed]))
        h
        (do (Thread/sleep 3000) (recur (+ waited 3)))))))

(defn- settle! [node model units duration-ms]
  (let [run {:model model
             :units units
             :duration-ms duration-ms
             :plan {:assignments [{:node node :span 1
                                   :est-bytes (:mem-bytes node 17179869184)}]}}
        settled (assoc (credits/settle run)
                       :model (:model/id model) :units units :duration-ms duration-ms)
        ledger (or (try (edn/read-string (slurp runs-file)) (catch Exception _ nil)) [])]
    (spit runs-file (pr-str (conj ledger settled)))
    settled))

(defn- live-node
  "Probe one fleet node into the shape murakumo.infer.schedule expects:
   which engines it serves, which checkpoints it holds, free RAM, queue depth."
  [n]
  (let [host (:host n)
        stats (comfy host "/system_stats")
        cks (set (try (checkpoints host) (catch Exception _ nil)))
        queue (or (some-> (comfy host "/queue") :queue_running count) 0)]
    (when stats
      {:name (:name n) :host host
       :engines #{:comfyui}
       :checkpoints cks
       :free-bytes (get-in stats [:system :ram_free] 0)
       :queue queue})))

(defn live-fleet [f]
  (->> (:nodes f) (pmap live-node) (filter some?) vec))

(defn run-job!
  "Render ONE media job on `node` and return its ComfyUI history. `kind` ∈
   #{:image :video :audio}; `opts` are the workflow params. Blocking. Fetches
   the artifact home over scp. Pure of the ledger — the caller settles."
  [node kind opts]
  (let [host (:host node)
        ;; :ckpt from opts (scheduler resolves the model's checkpoint) or first
        ckpt (or (:ckpt opts) (first (checkpoints host)))
        [wf out-node] (case kind
                        :image [(txt2img-workflow (assoc opts :ckpt ckpt)) :7]
                        :video [(i2v-workflow (assoc opts :ckpt ckpt :image (:image opts "seed.png"))) :7]
                        :ltx-video [(ltxv-t2v-workflow (assoc opts :ckpt ckpt)) :8]
                        :audio [(txt2audio-workflow (assoc opts :ckpt ckpt)) :7])
        pid (submit! host wf)
        hist (await-history host pid 900)
        out (get-in hist [:outputs out-node])
        file (or (get-in out [:images 0]) (get-in out [:gifs 0]) (get-in out [:audio 0]))]
    (when file
      (let [remote (format "comfyui/output/%s" (:filename file))
            local (str "murakumo-" (:filename file))]
        (p/sh "scp" "-o" "BatchMode=yes" (str host ":" remote) local)))
    hist))

(defn cmd-nodes [_]
  (let [f (fleet/enrich (fleet/load-fleet))]
    (println (format "%-10s %6s %6s  %s" "NODE" "FREE" "QUEUE" "CHECKPOINTS"))
    (doseq [n (live-fleet f)]
      (println (format "%-10s %5.1fG %6d  %s"
                       (:name n)
                       (/ (double (:free-bytes n)) 1e9)
                       (:queue n)
                       (str/join ", " (:checkpoints n)))))))

(defn- resolve-node
  "Pick the node to run `model`: the named node if given & eligible, else the
   scheduler's choice over the live fleet. Returns the fleet node map."
  [f model node-name]
  (let [live (live-fleet f)]
    (if node-name
      (or (first (filter #(= node-name (:name %)) (:nodes f)))
          (throw (ex-info (str "unknown node " node-name) {})))
      (if-let [chosen (sched/pick live model)]
        (first (filter #(= (:name chosen) (:name %)) (:nodes f)))
        (throw (ex-info "no eligible node for this model" {:model (:model/id model)}))))))

(defn cmd-generate [[node-name prompt negative size steps]]
  (let [cfg (edn/read-string (slurp "infer.edn"))
        f (fleet/enrich (fleet/load-fleet))
        img-model (or (first (filter #(= :image (:model/kind %)) (vals (:models cfg))))
                      {:model/id "animagine-xl-4.0" :model/engine :comfyui :credit/per-image 100})
        ;; when no node named, the scheduler routes the job (render-farm mode)
        node (resolve-node f img-model (when (and node-name (not= node-name "-")) node-name))
        host (:host node)
        ckpt (or (first (checkpoints host))
                 (do (println "no checkpoint on" (:name node)) (System/exit 1)))
        model (or (first (filter #(= ckpt (get % :model/gguf (:model/checkpoint %)))
                                 (vals (:models cfg))))
                  {:model/id ckpt :credit/per-image 100})
        [w h] (if size (map parse-long (str/split size #"x")) [832 1216])
        wf (txt2img-workflow {:ckpt ckpt :prompt prompt :negative negative
                              :width w :height h
                              :steps (or (some-> steps parse-long) 24)})
        t0 (System/currentTimeMillis)
        pid (submit! host wf)
        _ (println (format "[%s] %s → prompt %s, sampling…" (:name node) ckpt pid))
        hist (await-history host pid 600)
        ms (- (System/currentTimeMillis) t0)
        img (get-in hist [:outputs :7 :images 0])
        remote (format "comfyui/output/%s" (:filename img))
        local (str "murakumo-" (:filename img))]
    (p/sh "scp" "-o" "BatchMode=yes" (str host ":" remote) local)
    (let [settled (settle! node model {:images 1} ms)]
      (println (format "image → %s  (%.1fs on %s)" local (/ ms 1000.0) (:name node)))
      (println (format "settled: %.1f credits — %s earns %.1f, treasury %.1f"
                       (:run/total settled)
                       node-name
                       (double (get (:run/shares settled) node-name 0.0))
                       (:run/treasury settled))))))

(defn -main [& [cmd & args]]
  (case cmd
    "nodes" (cmd-nodes args)
    "generate" (cmd-generate args)
    (println "usage: bb murakumo infer media nodes | generate <node> \"<prompt>\" [neg] [WxH] [steps]")))
