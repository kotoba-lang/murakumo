(ns murakumo.cd-receipt-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [kotoba-git.refs :as refs]
            [murakumo.cd.receipt :as receipt]))

(def seed (byte-array (repeat 32 (byte 6))))
(def executor (ed/did-key-from-seed seed))
(def rollout-result
  {:receipt-cid "logical-cid"
   :ref "refs/cd/deployments/capability"
   :receipt {:cd.receipt/version 1 :cd.rollout/state :succeeded
             :cd.rollout/capability-cid "capability"
             :cd.rollout/artifact-cid "artifact"
             :cd.rollout/events []}})

(deftest terminal-receipt-is-stored-signed-and-restorable
  (let [a (receipt/attest (repo/empty-repo) "rid-ci" seed rollout-result 10)]
    (is (receipt/valid? {:rid "rid-ci" :executors #{executor}} a))
    (is (= (:receipt-commit-cid a)
           (refs/get-ref (:db a) "rid-ci" "refs/cd/deployments/capability")))
    (let [store (atom {})
          snapshot (repo/persist! #(swap! store assoc %1 %2) (:db a) nil)
          restored (repo/load #(get @store %) snapshot)]
      (is (= (:tree (object/read-commit (:db a) (:receipt-commit-cid a)))
             (:tree (object/read-commit restored (:receipt-commit-cid a))))))
    (is (not (receipt/valid? {:rid "rid-ci" :executors #{"did:key:other"}} a)))))
