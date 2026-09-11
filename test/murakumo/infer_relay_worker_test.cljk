(ns murakumo.infer-relay-worker-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.relay-worker :as worker]))

(deftest parse-args-test
  (testing "relay url positional + --flags"
    (is (= {:relay-url "ws://host:1/ws" :model "m" :name "n"}
           (worker/parse-args ["ws://host:1/ws" "--model" "m" "--name" "n"]))))
  (testing "flags only, no positional url"
    (is (= {:model "m"} (worker/parse-args ["--model" "m"]))))
  (testing "empty args"
    (is (= {} (worker/parse-args [])))))

(deftest placeholder-did-test
  (testing "deterministic per node-name, prefixed like cloud-murakumo's mobile placeholder"
    (is (= (worker/placeholder-did "asher") (worker/placeholder-did "asher")))
    (is (not= (worker/placeholder-did "asher") (worker/placeholder-did "juna")))
    (is (re-matches #"did:key:pending-[0-9a-f]{64}" (worker/placeholder-did "asher")))))

(deftest handle-job-unsupported-kind-test
  (testing "an unrecognised job kind fails closed with an error output, not an exception"
    (let [reply (worker/handle-job "http://unused" {:job-id "j1" :kind "media-postproc" :input {}})]
      (is (= "result" (:msg reply)))
      (is (= "j1" (:job-id reply)))
      (is (get-in reply [:output :error]))
      (is (= 0 (:ms reply))))))
