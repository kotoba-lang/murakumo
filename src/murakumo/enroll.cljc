(ns murakumo.enroll
  "The fleet-plane half of enrolment: naming a device's key, printing its label,
  and turning a claim request into a decision.

  The split this namespace sits on one side of: **the device owns the key, the
  fleet owns the name.** `aiueos.provider.device` generates the operational
  keypair and never lets the private half leave the machine; it exports the raw
  public key and stops there, because aiueos is dependency-minimal by invariant
  and the `did:key` envelope belongs to `org-w3-did`. This namespace is where
  the two meet.

  Nothing here re-decides anything aiueos already decides. `claim` verifies the
  possession proof with `aiueos.provider.device` and then hands the answer to
  `aiueos.enroll/claim`, which owns the admission rules. If this namespace ever
  grows a rule of its own, that rule has drifted out of the contract."
  (:require [aiueos.enroll :as enroll]
            #?(:clj [aiueos.provider.device :as device])
            [did.core :as did]
            [clojure.string :as str]))

(def ^:private hex-digits (set "0123456789abcdefABCDEF"))

(defn hex->ints
  "Hex string -> vector of 0..255 ints. Portable; `did.core` takes byte ints.

  Returns nil for anything that is not hex rather than throwing. A caller
  naming a device cannot usefully catch an exception here, and a parse that
  throws on bad input while its siblings return nil is the kind of asymmetry
  that turns one bad label into a 500 instead of a refusal."
  [hex]
  (when (and (string? hex) (pos? (count hex)) (even? (count hex))
             (every? hex-digits hex))
    (mapv (fn [pair]
            (let [s (apply str pair)]
              #?(:clj (Integer/parseInt s 16)
                 :cljs (js/parseInt s 16))))
          (partition 2 hex))))

(defn device-did
  "Raw Ed25519 public key (64 hex chars) -> `did:key`.

  Returns nil rather than a partial name for anything that is not a 32-byte
  key: a device identified by a truncated key would collide with whatever else
  truncates the same way."
  [public-key-hex]
  (let [ints (hex->ints public-key-hex)]
    (when (= 32 (count ints))
      (did/public-key->did-key ints))))

(defn did->public-key-hex
  "The inverse, for checking that a label and a key agree."
  [d]
  (when (and (string? d) (did/did-key-ed25519? d))
    (str/join (map #(let [s #?(:clj (Integer/toHexString %) :cljs (.toString % 16))]
                      (if (= 1 (count s)) (str "0" s) s))
                   (did/did-key->public-key d)))))

(defn label
  "The canonical label payload for a device, as printed on the box.

  Built by `aiueos.enroll/qr-payload` — the encoding lives with the decision
  that consumes it, so a label and the state machine that answers it cannot
  drift apart. Returns nil when the key cannot be named or a field would make
  the encoding ambiguous."
  [{:keys [public-key-hex model endpoint token]}]
  (when-let [d (device-did public-key-hex)]
    (enroll/qr-payload {:did d :model model :endpoint endpoint :token token})))

(defn label-matches-key?
  "Does a scanned label name the key the device just proved it holds? Checked
  separately from the claim decision because a mismatch here is a different
  event from a failed claim: it means the label and the machine are not the
  same device."
  [label-payload public-key-hex]
  (let [parsed (enroll/parse-qr label-payload)]
    (and (nil? (:aiueos.enroll/error parsed))
         (some? (:did parsed))
         (= (:did parsed) (device-did public-key-hex)))))

#?(:clj
   (defn claim
     "Verify the device's possession proof, then let `aiueos.enroll/claim`
     decide.

     `req` — `{:label :owner :now-ms :signed-challenge :expected}` where
     `:expected` is what the enrolment service issued
     (`{:public-key-b64 :nonce :endpoint}`).
     `record` — the fleet's stored device record
     (`{:did :state :token :attested? :attestation-valid? :first-seen-ms
     :public-key-hex}`).

     The proof is verified here and reduced to the single boolean the decision
     consumes; the decision's own reasons are returned unchanged. A verdict from
     this function is always an `aiueos.enroll` verdict, never a murakumo one."
     ([record req] (claim record req enroll/default-policy))
     ([record req policy]
      (let [parsed (enroll/parse-qr (:label req))
            proof-ok (device/possession-proof-valid? (:signed-challenge req)
                                                     (:expected req))]
        (cond
          (:aiueos.enroll/error parsed)
          {:aiueos/decision :deny
           :aiueos.enroll/reason :label-unreadable
           :murakumo.enroll/error (:aiueos.enroll/error parsed)}

          (not (label-matches-key? (:label req) (:public-key-hex record)))
          {:aiueos/decision :deny
           :aiueos.enroll/reason :device-did-mismatch
           :murakumo.enroll/detail :label-names-a-different-key}

          :else
          (enroll/claim record
                        {:did (:did parsed)
                         :token (:token parsed)
                         :owner (:owner req)
                         :now-ms (:now-ms req)
                         :possession-proof-valid? proof-ok}
                        policy))))))

(defn record-after
  "The device record a granted verdict produces. Kept next to `claim` so the
  state the fleet stores is derived from the verdict rather than assembled by
  each caller — two callers assembling it differently is how a device ends up
  `:claimed` in one view and `:factory` in another."
  [record verdict owner]
  (if (= :grant (:aiueos/decision verdict))
    (assoc record
           :state (:aiueos.enroll/next-state verdict)
           :owner owner
           :trust (:aiueos.enroll/trust verdict))
    record))
