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

(defn cmd-nodes [_]
  (let [f (fleet/enrich (fleet/load-fleet))]
    (doseq [n (:nodes f)
            :let [cks (try (checkpoints (:host n)) (catch Exception _ nil))]]
      (println (format "%-10s %s" (:name n) (or (seq cks) "-"))))))

(defn cmd-generate [[node-name prompt negative size steps]]
  (let [cfg (edn/read-string (slurp "infer.edn"))
        f (fleet/enrich (fleet/load-fleet))
        node (or (first (filter #(= node-name (:name %)) (:nodes f)))
                 (do (println "unknown node" node-name) (System/exit 1)))
        host (:host node)
        ckpt (or (first (checkpoints host))
                 (do (println "no checkpoint on" node-name) (System/exit 1)))
        model (or (first (filter #(= ckpt (get % :model/gguf (:model/checkpoint %)))
                                 (vals (:models cfg))))
                  {:model/id ckpt :credit/per-image 100})
        [w h] (if size (map parse-long (str/split size #"x")) [832 1216])
        wf (txt2img-workflow {:ckpt ckpt :prompt prompt :negative negative
                              :width w :height h
                              :steps (or (some-> steps parse-long) 24)})
        t0 (System/currentTimeMillis)
        pid (submit! host wf)
        _ (println (format "[%s] %s → prompt %s, sampling…" node-name ckpt pid))
        hist (await-history host pid 600)
        ms (- (System/currentTimeMillis) t0)
        img (get-in hist [:outputs :7 :images 0])
        remote (format "comfyui/output/%s" (:filename img))
        local (str "murakumo-" (:filename img))]
    (p/sh "scp" "-o" "BatchMode=yes" (str host ":" remote) local)
    (let [settled (settle! node model {:images 1} ms)]
      (println (format "image → %s  (%.1fs on %s)" local (/ ms 1000.0) node-name))
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
