(ns murakumo.infer-join-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.join :as join]))

(deftest tiers-and-capabilities
  (testing "browser is the widest-reach, zero-install tier"
    (is (= :none (get-in join/tiers [:browser :install])))
    (is (= :widest (get-in join/tiers [:browser :reach])))
    (is (join/can? {:tier :browser} :embarrassingly-parallel))
    (is (not (join/can? {:tier :browser} :host-large-model))))
  (testing "native hosts large models; browser/wasm do not"
    (is (join/can? {:tier :native} :host-large-model))
    (is (join/can? {:tier :wasm} :media-postproc))
    (is (not (join/can? {:tier :wasm} :low-latency-pipeline)))))

(deftest browser-is-nat-free
  (testing "browser/wasm always relay (dial out) — the NAT-traversal advantage"
    (is (join/needs-relay? {:tier :browser}))
    (is (join/needs-relay? {:tier :wasm})))
  (testing "native needs a relay only when it can't be reached inbound"
    (is (not (join/needs-relay? {:tier :native :inbound-reachable? true})))
    (is (join/needs-relay? {:tier :native :inbound-reachable? false}))))

(deftest enrollment-record
  (let [e (join/enrollment {:name "tab-1" :did "did:key:z6MkAbc" :tier :browser
                            :mem-bytes (* 8 1024 1024 1024) :link-gbps 0.1})]
    (testing "did:key is the account; tier + relay + caps drive scheduling"
      (is (= "did:key:z6MkAbc" (:node/did e)))
      (is (= :browser (:node/tier e)))
      (is (= :webrtc (:node/connect e)))
      (is (true? (:node/needs-relay? e))))
    (testing "residency is capped to the tier ceiling (a tab won't hold 8GB)"
      (is (= (* 2 1024 1024 1024) (get-in e [:node/caps :max-resident-bytes]))))))

(deftest work-routing-by-tier
  (let [nodes [(join/enrollment {:name "mac" :did "did:key:native" :tier :native
                                 :mem-bytes (* 16 1024 1024 1024)})
               (join/enrollment {:name "tab-a" :did "did:key:a" :tier :browser :mem-bytes (* 8 1024 1024 1024)})
               (join/enrollment {:name "tab-b" :did "did:key:b" :tier :browser :mem-bytes (* 8 1024 1024 1024)})]
        jobs [{:work-kind :host-large-model :resident-bytes (* 10 1024 1024 1024)}
              {:work-kind :embarrassingly-parallel :resident-bytes (* 1 1024 1024 1024)}
              {:work-kind :media-postproc :resident-bytes (* 512 1024 1024)}]
        {:keys [native swarm unschedulable]} (join/partition-work nodes jobs)]
    (testing "heavy model hosting → native; parallel + postproc → browser swarm"
      (is (= 1 (count native)))
      (is (= :host-large-model (:work-kind (first native))))
      (is (= 2 (count swarm)))
      (is (empty? unschedulable)))))

(deftest swarm-only-still-serves-light-work
  (testing "a fleet of ONLY browser tabs can still do parallel + media-postproc"
    (let [nodes [(join/enrollment {:name "t1" :did "did:key:1" :tier :browser :mem-bytes (* 4 1024 1024 1024)})]
          jobs [{:work-kind :embarrassingly-parallel :resident-bytes (* 512 1024 1024)}
                {:work-kind :host-large-model :resident-bytes (* 10 1024 1024 1024)}]
          {:keys [swarm unschedulable]} (join/partition-work nodes jobs)]
      (is (= 1 (count swarm)))
      (is (= 1 (count unschedulable)))          ; no native → large model can't land
      (is (= :host-large-model (:work-kind (first unschedulable)))))))

;; ---------------------------------------------------------------------------
;; Capability provenance + the uniform-cohort detector (ADR-2607319500 D3)
;; ---------------------------------------------------------------------------

(deftest capability-provenance-defaults-to-declared
  (testing "absent provenance is :declared — a node that says nothing is not trusted"
    (is (= :declared (join/capability-provenance {:caps {:mem-bytes 34359738368}})))
    (is (= :declared (join/capability-provenance {}))))
  (testing "an unrecognised value is :declared, not passed through"
    (is (= :declared (join/capability-provenance {:caps {:capability/source :probably}}))))
  (testing ":measured only when explicitly asserted"
    (is (= :measured (join/capability-provenance {:caps {:capability/source :measured}})))
    (is (join/measured-capability? {:caps {:capability/source :measured}})))
  (testing "reads an enrollment record (:node/caps) as well as a planner node (:caps)"
    (is (= :measured (join/capability-provenance
                      {:node/caps {:capability/source :measured}})))))

(deftest enrollment-carries-provenance-with-the-numbers
  (let [e (join/enrollment {:name "tab-x" :did "did:key:z6Mk" :tier :browser
                            :mem-bytes 34359738368})]
    (testing "a joiner that does not assert measurement enrolls as :declared"
      (is (= :declared (get-in e [:node/caps :capability/source])))
      (is (= :declared (join/capability-provenance e))))
    (testing "and one that does, does"
      (is (= :measured (get-in (join/enrollment
                                {:name "judah" :did "did:key:z6Mk" :tier :native
                                 :mem-bytes 17179869184 :capability/source :measured})
                               [:node/caps :capability/source]))))))

(deftest uniform-capability-cohorts-detects-a-compiled-in-constant
  ;; reproduction of the live registry read 2026-07-31: 44 browser nodes all
  ;; advertising exactly 32 GiB, plus one node with a different figure
  (let [browsers (for [i (range 44)]
                   {:node/name (str "tab-" i) :node/caps {:mem-bytes 34359738368}})
        odd-one {:node/name "tab-odd" :node/caps {:mem-bytes 8589934592}}
        cohorts (join/uniform-capability-cohorts (conj (vec browsers) odd-one))]
    (testing "the 44-node constant is surfaced as one cohort"
      (is (= 1 (count cohorts)))
      (is (= 34359738368 (:mem-bytes (first cohorts))))
      (is (= 44 (:count (first cohorts)))))
    (testing "a lone value is not a cohort — this detector needs agreement to fire"
      (is (not-any? #(= 8589934592 (:mem-bytes %)) cohorts)))
    (testing "nodes are named so the report is actionable"
      (is (= 44 (count (:nodes (first cohorts))))))
    (testing "none of them claimed measurement, so declaration and detector agree"
      (is (zero? (:declared-measured (first cohorts)))))))

(deftest uniform-cohort-surfaces-declaration-detector-disagreement
  ;; the interesting case: nodes ASSERT :measured yet agree to the byte
  (let [liars (for [i (range 3)]
                {:node/name (str "n" i)
                 :node/caps {:mem-bytes 34359738368 :capability/source :measured}})
        cohorts (join/uniform-capability-cohorts liars)]
    (testing "the cohort still fires — the detector does not trust the declaration"
      (is (= 3 (:count (first cohorts)))))
    (testing "and the disagreement is reported rather than resolved silently"
      (is (= 3 (:declared-measured (first cohorts)))))))

(deftest uniform-capability-cohorts-is-total
  (testing "nodes without :mem-bytes are skipped, not errors"
    (is (= [] (join/uniform-capability-cohorts [{:node/name "a"} {:node/name "b"}]))))
  (testing "empty input is empty output"
    (is (= [] (join/uniform-capability-cohorts []))))
  (testing "min-cohort is tunable"
    (is (= 1 (count (join/uniform-capability-cohorts
                     [{:node/name "a" :node/caps {:mem-bytes 1}}] 1))))))
