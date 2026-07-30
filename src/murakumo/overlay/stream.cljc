;; murakumo.overlay.stream — deterministic stream/session framing.
;;
;; W6 product-shell + T6.4 remainder (oracle-required on JVM):
;; window size + advance-seq + ack-accepted? + type tokens via kotoba
;; overlay_stream_core. JVM requires shipped KIR; cljs keeps mirrors as
;; fail-closed fallback. stream-id hashing and frame maps stay host.

(ns murakumo.overlay.stream
  (:require [murakumo.identity :as identity]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-stream)

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- o
  "Call a pure export. JVM requires the oracle artifact; cljs may fall back."
  [export args]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "overlay-stream oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export args))
     :cljs
     (if (oracle-ready?)
       (try
         (oracle/call oid export args)
         (catch :default _
           ::oracle-failed))
       ::oracle-failed)))

#?(:cljs
   (do
     (def ^:private mirror-default-window-size 64)
     (def ^:private mirror-initial-next-seq 0)
     (def ^:private mirror-type-stream "murakumo.overlay.stream")
     (def ^:private mirror-type-frame "murakumo.overlay.stream-frame")
     (def ^:private mirror-type-ack "murakumo.overlay.stream-ack")

     (defn- cljs-str [export mirror]
       (let [v (o export [])]
         (if (= v ::oracle-failed) mirror v)))

     (defn- cljs-i64 [export mirror]
       (let [v (o export [])]
         (if (= v ::oracle-failed) mirror (oracle/i64->host v))))))

;; ── residual type tokens + scalars ───────────────────────────────────

(def default-window-size
  "Stream receive window size. Kotoba SSoT (JVM requires oracle)."
  #?(:clj (oracle/i64->host (o 'default-window-size []))
     :cljs (cljs-i64 'default-window-size mirror-default-window-size)))

(def initial-next-seq
  "Initial :next-seq for open-stream. Kotoba SSoT (JVM requires oracle)."
  #?(:clj (oracle/i64->host (o 'initial-next-seq []))
     :cljs (cljs-i64 'initial-next-seq mirror-initial-next-seq)))

(def type-stream
  #?(:clj (o 'type-stream [])
     :cljs (cljs-str 'type-stream mirror-type-stream)))

(def type-frame
  #?(:clj (o 'type-frame [])
     :cljs (cljs-str 'type-frame mirror-type-frame)))

(def type-ack
  #?(:clj (o 'type-ack [])
     :cljs (cljs-str 'type-ack mirror-type-ack)))

(defn stream-id
  "Stable stream id for a logical service connection."
  [session service opened-at]
  (identity/sha256-hex
   (pr-str {:overlay (:overlay session)
            :node (:node session)
            :name (:name session)
            :principal (:principal session)
            :service service
            :opened-at opened-at})))

(defn open-stream
  ([session service] (open-stream session service 0))
  ([session service opened-at]
   {:type type-stream
    :id (stream-id session service opened-at)
    :overlay (:overlay session)
    :node (:node session)
    :name (:name session)
    :principal (:principal session)
    :service service
    :opened-at opened-at
    :next-seq initial-next-seq
    :window default-window-size
    :closed? false}))

(defn frame
  "Build one ordered stream frame."
  [stream payload]
  {:type type-frame
   :stream (:id stream)
   :overlay (:overlay stream)
   :node (:node stream)
   :name (:name stream)
   :service (:service stream)
   :seq (:next-seq stream)
   :payload payload})

(defn advance [stream]
  (update stream :next-seq
          (fn [s]
            #?(:clj (oracle/i64->host (o 'advance-seq [(oracle/as-i64 s)]))
               :cljs (let [v (o 'advance-seq [(oracle/as-i64 s)])]
                       (if (= v ::oracle-failed) (inc s) (oracle/i64->host v)))))))

(defn frames
  "Turn payloads into ordered frames and the advanced stream state."
  [stream payloads]
  (reduce (fn [{:keys [stream frames]} payload]
            {:stream (advance stream)
             :frames (conj frames (frame stream payload))})
          {:stream stream :frames []}
          payloads))

(defn ack
  [frame accepted?]
  {:type type-ack
   :stream (:stream frame)
   :seq (:seq frame)
   :accepted? #?(:clj (oracle/bool->host
                       (o 'ack-accepted? [(boolean accepted?)]))
                 :cljs (let [v (o 'ack-accepted? [(boolean accepted?)])]
                         (if (= v ::oracle-failed)
                           (boolean accepted?)
                           (oracle/bool->host v))))})

(defn close [stream reason]
  (assoc stream :closed? true :close-reason reason))
