;; Optional cljs oracle load surface — exercisable on the JVM via register-kir!
;; and set-resource-loader! (same APIs nbb uses).

(ns murakumo.kotoba-oracle-cljs-load-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.kir :as ir]
            [murakumo.fleet.inventory :as inv]
            [murakumo.kotoba.oracle :as oracle]
            [murakumo.task.plan :as task]))

(deftest register-kir-bypasses-resource-read
  (oracle/clear-cache!)
  (let [live (edn/read-string
              (slurp (io/resource "murakumo/oracle/task_plan_core.kir.edn")))]
    (oracle/register-kir! :task-plan live)
    (is (oracle/ready? :task-plan))
    (is (= (ir/execute live 'default-max-slots [])
           (oracle/call :task-plan 'default-max-slots [])))
    (oracle/clear-cache!)
    ;; classpath load still works after clear
    (is (oracle/ready? :task-plan))))

(deftest set-resource-loader-injects-edn-text
  (oracle/clear-cache!)
  (let [path "murakumo/oracle/fleet_inventory_core.kir.edn"
        text (slurp (io/resource path))
        prev (oracle/set-resource-loader!
              (fn [p]
                (when (= p path) text)))]
    (try
      (is (oracle/ready? :fleet-inventory))
      (is (= 8077 (oracle/call :fleet-inventory 'default-control-port [])))
      (finally
        (oracle/set-resource-loader! prev)
        (oracle/clear-cache!)))))

(deftest task-failed-uses-oracle-when-ready
  (is (oracle/ready? :task-plan))
  (is (true? (task/failed? {:exit 1})))
  (is (true? (task/failed? {:error "x"})))
  (is (false? (task/failed? {:exit 0}))))

(deftest fleet-inventory-uses-oracle-when-ready
  (is (oracle/ready? :fleet-inventory))
  (is (= 8077 (inv/node-port {} {})))
  (is (= 9001 (inv/node-port {:fleet/port 9001} {})))
  (is (= 8080 (inv/node-port {} {:port 8080})))
  (is (= "http://localhost:8080/health"
         (inv/node-health-url {} {:port 8080}))))

(deftest catalog-still-complete
  (is (= 32 (oracle/catalog-count)))
  (is (some #{:task-plan} (oracle/catalog-ids)))
  (is (some #{:fleet-inventory} (oracle/catalog-ids))))
