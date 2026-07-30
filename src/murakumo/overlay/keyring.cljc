;; murakumo.overlay.keyring — deterministic key rotation metadata.
;;
;; W6 product-shell + T6.4: rotation seconds/epoch + hash preimages + seed/key
;; seps require the shipped `:overlay-keyring` KIR on **every** platform. Host
;; pure mirrors are gone — cljs/nbb must preload shipped KIR (resources/ via
;; nbb cwd, register-kir!, or set-resource-loader!) before requiring this ns
;; (ADR-260731-w6-t64-keyring-peer-mirror-delete).
;; SHA-256 stays host (identity/sha256-hex). Map assembly stays host.

(ns murakumo.overlay.keyring
  (:require [murakumo.identity :as identity]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-keyring)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

;; ── residual preimage seps + type tokens ─────────────────────────────

(def default-rotation-seconds
  "Rotation window seconds. Kotoba SSoT (requires oracle)."
  (oracle/i64->host (o 'default-rotation-seconds [])))

(def key-id-hex-len
  "Kid hex prefix length. Kotoba SSoT (requires oracle)."
  (oracle/i64->host (o 'key-id-hex-len [])))

(def seed-sep
  (o 'seed-sep []))

(def key-id-mid
  (o 'key-id-mid []))

(def derive-key-mid
  (o 'derive-key-mid []))

(def type-key
  (o 'type-key []))

(def type-rotation
  (o 'type-rotation []))

(defn epoch
  ([seconds] (epoch seconds default-rotation-seconds))
  ([seconds rotation-seconds]
   (oracle/i64->host
    (o 'epoch [(oracle/as-i64 seconds) (oracle/as-i64 rotation-seconds)]))))

(defn key-id [overlay epoch]
  (subs (identity/sha256-hex
         (o 'key-id-input [(str overlay) (oracle/as-i64 epoch)]))
        0 key-id-hex-len))

(defn derive-key
  "Derive per-overlay, per-epoch frame auth material."
  [operator-seed overlay epoch]
  {:type type-key
   :overlay overlay
   :epoch epoch
   :kid (key-id overlay epoch)
   :alg :sha256-aes-gcm
   :key (identity/sha256-hex
         (o 'derive-key-input
            [(str operator-seed) (str overlay) (oracle/as-i64 epoch)]))})

(defn rotation-plan
  ([operator-seed overlay now-seconds]
   (rotation-plan operator-seed overlay now-seconds default-rotation-seconds))
  ([operator-seed overlay now-seconds rotation-seconds]
   (let [current (epoch now-seconds rotation-seconds)]
     {:type type-rotation
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
