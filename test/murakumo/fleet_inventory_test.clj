;; murakumo.fleet-inventory-test — offline tests for portable inventory helpers.

(ns murakumo.fleet-inventory-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.fleet.inventory :as inv]))

(def fleet
  {:fleet/port 9000
   :nodes [{:name "asher"}
           {:name "judah" :port 9010}
           {:name "levi"}]})

(deftest node-port-defaults
  (testing "node port overrides fleet port"
    (is (= 9010 (inv/node-port fleet {:name "judah" :port 9010}))))
  (testing "fleet port is the default"
    (is (= 9000 (inv/node-port fleet {:name "asher"}))))
  (testing "8077 is the final fallback"
    (is (= 8077 (inv/node-port {:nodes []} {:name "solo"}))))
  (is (= "http://localhost:9010/health"
         (inv/node-health-url fleet {:name "judah" :port 9010}))))

(deftest selector-semantics
  (testing "nil/all selects every node"
    (is (= ["asher" "judah" "levi"] (mapv :name (inv/select fleet nil))))
    (is (= ["asher" "judah" "levi"] (mapv :name (inv/select fleet "all")))))
  (testing "comma selectors preserve fleet order and ignore unknown names"
    (is (= ["asher" "levi"] (mapv :name (inv/select fleet "levi,asher,missing")))))
  (is (= {:name "judah" :port 9010}
         (inv/node-named fleet "judah")))
  (is (nil? (inv/node-named fleet "missing"))))

(deftest tailscale-status-parsing
  (let [out "100.64.0.1 asher user@ macOS active; direct 1.2.3.4:41641\n100.64.0.2 judah user@ macOS offline\nnoise\n"]
    (is (= {"asher" {:ip "100.64.0.1" :online? true}
            "judah" {:ip "100.64.0.2" :online? false}}
           (inv/parse-tailscale-status out)))
    (is (= {"asher" {:ip "100.64.0.1" :online? true}
            "judah" {:ip "100.64.0.2" :online? false}}
           (inv/tailscale-status-result {:exit 0 :out out})))
    (is (= {} (inv/tailscale-status-result {:exit 127 :out out})))))

(deftest fleet-enrichment-is-pure
  (is (= {:fleet/port 9000
          :nodes [{:name "asher" :ip "100.64.0.1" :online? true}
                  {:name "judah" :port 9010}]}
         (inv/enrich {:fleet/port 9000
                      :nodes [{:name "asher"}
                              {:name "judah" :port 9010}]}
                     {"asher" {:ip "100.64.0.1" :online? true}}))))
