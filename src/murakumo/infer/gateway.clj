;; murakumo.infer.gateway — OpenAI-images-compatible HTTP front for the
;; fleet's resident ComfyUI (murakumo.infer.media).
;;
;; kotoba-lang/comfyui's comfyui.gateway/render-via-gateway (the host-fn every
;; comfyui-clj-based generation graph calls) expects `POST {base}/v1/images/
;; generations` -> `{:data [{:b64_json "..."}]}`. Nothing implements that
;; contract for the murakumo fleet today — the fleet's ComfyUI ports are
;; deliberately unexposed (SSH-loopback only, per media.clj's header comment),
;; so no COMFY_URL anywhere in the ecosystem (cloud-murakumo, ai-gftd-yukkuri)
;; can reach it. This is that bridge: one process, run on a machine with SSH
;; access to the fleet, that translates the gateway's HTTP contract into
;; murakumo.infer.media/run-job! (job-parallel dispatch + poll + scp already
;; proven by `bb murakumo infer media generate`).
;;
;;   bb murakumo infer gateway [port=8790]
;;   POST /v1/images/generations  {:prompt :n :size "WxH" :model? :seed?}
;;                                 -> {:data [{:b64_json ...}]}
;;   GET  /health                  -> {:ok :fleet-nodes}
;;
;; Scope: image (txt2img) only, matching comfyui.gateway's KSampler contract.
;; Blocking (one request = one full render, per media.clj's run-job!) — fine
;; for the current job-parallel/render-farm model; a queue-backed async
;; variant (poll-by-id) is a follow-up if request-time synchronous rendering
;; becomes a problem. Not deployed/exposed by this commit — running it
;; reachable from outside localhost (Cloudflare Tunnel, Tailscale funnel, or
;; a plain --bind) is an operational step the fleet owner takes separately,
;; same as renderer-mac's README documents for its own tunnel.
(ns murakumo.infer.gateway
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [murakumo.fleet :as fleet]
            [murakumo.infer.media :as media]
            [murakumo.infer.schedule :as sched]
            [org.httpkit.server :as http]))

(defn- parse-size [size]
  (if-let [[_ w h] (and size (re-matches #"(\d+)x(\d+)" size))]
    [(parse-long w) (parse-long h)]
    [832 1216]))

(defn- image-filename
  "The SaveImage output filename from a run-job! history (:image kind always
  lands on node :7, per media.clj's `run-job!` case)."
  [hist]
  (get-in hist [:outputs :7 :images 0 :filename]))

(defn generate-image!
  "{:prompt :negative :n :size :model :seed} -> {:data [{:b64_json ...}]}.
  Picks a fleet node via murakumo.infer.schedule/pick (any node serving
  :comfyui; prefers one already holding the requested checkpoint, else any
  eligible node — media.clj's run-job! resolves a default checkpoint when
  :model is absent), runs ONE image job (blocking), reads the scp'd file,
  and returns it base64-encoded. Throws ex-info if no fleet node is eligible
  or the render/scp produced no file."
  [{:keys [prompt negative n size model seed]}]
  (let [[width height] (parse-size size)
        f (fleet/enrich (fleet/load-fleet))
        live (media/live-fleet f)
        wanted {:model/engine :comfyui :model/checkpoint model}
        node-info (or (sched/pick live wanted)
                      (throw (ex-info "no eligible murakumo fleet node for :comfyui" {:live-count (count live)})))
        node (or (first (filter #(= (:name node-info) (:name %)) (:nodes f)))
                 (throw (ex-info "picked node not found in fleet.edn" {:node (:name node-info)})))
        hist (media/run-job! node :image (cond-> {:prompt prompt :width width :height height}
                                           negative (assoc :negative negative)
                                           model (assoc :ckpt model)
                                           seed (assoc :seed seed)))
        filename (image-filename hist)]
    (when-not filename
      (throw (ex-info "murakumo render produced no output file" {:history hist})))
    (let [local (str "murakumo-" filename)
          bytes (.readAllBytes (io/input-stream local))]
      {:data [{:b64_json (.encodeToString (java.util.Base64/getEncoder) bytes)}]
       :murakumo {:node (:name node-info) :width width :height height :n (or n 1)}})))

(defn- json-response [status body]
  {:status status :headers {"content-type" "application/json"} :body (json/generate-string body)})

(defn- http-handler [req]
  (case [(:request-method req) (:uri req)]
    [:post "/v1/images/generations"]
    (try
      (let [body (json/parse-string (slurp (:body req)) true)]
        (json-response 200 (generate-image! body)))
      (catch Exception e
        (json-response 502 {:error {:message (ex-message e) :data (ex-data e)}})))

    [:get "/health"]
    (try
      (let [f (fleet/enrich (fleet/load-fleet))]
        (json-response 200 {:ok true :fleet-nodes (count (media/live-fleet f))}))
      (catch Exception e
        (json-response 503 {:ok false :error (ex-message e)})))

    (json-response 404 {:error "not_found"})))

(defn -main [& [port]]
  (let [port (or (some-> port parse-long) 8790)]
    (http/run-server http-handler {:port port})
    (println (format "murakumo comfyui gateway on http://0.0.0.0:%d  (POST /v1/images/generations, GET /health)" port))
    @(promise)))
