;; murakumo.overlay.keyring — deterministic key rotation metadata.

(ns murakumo.overlay.keyring
  (:require [murakumo.identity :as identity]))

(def default-rotation-seconds 86400)

(defn epoch
  ([seconds] (epoch seconds default-rotation-seconds))
  ([seconds rotation-seconds]
   (quot seconds rotation-seconds)))

(defn key-id [overlay epoch]
  (subs (identity/sha256-hex (str overlay ":key:" epoch)) 0 16))

(defn derive-key
  "Derive per-overlay, per-epoch frame auth material."
  [operator-seed overlay epoch]
  {:type "murakumo.overlay.key"
   :overlay overlay
   :epoch epoch
   :kid (key-id overlay epoch)
   :alg :sha256-aes-gcm
   :key (identity/sha256-hex
         (str operator-seed ":" overlay ":murakumo-overlay-key:" epoch))})

(defn rotation-plan
  ([operator-seed overlay now-seconds]
   (rotation-plan operator-seed overlay now-seconds default-rotation-seconds))
  ([operator-seed overlay now-seconds rotation-seconds]
   (let [current (epoch now-seconds rotation-seconds)]
     {:type "murakumo.overlay.key-rotation"
      :overlay overlay
      :rotation-seconds rotation-seconds
      :current (derive-key operator-seed overlay current)
      :previous (when (pos? current)
                  (derive-key operator-seed overlay (dec current)))
      :next (derive-key operator-seed overlay (inc current))})))

(defn active-key [rotation]
  (get-in rotation [:current :key]))

(defn accepted-kids [rotation]
  (vec (keep #(get-in rotation [% :kid]) [:previous :current :next])))
