;; murakumo.overlay.keyring — deterministic key rotation metadata.
;;
;; W6 product-shell: rotation seconds/epoch + hash preimages via kotoba
;; overlay_keyring_core when oracle loadable (JVM or cljs/nbb).
;; SHA-256 stays host (identity/sha256-hex).

(ns murakumo.overlay.keyring
  (:require [murakumo.identity :as identity]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-keyring)

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

(def default-rotation-seconds
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid 'default-rotation-seconds []))
      86400)
    (catch #?(:clj Exception :cljs :default) _
      86400)))

(defn epoch
  ([seconds] (epoch seconds default-rotation-seconds))
  ([seconds rotation-seconds]
   (try-oracle
    #(oracle/i64->host
      (o 'epoch [(oracle/as-i64 seconds) (oracle/as-i64 rotation-seconds)]))
    #(quot seconds rotation-seconds))))

(defn key-id [overlay epoch]
  (subs (identity/sha256-hex
         (try-oracle
          #(o 'key-id-input [(str overlay) (oracle/as-i64 epoch)])
          #(str overlay ":key:" epoch)))
        0 16))

(defn derive-key
  "Derive per-overlay, per-epoch frame auth material."
  [operator-seed overlay epoch]
  {:type "murakumo.overlay.key"
   :overlay overlay
   :epoch epoch
   :kid (key-id overlay epoch)
   :alg :sha256-aes-gcm
   :key (identity/sha256-hex
         (try-oracle
          #(o 'derive-key-input
              [(str operator-seed) (str overlay) (oracle/as-i64 epoch)])
          #(str operator-seed ":" overlay ":murakumo-overlay-key:" epoch)))})

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
