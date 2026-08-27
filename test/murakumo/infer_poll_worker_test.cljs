(ns murakumo.infer-poll-worker-test
  (:require [cljs.test :refer [async deftest is run-tests testing]]
            [murakumo.infer.poll-worker :as worker]))

(deftest local-auth-token-precedence
  (testing "an explicit diagnostic option wins"
    (is (= "arg" (worker/local-auth-token
                   {:local-token "arg"}
                   {"MURAKUMO_INFER_LOCAL_TOKEN" "dedicated"
                    "VLLM_API_KEY" "vllm"}))))
  (testing "the dedicated environment value wins over the vLLM compatibility value"
    (is (= "dedicated" (worker/local-auth-token
                         {}
                         {"MURAKUMO_INFER_LOCAL_TOKEN" "dedicated"
                          "VLLM_API_KEY" "vllm"}))))
  (is (= "vllm" (worker/local-auth-token {} {"VLLM_API_KEY" "vllm"})))
  (is (nil? (worker/local-auth-token {} {}))))

(deftest local-auth-header-is-optional
  (is (= {"content-type" "application/json"}
         (worker/local-auth-headers nil)))
  (is (= {"content-type" "application/json"
          "authorization" "Bearer secret"}
         (worker/local-auth-headers "secret"))))

(deftest completion-sends-local-bearer-and-rejects-http-errors
  (async done
    (let [requests (atom [])
          response (fn [status body]
                     #js {:status status
                          :text (fn [] (js/Promise.resolve (js/JSON.stringify (clj->js body))))})]
      (let [fetch-fn (fn [_url opts]
                       (swap! requests conj opts)
                       (js/Promise.resolve
                        (if (= 1 (count @requests))
                          (response 200 {:choices [{:message {:content "ok"}}]})
                          (response 401 {:error "unauthorized"}))))]
      (-> (worker/run-completion-with-fetch! fetch-fn "http://local/v1" "local-secret" "model" "hello" 4)
          (.then (fn [outcome]
                   (is (:ok outcome))
                   (is (= "ok" (:text outcome)))
                   (is (= "Bearer local-secret"
                          (aget (.-headers (first @requests)) "authorization")))
                   (worker/run-completion-with-fetch! fetch-fn "http://local/v1" "bad" "model" "hello" 4)))
          (.then (fn [outcome]
                   (is (false? (:ok outcome)))
                   (is (re-find #"HTTP 401" (:error outcome)))))
          (.catch (fn [error] (is false (str error))))
          (.finally done))))))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (println (str "\n" (:test m) " tests, " (:pass m) " assertions, "
                (:fail m) " failures, " (:error m) " errors"))
  (when-not (cljs.test/successful? m) (js/process.exit 1)))

(run-tests)
