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

(deftest resolve-token-secret-via-inject
  (testing "kit-shaped inject"
    (is (= "injected"
           (secret/resolve-token-secret
            {:fetch (secret/map-fetch {"murakumo-token" "injected"})}))))
  (testing "missing fails soft (nil) — CLI prints the env error"
    (is (nil? (secret/resolve-token-secret
               {:fetch (secret/map-fetch {})}))))
  (testing "default name identity"
    (is (= "murakumo-token" secret/token-secret-name))
    (is (= "MURAKUMO_TOKEN_SECRET" secret/token-secret-env))))

(deftest env-fetch-rejects-bad-maps
  (is (thrown? Exception (secret/env-fetch {})))
  (is (thrown? Exception (secret/env-fetch {"a" 1}))))
