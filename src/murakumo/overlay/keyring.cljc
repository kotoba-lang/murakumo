;; murakumo.overlay.keyring — deterministic key rotation metadata.
;;
;; W6 product-shell (ADR-260728-w6-keyring-seps-pure-oracle):
;; rotation seconds/epoch + hash preimages + seed/key seps via kotoba
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
  "JVM: require shipped KIR (T6.4). cljs: oracle when ready, else mirror."
  [thunk mirror-thunk]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid})))
       (thunk))
     :cljs
     (if (oracle-ready?)
       (try
         (thunk)
         (catch :default _
           (mirror-thunk)))
       (mirror-thunk))))

(defn- oracle-str-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export []))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/call oid export [])
         mirror)
       (catch :default _
         mirror))))

(defn- oracle-i64-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/i64->host (oracle/call oid export [])))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/i64->host (oracle/call oid export []))
         mirror)
       (catch :default _
         mirror))))

;; ── residual preimage seps + type tokens ─────────────────────────────

(def ^:private mirror-default-rotation-seconds 86400)
(def ^:private mirror-key-id-hex-len 16)
(def ^:private mirror-seed-sep ":")
(def ^:private mirror-key-id-mid ":key:")
(def ^:private mirror-derive-key-mid ":murakumo-overlay-key:")
(def ^:private mirror-type-key "murakumo.overlay.key")
(def ^:private mirror-type-rotation "murakumo.overlay.key-rotation")

(def default-rotation-seconds
  "Rotation window seconds. Kotoba when ready."
  (oracle-i64-const 'default-rotation-seconds mirror-default-rotation-seconds))

(def key-id-hex-len
  "Kid hex prefix length. Kotoba when ready."
  (oracle-i64-const 'key-id-hex-len mirror-key-id-hex-len))

(def seed-sep
  (oracle-str-const 'seed-sep mirror-seed-sep))

(def key-id-mid
  (oracle-str-const 'key-id-mid mirror-key-id-mid))

(def derive-key-mid
  (oracle-str-const 'derive-key-mid mirror-derive-key-mid))

(def type-key
  (oracle-str-const 'type-key mirror-type-key))

(def type-rotation
  (oracle-str-const 'type-rotation mirror-type-rotation))

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
          #(str overlay key-id-mid epoch)))
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
         (try-oracle
          #(o 'derive-key-input
              [(str operator-seed) (str overlay) (oracle/as-i64 epoch)])
          #(str operator-seed seed-sep overlay derive-key-mid epoch)))})

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
