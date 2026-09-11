(ns murakumo.secret-kagi-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.secret :as secret]))

(deftest kagi-fetch-resolves-mapped-ref-via-one-shot-getter
  (let [calls (atom [])
        fetch (secret/kagi-fetch
               {"murakumo-token" "kagi:murakumo/token"}
               (fn [ref]
                 (swap! calls conj ref)
                 (when (= ref "kagi:murakumo/token") "s3cret")))]
    (is (= {:tag :value :value "s3cret"}
           (fetch {:name "murakumo-token"})))
    (is (= ["kagi:murakumo/token"] @calls))
    (is (= :secret/not-found (:code (fetch {:name "unknown"}))))))

(deftest kagi-fetch-rejects-blank-getter-results
  (let [fetch (secret/kagi-fetch {"a" "ref-a"} (constantly ""))]
    (is (= :secret/empty (:code (fetch {:name "a"}))))))
