(ns murakumo.ci-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.ci.broker :as broker]
            [murakumo.ci.runner :as runner]
            [murakumo.ci.sandbox :as sandbox]))

(def context {:source-dir "/safe/src" :output-dir "/safe/out" :runtime "podman"})
(def step {:ci/image "docker.io/library/clojure:temurin-21-tools-deps"
           :ci/image-digest "sha256:image"
           :ci/argv ["clojure" "-M:test"]})

(deftest sandbox-plan-is-shell-free-and-locked-down
  (let [p (sandbox/plan step context)
        argv (:sandbox/argv p)]
    (is (= "podman" (first argv)))
    (is (some #{"--read-only"} argv))
    (is (= "none" (nth argv (inc (.indexOf argv "--network")))))
    (is (some #{"ALL"} argv))
    (is (some #{"no-new-privileges"} argv))
    (is (some #{"docker.io/library/clojure:temurin-21-tools-deps@sha256:image"} argv))
    (is (= ["clojure" "-M:test"] (vec (take-last 2 argv)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid sandbox step"
                        (sandbox/plan (assoc step :ci/argv "rm -rf /") context))))

(deftest runner-fails-fast-and-builds-stable-receipt
  (let [calls (atom [])
        exec (fn [plan]
               (swap! calls conj plan)
               (if (some #{"fail"} (:sandbox/argv plan))
                 {:exit 1 :stderr-digest "bafy-err" :duration-ms 2}
                 {:exit 0 :stdout-digest "bafy-out" :duration-ms 1
                  :verified-artifacts [{:path "junit.xml" :cid "bafy-art"}]}))
        pipeline {:ci/jobs
                  {"build" {:ci/id "build" :ci/steps [step]}
                   "test" {:ci/id "test" :ci/steps [(assoc step :ci/argv ["fail"])
                                                       (assoc step :ci/argv ["never"])]}
                   "deploy" {:ci/id "deploy" :ci/steps [step]}}}
        request {:ci.run/id "run-1" :ci.run/source {:kotoba/commit "bafy-src"}
                 :ci.run/pipeline-digest "bafy-pipe"}
        result (runner/run exec context request pipeline
                           [["build"] ["test"] ["deploy"]] "did:key:zRunner")]
    (is (= :failed (:result result)))
    (is (= 2 (count @calls)) "second test step and later waves do not run")
    (is (= "bafy-art" (get-in result [:receipt :receipt/jobs 0
                                      :ci.job/steps 0 :ci.step/artifacts 0 :cid])))
    (is (string? (:receipt-cid result)))
    (is (= (:receipt-cid result)
           (:receipt-cid (runner/run exec context request pipeline
                                     [["build"] ["test"] ["deploy"]]
                                     "did:key:zRunner"))))))

(deftest lease-to-signed-receipt-reference-completion-shape
  (let [request {:ci.run/id "run-ok" :ci.run/source {:kotoba/commit "src"}
                 :ci.run/pipeline-digest "pipe" :ci.run/requires #{:linux}}
        b0 (broker/submit (broker/empty-broker) request)
        node {:runner/id "did:key:zRunner" :runner/capabilities #{:linux}}
        [b1 lease] (broker/lease b0 node "token" 0 100)
        b2 (broker/start b1 lease 1)
        pipeline {:ci/jobs {"test" {:ci/id "test" :ci/steps [step]}}}
        execution (runner/run (constantly {:exit 0}) context request pipeline
                              [["test"]] (:runner/id node))
        b3 (broker/complete b2 lease 2 (:result execution) (:receipt-cid execution))]
    (is (= :passed (get-in b3 [:murakumo.ci/runs "run-ok" :ci.run/state])))
    (is (= (:receipt-cid execution)
           (get-in b3 [:murakumo.ci/runs "run-ok" :ci.run/receipt-cid])))))

(deftest timeout-is-preserved-as-terminal-result
  (let [pipeline {:ci/jobs {"test" {:ci/id "test" :ci/steps [step]}}}
        result (runner/run (constantly {:exit 124 :timed-out? true}) context
                           {:ci.run/id "r" :ci.run/source {}
                            :ci.run/pipeline-digest "p"}
                           pipeline [["test"]] "did:key:zRunner")]
    (is (= :timed-out (:result result)))
    (is (= :timed-out (get-in result [:receipt :receipt/jobs 0 :ci.job/status])))))
