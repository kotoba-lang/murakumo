(ns murakumo.publisher-adapter-test
  (:require [murakumo.publisher :as mp]
            [aiueos.publisher :as pub]
            [status-list.core :as sl]
            [clojure.test :refer [deftest is testing]]))

(deftest bit-addressing-matches-the-spec-not-a-guess
  (testing "generated with the library, read back with our adapter"
    (let [encoded (sl/generate [0 7 8 130])
          bits (mp/bits (sl/expand encoded))]
      (is (= 1 (nth bits 0)))
      (is (= 1 (nth bits 7)) "last bit of byte 0 is index 7, not index 0 of byte 1")
      (is (= 1 (nth bits 8)))
      (is (= 1 (nth bits 130)))
      (is (= 0 (nth bits 1)))
      (is (= 0 (nth bits 9))))))

(deftest a-credential-without-a-list-yields-nil-which-fails-closed
  (is (nil? (mp/credential->bits {})))
  (is (true? (pub/revoked? (mp/credential->bits {}) 0))
      "nil is not 'nobody is revoked' -- an index outside the bitmap is revoked"))

(def root {:keys #{"k1" "k2"} :threshold 2})
(def sigs [{:key-id "k1" :verified? true :status-index 0}
           {:key-id "k2" :verified? true :status-index 1}])
(def release {:sequence 7 :signatures sigs :artifact-digests-match? true :timestamp-ms 1000})

(defn- cred [revoked-indices]
  {"credentialSubject" {"encodedList" (sl/generate revoked-indices)}})

(deftest a-release-signed-by-live-keys-is-admitted
  (is (pub/admitted?
       (mp/admit release {:installed-sequence 6 :now-ms 2000 :root root
                          :status-list-credential (cred [])}))))

(deftest revoking-a-signer-in-the-real-list-format-stops-the-release
  (let [v (mp/admit release {:installed-sequence 6 :now-ms 2000 :root root
                             :status-list-credential (cred [0 1])})]
    (is (= :key-revoked (:aiueos.publisher/reason v)))))

(deftest a-stale-bitmap-cannot-outrank-the-credential
  (testing "passing revocation-bits alongside a credential does not let the older one win"
    (let [v (mp/admit release {:installed-sequence 6 :now-ms 2000 :root root
                               :revocation-bits [0 0 0 0]
                               :status-list-credential (cred [0 1])})]
      (is (= :key-revoked (:aiueos.publisher/reason v))))))
