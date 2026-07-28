(ns murakumo.secret-test
  "W6 secret-custody ops cutover: named fetch only, no ambient dump."
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.secret :as secret]))

(deftest map-fetch-roundtrip
  (let [f (secret/map-fetch {"murakumo-token" "hmac-s3cret"
                             "other" "x"})]
    (is (= :value (:tag (f {:name "murakumo-token"}))))
    (is (= "hmac-s3cret" (:value (f {:name "murakumo-token"}))))
    (is (= :secret/not-found (:code (f {:name "missing"}))))))

(deftest fn-fetch-one-shot
  (let [calls (atom [])
        f (secret/fn-fetch (fn [n]
                             (swap! calls conj n)
                             (when (= n "murakumo-token") "from-kagi")))]
    (is (= "from-kagi" (:value (f {:name "murakumo-token"}))))
    (is (= :secret/not-found (:code (f {:name "other"}))))
    (is (= ["murakumo-token" "other"] @calls))))

(deftest resolve-known-secrets-via-inject
  (let [fetch (secret/map-fetch {"murakumo-token" "hmac"
                                 "murakumo-service-token" "svc"
                                 "murakumo-metrics-token" "met"})]
    (is (= "hmac" (secret/resolve-token-secret {:fetch fetch})))
    (is (= "svc" (secret/resolve-service-token {:fetch fetch})))
    (is (= "met" (secret/resolve-metrics-token {:fetch fetch})))
    (is (nil? (secret/resolve-service-token {:fetch (secret/map-fetch {})}))))
  (testing "default name identity"
    (is (= "murakumo-token" secret/token-secret-name))
    (is (= "MURAKUMO_TOKEN_SECRET" secret/token-secret-env))
    (is (= "murakumo-service-token" secret/service-token-name))
    (is (= "MURAKUMO_SERVICE_TOKEN" secret/service-token-env))
    (is (= "murakumo-metrics-token" secret/metrics-token-name))
    (is (= "MURAKUMO_METRICS_TOKEN" secret/metrics-token-env))
    (is (= secret/known-env-secrets
           {secret/token-secret-name secret/token-secret-env
            secret/service-token-name secret/service-token-env
            secret/metrics-token-name secret/metrics-token-env}))))

(deftest resolve-exact-env-for-overlay-auth-key
  (testing "config-declared env var name, exact read only"
    (is (= "overlay-secret"
           (secret/resolve-exact-env
            "ANY_DECLARED_ENV"
            {:fetch (secret/map-fetch {"dyn-env" "overlay-secret"})})))
    (is (nil? (secret/resolve-exact-env
               "ANY_DECLARED_ENV"
               {:fetch (secret/map-fetch {})})))
    (is (nil? (secret/resolve-exact-env "bad*name")))
    (is (nil? (secret/resolve-exact-env "")))
    (is (nil? (secret/resolve-exact-env "a/b")))
    (is (true? (secret/valid-env-var-name? "MURAKUMO_OVERLAY_AUTH")))
    (is (false? (secret/valid-env-var-name? "tok*")))))

(deftest env-fetch-rejects-bad-maps
  (is (thrown? Exception (secret/env-fetch {})))
  (is (thrown? Exception (secret/env-fetch {"a" 1})))
  (is (thrown? Exception (secret/env-fetch {"a" "bad*"}))))
