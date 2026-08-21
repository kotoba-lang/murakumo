(ns murakumo.ci-attest-test
  (:require [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [kotoba-git.refs :as refs]
            [murakumo.ci.attest :as attest]))

(def seeds [(byte-array (repeat 32 (byte 1)))
            (byte-array (repeat 32 (byte 2)))
            (byte-array (repeat 32 (byte 3)))])
(def dids (mapv ed/did-key-from-seed seeds))

(defn execution [runner artifact]
  {:result :passed
   :receipt {:receipt/version 1 :receipt/run-id "run-1"
             :receipt/source {:source/git-revision "abc"}
             :receipt/pipeline-digest "bafy-pipe"
             :receipt/runner-id runner :receipt/status :passed
             :receipt/jobs [{:ci.job/id "test" :ci.job/status :passed
                             :ci.job/steps [{:ci.step/status :passed
                                            :ci.step/duration-ms 12
                                            :ci.step/stdout-digest "log"
                                            :ci.step/artifacts [{:path "app.wasm" :cid artifact}]}]}]}})

(deftest receipts-differ-but-shared-verdict-reaches-quorum
  (let [a (attest/attest (repo/empty-repo) "rid-ci" (nth seeds 0)
                         (execution (nth dids 0) "bafy-art") 1)
        b (attest/attest (:db a) "rid-ci" (nth seeds 1)
                         (assoc-in (execution (nth dids 1) "bafy-art")
                                   [:receipt :receipt/jobs 0 :ci.job/steps 0 :ci.step/duration-ms] 99) 2)
        policy {:rid "rid-ci" :run-id "run-1" :delegates (set dids) :threshold 2}]
    (is (not= (:receipt-commit-cid a) (:receipt-commit-cid b)))
    (is (= (:verdict-cid a) (:verdict-cid b)))
    (is (= (:verdict-cid a)
           (attest/canonical-verdict policy [(:sigref a) (:sigref b)])))
    (is (= (:receipt-commit-cid b)
           (refs/get-ref (:db b) "rid-ci"
                         (attest/receipt-ref "run-1" (nth dids 1)))))
    (let [store (atom {})
          snap (repo/persist! #(swap! store assoc %1 %2) (:db b) nil)
          restored (repo/load #(get @store %) snap)]
      (is (= (:tree (object/read-commit (:db b) (:receipt-commit-cid b)))
             (:tree (object/read-commit restored (:receipt-commit-cid b))))))))

(deftest artifact-mismatch-does-not-form-quorum
  (let [a (attest/attest (repo/empty-repo) "rid-ci" (nth seeds 0)
                         (execution (nth dids 0) "artifact-a") 1)
        b (attest/attest (:db a) "rid-ci" (nth seeds 1)
                         (execution (nth dids 1) "artifact-b") 2)]
    (is (not= (:verdict-cid a) (:verdict-cid b)))
    (is (nil? (attest/canonical-verdict
               {:rid "rid-ci" :run-id "run-1" :delegates (set dids) :threshold 2}
               [(:sigref a) (:sigref b)])))))

(deftest signer-must-match-receipt-runner
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not own signing key"
                        (attest/attest (repo/empty-repo) "rid-ci" (first seeds)
                                       (execution (second dids) "artifact") 1))))

(deftest split-quorum-is-never-resolved-by-arrival-order
  (let [four-seeds (conj seeds (byte-array (repeat 32 (byte 4))))
        four-dids (mapv ed/did-key-from-seed four-seeds)
        attestations (mapv (fn [idx artifact]
                             (attest/attest (repo/empty-repo) "rid-ci" (nth four-seeds idx)
                                            (execution (nth four-dids idx) artifact) idx))
                           (range 4) ["a" "a" "b" "b"])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"split verdict quorum"
         (attest/canonical-verdict
          {:rid "rid-ci" :run-id "run-1" :delegates (set four-dids) :threshold 2}
          (mapv :sigref attestations))))))
