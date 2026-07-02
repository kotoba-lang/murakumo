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
