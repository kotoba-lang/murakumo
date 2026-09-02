(ns murakumo.infer.backoff-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.backoff :as backoff]))

(deftest a-failure-doubles-the-wait-and-the-ceiling-holds
  (is (= 5000 (backoff/next-delay-ms 0)))
  (is (= 10000 (backoff/next-delay-ms 1)))
  (is (= 20000 (backoff/next-delay-ms 2)))
  (is (= 300000 (backoff/next-delay-ms 10)) "bounded at five minutes")
  (is (= 300000 (backoff/next-delay-ms 60)) "and stays bounded however long it has failed")
  (testing "jitter only ever adds, and at most a quarter"
    (is (= 5000 (backoff/next-delay-ms backoff/default-policy 0 0.0)))
    (is (<= 5000 (backoff/next-delay-ms backoff/default-policy 0 0.5) 6250))
    (is (< (backoff/next-delay-ms backoff/default-policy 0 0.999) 6250))))

(deftest local-exhaustion-is-named-not-mistaken-for-a-dead-gateway
  ;; The 2026-09-02 shape: undici `fetch failed` whose cause is EADDRNOTAVAIL.
  (is (= :local-exhaustion (backoff/classify {:code "EADDRNOTAVAIL" :message "fetch failed"})))
  (is (= :local-exhaustion (backoff/classify {:code "emfile"})))
  (is (= :local-exhaustion (backoff/classify {:message "connect: Can't assign requested address"})))
  (is (= :remote-unreachable (backoff/classify {:code "ECONNREFUSED"})))
  (is (= :remote-unreachable (backoff/classify {:code "ENOTFOUND"})))
  (is (= :http (backoff/classify {:status 503})))
  (is (= :unknown (backoff/classify {:message "fetch failed"}))
      "a bare `fetch failed` with no cause is not evidence of either"))

(deftest local-exhaustion-waits-the-whole-ceiling
  (is (= 300000 (backoff/delay-for :local-exhaustion 0)))
  (is (= 300000 (backoff/delay-for :local-exhaustion 1)))
  (is (= 10000 (backoff/delay-for :remote-unreachable 1)))
  (is (= 5000 (backoff/delay-for :unknown 0))))
