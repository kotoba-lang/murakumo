;; murakumo.overlay.stream — deterministic stream/session framing.

(ns murakumo.overlay.stream
  (:require [murakumo.identity :as identity]))

(def default-window-size 64)

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
   {:type "murakumo.overlay.stream"
    :id (stream-id session service opened-at)
    :overlay (:overlay session)
    :node (:node session)
    :name (:name session)
    :principal (:principal session)
    :service service
    :opened-at opened-at
    :next-seq 0
    :window default-window-size
    :closed? false}))

(defn frame
  "Build one ordered stream frame."
  [stream payload]
  {:type "murakumo.overlay.stream-frame"
   :stream (:id stream)
   :overlay (:overlay stream)
   :node (:node stream)
   :name (:name stream)
   :service (:service stream)
   :seq (:next-seq stream)
   :payload payload})

(defn advance [stream]
  (update stream :next-seq inc))

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
  {:type "murakumo.overlay.stream-ack"
   :stream (:stream frame)
   :seq (:seq frame)
   :accepted? (boolean accepted?)})

(defn close [stream reason]
  (assoc stream :closed? true :close-reason reason))
