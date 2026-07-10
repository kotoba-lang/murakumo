;; murakumo.overlay-cert-test — cert.clj had no test coverage before this
;; file. Scoped to the PURE functions only (naming/path derivation, the
;; audit hash-chain, CLI arg parsing) -- the impure X.509
;; issuance/file-I/O paths (issue-self-signed!/ensure-quic-material!)
;; are already exercised indirectly by
;; murakumo.overlay-quic-driver-live-test.clj's real localhost QUIC
;; round trip (which generates real cert material via this namespace),
;; so this file doesn't duplicate that coverage.
;;
;; The audit hash-chain (append-audit/hash-event/canonical) matters most:
;; it's the SAME tamper-evident append-only pattern this whole session's
;; witness-quorum work applies to attestations, applied here to cert
;; material provenance -- worth pinning independently.

(ns murakumo.overlay-cert-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.overlay.cert :as cert]))

;; ── naming / path derivation ──────────────────────────────────────────

(deftest safe-name-replaces-unsafe-chars-and-trims-dashes
  (is (= "a-b-c" (cert/safe-name "a b/c")))
  (is (= "hello" (cert/safe-name "  hello  ")))
  (is (= "a.b_c-9" (cert/safe-name "a.b_c-9")) "dots/underscores/dashes/digits pass through unchanged"))

(deftest material-name-derives-from-session-and-connect
  (is (= "murakumo-naphtali-100.101.27.85"
         (cert/material-name {:session {:overlay "murakumo" :node "naphtali"}
                              :connect {:host "100.101.27.85"}}))))

(deftest material-name-falls-back-to-defaults-when-fields-missing
  (is (= "overlay-node-localhost" (cert/material-name {}))))

(deftest material-name-prefers-node-over-name
  (is (= "ov-node1-h" (cert/material-name {:session {:overlay "ov" :node "node1" :name "name1"} :connect {:host "h"}}))))

(deftest material-key-is-the-keyword-form-of-material-name
  (is (= :ov-node1-h (cert/material-key {:session {:overlay "ov" :node "node1"} :connect {:host "h"}}))))

(deftest material-paths-appends-generation-suffix-when-given
  (let [req {:session {:overlay "ov" :node "n"} :connect {:host "h"}}
        p0 (cert/material-paths req)
        p2 (cert/material-paths req 2)]
    (is (= "ov-n-h.cert.pem" (.getName (:cert p0))))
    (is (= "ov-n-h-g2.cert.pem" (.getName (:cert p2))))
    (is (= "ov-n-h-g2.key.pem" (.getName (:key p2))))))

;; ── audit hash-chain: tamper-evident cert material provenance ─────────

(deftest hash-event-excludes-the-hash-field-itself
  (testing "hashing an event, then re-hashing it WITH a :hash field attached,
            produces the same digest -- the hash is never self-referential"
    (let [event {:seq 1 :op :issue :material :k}]
      (is (= (cert/hash-event event)
             (cert/hash-event (assoc event :hash "whatever-was-here-before")))))))

(deftest hash-event-is-sensitive-to-key-order-independent-content
  (testing "canonical sorts map keys, so hash-event is a function of CONTENT,
            not construction order"
    (is (= (cert/hash-event {:a 1 :b 2}) (cert/hash-event {:b 2 :a 1})))))

(deftest hash-event-changes-when-any-field-changes
  (is (not= (cert/hash-event {:seq 1 :fingerprint "aaa"})
            (cert/hash-event {:seq 1 :fingerprint "bbb"}))))

(deftest append-audit-first-event-has-no-prev-hash
  (let [idx (cert/append-audit {:audit []} :issue :k1 {:generation 1 :fingerprint "fp1"} {})
        event (last (:audit idx))]
    (is (= 1 (:seq event)))
    (is (nil? (:prev-hash event)))
    (is (some? (:hash event)))))

(deftest append-audit-chains-each-event-to-the-previous-hash
  (let [idx (-> {:audit []}
                (cert/append-audit :issue :k1 {:generation 1 :fingerprint "fp1"} {})
                (cert/append-audit :rotate :k1 {:generation 2 :fingerprint "fp2"} {}))
        [e1 e2] (:audit idx)]
    (is (= 1 (:seq e1)))
    (is (= 2 (:seq e2)))
    (is (nil? (:prev-hash e1)))
    (is (= (:hash e1) (:prev-hash e2)) "the second event's prev-hash is exactly the first event's hash")
    (is (not= (:hash e1) (:hash e2)))))

(deftest append-audit-chain-breaks-detectably-if-an-earlier-event-is-tampered
  (testing "mirrors the same tamper-detection property this session's
            witness-quorum append-only ledgers rely on"
    (let [idx (-> {:audit []}
                  (cert/append-audit :issue :k1 {:generation 1 :fingerprint "fp1"} {})
                  (cert/append-audit :rotate :k1 {:generation 2 :fingerprint "fp2"} {}))
          [e1 e2] (:audit idx)
          tampered-e1 (assoc e1 :fingerprint "TAMPERED")]
      (is (not= (:hash e1) (cert/hash-event tampered-e1))
          "recomputing e1's hash after tampering no longer matches its stored hash")
      (is (not= (:prev-hash e2) (cert/hash-event tampered-e1))
          "and e2's prev-hash link, computed against the ORIGINAL e1, no longer matches
           the tampered e1's recomputed hash either -- the chain is broken at exactly
           the tampered link"))))

;; ── generation bookkeeping ─────────────────────────────────────────────

(deftest next-generation-starts-at-1-for-a-fresh-entry
  (is (= 1 (cert/next-generation nil)))
  (is (= 1 (cert/next-generation {}))))

(deftest next-generation-increments-the-active-generation
  (is (= 4 (cert/next-generation {:active 3}))))

;; ── SAN host classification ────────────────────────────────────────────

(deftest ip-address?-matches-ipv4-and-hex-like-hosts
  (is (true? (cert/ip-address? "192.168.1.1")))
  (is (true? (cert/ip-address? "127.0.0.1"))))

(deftest ip-address?-rejects-hostnames
  (is (false? (cert/ip-address? "naphtali")))
  (is (false? (cert/ip-address? "kotobase.net"))))

;; ── CLI arg parsing ────────────────────────────────────────────────────

(deftest parse-argv-defaults
  (let [opts (cert/parse-argv [])]
    (is (= :ensure (:command opts)))
    (is (= "localhost" (:host opts)))
    (is (= 4001 (:port opts)))
    (is (= 2 (:keep opts)))))

(deftest parse-argv-reads-command-and-flags
  (let [opts (cert/parse-argv ["rotate" "--node=naphtali" "--host=100.101.27.85" "--port=4002" "--keep=5"])]
    (is (= :rotate (:command opts)))
    (is (= "naphtali" (:node opts)))
    (is (= "100.101.27.85" (:host opts)))
    (is (= 4002 (:port opts)))
    (is (= 5 (:keep opts)))))

(deftest request-from-opts-builds-the-adapter-request-shape
  (let [req (cert/request-from-opts {:overlay "ov" :node "n" :name "n" :host "h" :port 4001})]
    (is (= "murakumo.overlay.adapter-request" (:type req)))
    (is (= :quic (:transport req)))
    (is (= "h" (get-in req [:connect :host])))
    (is (= 4001 (get-in req [:connect :port])))
    (is (= "ov" (get-in req [:session :overlay])))))
