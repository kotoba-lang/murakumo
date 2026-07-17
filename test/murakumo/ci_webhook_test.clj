(ns murakumo.ci-webhook-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [murakumo.ci.broker :as broker]
            [murakumo.ci.webhook :as webhook]))

(defn request [uri body headers]
  {:request-method :post :uri uri :headers headers
   :body (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))})

(deftest authenticated-github-ingress-enqueues
  (let [state (atom (broker/empty-broker))
        secret "secret"
        body (json/generate-string {:repository {:full_name "o/r"}
                                    :after "0123456789abcdef0123456789abcdef01234567"
                                    :ref "refs/heads/main"})
        h (webhook/handler {:github-secret secret :pipeline-digest "bafy-pipe"
                            :submit! #(swap! state broker/submit %)
                            :verify-radicle (constantly false)})
        bad (h (request "/ci/v1/events/github" body {}))
        good (h (request "/ci/v1/events/github" body
                         {"x-hub-signature-256" (webhook/github-signature secret body)}))]
    (is (= 401 (:status bad)))
    (is (= 202 (:status good)))
    (is (= 1 (count (:murakumo.ci/runs @state))))))

(deftest radicle-ingress-requires-injected-verification
  (let [seen (atom [])
        body (json/generate-string {:rid "rad:zRepo"
                                    :commit "0123456789abcdef0123456789abcdef01234567"
                                    :ref "refs/heads/main" :signer "did:key:zNode"})
        make #(webhook/handler {:pipeline-digest "pipe" :submit! (fn [r] (swap! seen conj r))
                                :verify-radicle %})]
    (is (= 401 (:status ((make nil) (request "/ci/v1/events/radicle" body {})))))
    (is (= 202 (:status ((make (fn [{:keys [signature]}] (= "valid" signature)))
                         (request "/ci/v1/events/radicle" body
                                  {"x-radicle-signature" "valid"})))))
    (is (= 1 (count @seen)))))
