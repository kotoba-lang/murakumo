(ns murakumo.ci-github-status-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [murakumo.ci.github-status :as status]))

(def sha "0123456789abcdef0123456789abcdef01234567")

(deftest status-request-maps-canonical-verdict-to-external-context
  (let [r (status/request {:repo "kotoba-lang/kotobase" :sha sha
                           :result :passed :run-id "run-1"
                           :target-url "https://murakumo.cloud/ci/runs/run-1"}
                          "token")
        body (json/parse-string (get-in r [:opts :body]) true)]
    (is (= (str "https://api.github.com/repos/kotoba-lang/kotobase/statuses/" sha) (:url r)))
    (is (= "success" (:state body)))
    (is (= "murakumo.cloud/ci" (:context body)))
    (is (= "2026-03-10" (get-in r [:opts :headers "x-github-api-version"]))))
  (is (thrown? clojure.lang.ExceptionInfo
               (status/request {:repo "bad/repo/extra" :sha sha :result :passed} "t"))))

(deftest publish-requires-created-response
  (is (= {:ok? true :run-id "r" :state "failure"}
         (status/publish! (fn [_ _] {:status 201})
                          {:repo "o/r" :sha sha :result :failed :run-id "r"} "t")))
  (is (thrown? clojure.lang.ExceptionInfo
               (status/publish! (fn [_ _] {:status 403})
                                {:repo "o/r" :sha sha :result :passed} "t"))))
