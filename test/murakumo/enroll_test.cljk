(ns murakumo.enroll-test
  (:require [murakumo.enroll :as me]
            [murakumo.factory :as factory]
            [aiueos.enroll :as enroll]
            [aiueos.provider.device :as device]
            [aiueos.key-lifecycle :as kl]
            [clojure.test :refer [deftest is testing]]))

(def kp (device/generate-operational-keypair!))
(def pub-hex (device/public-key-hex kp))
(def pub-b64 (device/public-key-base64 kp))
(def endpoint "https://murakumo.cloud/enroll")
(def token "T-ABC123")
(def label (me/label {:public-key-hex pub-hex :model "MK-1 Solo"
                      :endpoint endpoint :token token}))
(def expected {:public-key-b64 pub-b64 :nonce "n-1" :endpoint endpoint})
(def proof (device/sign-challenge
            (device/challenge {:public-key-b64 pub-b64 :nonce "n-1"
                               :endpoint endpoint :issued-ms 1})
            (.getPrivate kp)))

(defn- record []
  {:did (me/device-did pub-hex) :state :factory :token token
   :attested? false :first-seen-ms 0 :public-key-hex pub-hex})

(deftest a-device-key-gets-a-did-key-name
  (is (.startsWith (me/device-did pub-hex) "did:key:z6Mk"))
  (is (= pub-hex (me/did->public-key-hex (me/device-did pub-hex)))
      "naming round-trips, so a label and a key can be compared"))

(deftest a-truncated-key-is-not-named
  (is (nil? (me/device-did (subs pub-hex 0 40))))
  (is (nil? (me/device-did nil)))
  (is (nil? (me/device-did "zz"))))

(deftest a-label-is-the-aiueos-encoding-not-a-second-one
  (is (= label (enroll/qr-payload {:did (me/device-did pub-hex) :model "MK-1 Solo"
                                   :endpoint endpoint :token token})))
  (is (= token (:token (enroll/parse-qr label)))))

(deftest a-label-that-names-another-key-is-caught-before-the-claim
  (let [other (device/public-key-hex (device/generate-operational-keypair!))]
    (is (true? (me/label-matches-key? label pub-hex)))
    (is (false? (me/label-matches-key? label other)))))

(deftest a-complete-claim-is-granted
  (let [v (me/claim (record) {:label label :owner "acct:k" :now-ms 1000
                              :signed-challenge proof :expected expected})]
    (is (enroll/granted? v))
    (is (= :tofu (:aiueos.enroll/trust v)) "no factory certificate -> reported")
    (is (= :claimed (:state (me/record-after (record) v "acct:k"))))))

(deftest a-claim-with-a-replayed-proof-is-denied-by-aiueos-not-by-murakumo
  (let [v (me/claim (record) {:label label :owner "acct:k" :now-ms 1000
                              :signed-challenge proof
                              :expected (assoc expected :nonce "n-2")})]
    (is (= :no-proof-of-possession (:aiueos.enroll/reason v))
        "the reason comes from the decision layer unchanged")))

(deftest an-unreadable-label-is-its-own-reason
  (let [v (me/claim (record) {:label "not-a-label" :owner "acct:k" :now-ms 1000
                              :signed-challenge proof :expected expected})]
    (is (= :label-unreadable (:aiueos.enroll/reason v)))
    (is (= :wrong-scheme (:murakumo.enroll/error v)))))

(deftest a-label-from-a-different-box-does-not-claim-this-one
  (let [other-hex (device/public-key-hex (device/generate-operational-keypair!))
        other-label (me/label {:public-key-hex other-hex :model "MK-1 Solo"
                               :endpoint endpoint :token token})
        v (me/claim (record) {:label other-label :owner "acct:k" :now-ms 1000
                              :signed-challenge proof :expected expected})]
    (is (= :device-did-mismatch (:aiueos.enroll/reason v)))
    (is (= :label-names-a-different-key (:murakumo.enroll/detail v)))))

(deftest a-denied-claim-does-not-move-the-record
  (let [r (record)
        v {:aiueos/decision :deny :aiueos.enroll/reason :token-mismatch}]
    (is (= r (me/record-after r v "acct:k")))))

;; ── factory station ───────────────────────────────────────────────────────

(def root-kp (kl/generate-key-pair))

(deftest a-station-issues-a-verifiable-birth-certificate
  (let [u (factory/provision {:serial "SN-0001" :model "MK-1 Solo" :issued-ms 1
                              :root-private-key (.getPrivate root-kp)})]
    (is (true? (factory/certificate-valid? (:certificate u)
                                           (kl/public-key-base64 root-kp))))
    (is (false? (factory/certificate-valid? (:certificate u)
                                            (kl/public-key-base64 (kl/generate-key-pair))))
        "a certificate from another root does not verify")))

(deftest a-tampered-certificate-does-not-verify
  (let [u (factory/provision {:serial "SN-0002" :model "MK-1 Solo" :issued-ms 1
                              :root-private-key (.getPrivate root-kp)})
        tampered (assoc (:certificate u) :murakumo.factory/serial "SN-9999")]
    (is (false? (factory/certificate-valid? tampered (kl/public-key-base64 root-kp))))))

(deftest the-station-never-holds-the-operational-key
  (let [u (factory/provision {:serial "SN-0003" :model "MK-1 Solo" :issued-ms 1
                              :root-private-key (.getPrivate root-kp)})]
    (is (nil? (:did (factory/unit-record u)))
        "no operational key exists yet, so there is no name to invent")
    (is (= :factory (:state (factory/unit-record u))))
    (is (true? (:attested? (factory/unit-record u))))
    (is (nil? (:factory-private-key-b64 (factory/unit-record u)))
        "the record the fleet stores does not carry the factory private key")))

(deftest a-provisioned-unit-becomes-attested-once-it-has-a-key
  (let [u (factory/provision {:serial "SN-0004" :model "MK-1 Solo" :issued-ms 1
                              :root-private-key (.getPrivate root-kp)})
        r (merge (factory/unit-record u) {:did (me/device-did pub-hex)
                                          :public-key-hex pub-hex})
        lbl (factory/label-for u pub-hex endpoint)
        v (me/claim r {:label lbl :owner "acct:k" :now-ms 1000
                       :signed-challenge proof :expected expected}
                    {:require-attestation? true})]
    (is (enroll/granted? v))
    (is (= :attested (:aiueos.enroll/trust v))
        "with a station, the same flow reaches the grade tofu could not")))

(deftest tokens-are-unguessable-and-distinct
  (let [ts (repeatedly 20 factory/draw-token)]
    (is (= 20 (count (distinct ts))))
    (is (every? #(= 42 (count %)) ts) "T- plus 40 hex chars = 160 bits")))
