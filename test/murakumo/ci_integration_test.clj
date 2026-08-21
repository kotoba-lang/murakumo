(ns murakumo.ci-integration-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.ci.broker :as broker]
            [murakumo.ci.protocol :as protocol]
            [murakumo.ci.source :as source]
            [murakumo.ci.store :as store]
            [murakumo.overlay.stream :as stream]))

(deftest forge-events-converge-on-stable-run-requests
  (let [g {:repository {:full_name "kotoba-lang/kotobase"}
           :after "abc" :ref "refs/heads/main"}
        a (source/github g "bafy-pipeline")
        b (source/github (into (sorted-map) (reverse g)) "bafy-pipeline")
        r (source/radicle {:rid "rad:zRepo" :commit "abc"
                           :ref "refs/heads/main" :signer "did:key:zNode"}
                          "bafy-pipeline")]
    (is (= (:ci.run/id a) (:ci.run/id b)))
    (is (= :github (get-in a [:ci.run/source :source/type])))
    (is (= :radicle (get-in r [:ci.run/source :source/type])))
    (is (not= (:ci.run/id a) (:ci.run/id r)))))

(deftest broker-checkpoint-restores-and-does-not-duplicate-events
  (let [s (store/memory-store)
        request (source/github {:repository {:full_name "o/r"}
                                :after "abc" :ref "refs/heads/main"} "pipe")
        b (broker/submit (broker/empty-broker) request)]
    (is (= {:events-appended 1 :events-total 1} (store/checkpoint! s b)))
    (is (= {:events-appended 0 :events-total 1} (store/checkpoint! s b)))
    (is (= b (store/restore s)))
    (is (= 1 (count (store/events-since s 0))))
    (is (string? (:ci.event/id (first (store/events-since s 0)))))))

(deftest overlay-carries-versioned-lease-and-receipt-messages
  (let [runner {:runner/id "did:key:zRunner" :runner/capabilities #{:linux}}
        run {:ci.run/id "run-1" :ci.run/source {:kotoba/commit "bafy-source"}
             :ci.run/pipeline-digest "bafy-pipe" :ci.run/requires #{:linux}}
        lease {:ci.lease/token "t" :ci.lease/run-id "run-1"
               :ci.lease/runner-id "did:key:zRunner" :ci.lease/expires-at 100}
        payloads [(protocol/lease-request runner)
                  (protocol/lease-offer lease run)
                  (protocol/completion lease :passed "bafy-receipt")]
        session {:overlay "bafy-overlay" :node "bafy-node" :name "runner"}
        framed (stream/frames (stream/open-stream session "murakumo-ci" 1) payloads)]
    (is (every? protocol/valid? (map #(get % :payload) (:frames framed))))
    (is (= [0 1 2] (mapv :seq (:frames framed))))
    (is (= "bafy-receipt"
           (get-in framed [:frames 2 :payload :murakumo.ci/body :receipt/cid])))))
