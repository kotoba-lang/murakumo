;; murakumo.overlay.stream — deterministic stream/session framing.
;;
;; W6 product-shell (ADR-260728-w6-stream-type-tokens-pure-oracle):
;; window size + advance-seq + ack-accepted? + type tokens via kotoba
;; overlay_stream_core when oracle loadable (JVM or cljs/nbb).
;; stream-id hashing and frame maps stay host.

(ns murakumo.overlay.stream
  (:require [murakumo.identity :as identity]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-stream)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn- oracle-str-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(defn- oracle-i64-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid export []))
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

;; ── residual type tokens + scalars ───────────────────────────────────

(def ^:private mirror-default-window-size 64)
(def ^:private mirror-initial-next-seq 0)
(def ^:private mirror-type-stream "murakumo.overlay.stream")
(def ^:private mirror-type-frame "murakumo.overlay.stream-frame")
(def ^:private mirror-type-ack "murakumo.overlay.stream-ack")

(def default-window-size
  "Stream receive window size. Kotoba when ready."
  (oracle-i64-const 'default-window-size mirror-default-window-size))

(def initial-next-seq
  "Initial :next-seq for open-stream. Kotoba when ready."
  (oracle-i64-const 'initial-next-seq mirror-initial-next-seq))

(def type-stream
  (oracle-str-const 'type-stream mirror-type-stream))

(def type-frame
  (oracle-str-const 'type-frame mirror-type-frame))

(def type-ack
  (oracle-str-const 'type-ack mirror-type-ack))

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
            (try-oracle
             #(oracle/i64->host (o 'advance-seq [(oracle/as-i64 s)]))
             #(inc s)))))

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
   :accepted? (try-oracle
               #(= 1 (oracle/i64->host
                      (o 'ack-accepted? [(oracle/as-i64 (if accepted? 1 0))])))
               #(boolean accepted?))})

(defn close [stream reason]
  (assoc stream :closed? true :close-reason reason))
