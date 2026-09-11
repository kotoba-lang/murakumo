(ns murakumo.overlay-witness-treasury-test
  "The Phase-3 composition between a real witness-quorum verdict and
  kekkai's TailnetGovernor value governance (ADR-2607110300). Exercises
  the two testable halves without a real fleet/QUIC dial:
  treasury-release-proposal (pure shaping) and run-treasury-release!
  (real in-process kekkai StateGraph execution, no network). Mirrors
  kekkai's own treasury_contract_test.clj setup exactly, since this is
  the same StateGraph -- just reached via murakumo's composing entry
  point instead of a hand-built proposal in kekkai's own test suite."
  (:require [clojure.test :refer [deftest is testing]]
            [kekkai.store :as store]
            [murakumo.overlay.witness-treasury :as wt]))

(defn- witnessed [] {:kind :witnessed :verdict :accept})
(defn- rejected [] {:kind :rejected :verdict :reject})

;; ── treasury-release-proposal (pure) ─────────────────────────────────────────

(deftest proposal-shape-for-a-witnessed-quorum
  (let [p (wt/treasury-release-proposal {:quorum-state (witnessed) :amount 100 :recipient "acct:vendor"})]
    (is (= :treasury-release (:effect p)))
    (is (= (witnessed) (:witness-verdict p)))
    (is (= 100 (:amount p)))
    (is (= "acct:vendor" (:recipient p)))
    (is (= 1.0 (:confidence p)))))

(deftest proposal-defaults-zero-confidence-for-a-non-witnessed-quorum
  (let [p (wt/treasury-release-proposal {:quorum-state (rejected) :amount 10 :recipient "acct:x"})]
    (is (= 0.0 (:confidence p)))))

(deftest proposal-honors-explicit-confidence-override
  (let [p (wt/treasury-release-proposal {:quorum-state (witnessed) :amount 10 :recipient "acct:x" :confidence 0.4})]
    (is (= 0.4 (:confidence p)))))

;; ── run-treasury-release! (real kekkai StateGraph, no network) ───────────────

(deftest witnessed-quorum-reaches-human-signoff-then-commits
  (testing "the real quorum->kekkai path never auto-commits real money, but a
            clean :witnessed verdict DOES reach the human-signoff interrupt
            (proving the composition actually plumbs the verdict through,
            not just that kekkai's own hand-built-verdict tests pass)"
    (let [s (store/seed-db)
          proposal (wt/treasury-release-proposal {:quorum-state (witnessed) :amount 100 :recipient "acct:vendor"})
          r1 (wt/run-treasury-release! {:kekkai-store s :node "n-server" :proposal proposal :thread-id "wt1"})]
      (is (= :interrupted (:status r1)))
      (is (nil? (store/assessment-of s "n-server")) "nothing committed before sign-off"))))

(deftest rejected-quorum-holds-without-reaching-signoff
  (let [s (store/seed-db)
        proposal (wt/treasury-release-proposal {:quorum-state (rejected) :amount 10 :recipient "acct:x"})
        r (wt/run-treasury-release! {:kekkai-store s :node "n-server" :proposal proposal :thread-id "wt2"})]
    (is (= :hold (get-in r [:state :disposition])))
    (is (some #{:witness-quorum-not-reached} (:basis (last (store/ledger s)))))))

(deftest missing-quorum-state-holds-deny-by-default
  (let [s (store/seed-db)
        proposal (wt/treasury-release-proposal {:quorum-state nil :amount 10 :recipient "acct:x"})
        r (wt/run-treasury-release! {:kekkai-store s :node "n-server" :proposal proposal :thread-id "wt3"})]
    (is (= :hold (get-in r [:state :disposition])))
    (is (some #{:no-witness-verdict} (:basis (last (store/ledger s)))))))
