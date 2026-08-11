;; Optional cljs oracle load surface — exercisable on the JVM via register-kir!
;; and set-resource-loader! (same APIs nbb uses).
;;
;; T6.4 first slice: the *same* precompiled KIR product artifacts are the
;; execution source for pure helpers on the cljs/nbb load path — not a second
;; pure reimplementation. Host mirrors remain only as fail-closed fallback when
;; oracle is not ready (resource missing / load error).

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
  (is (<= 35 (oracle/catalog-count)))
  (is (some #{:task-plan} (oracle/catalog-ids)))
  (is (some #{:fleet-inventory} (oracle/catalog-ids))))

(defn- zero-arity-exports
  "Export symbols with empty :params in a loaded KIR document."
  [kir]
  (let [by-name (into {} (map (juxt :name identity) (:functions kir)))]
    (filterv (fn [sym]
               (let [f (get by-name sym)]
                 (and f (empty? (:params f)))))
             (:exports kir))))

(deftest t64-same-artifact-all-catalog-ids-ready
  "T6.4: every product-shell catalog id loads its shipped KIR (same artifact
   as JVM dual-source)."
  (oracle/clear-cache!)
  (is (<= 35 (oracle/catalog-count)))
  (doseq [id (sort (oracle/catalog-ids))]
    (testing (str id)
      (is (oracle/ready? id) (str "not ready: " id))
      (let [kir (oracle/load-kir id)]
        (is (map? kir))
        (is (seq (:exports kir)) (str "no exports: " id))))))

(deftest t64-same-artifact-zero-arity-exports-execute
  "T6.4: for each catalog core, every 0-arity export matches via oracle/call
   and ir/execute on the same loaded KIR document. Cores with only arity>0
   exports (e.g. infer-schedule) are still load-checked above."
  (oracle/clear-cache!)
  (doseq [id (sort (oracle/catalog-ids))]
    (testing (str id)
      (let [kir (oracle/load-kir id)
            zeros (zero-arity-exports kir)]
        (doseq [exp zeros]
          (testing (str exp)
            (is (= (oracle/call id exp [])
                   (ir/execute kir exp []))
                (str id " " exp " call≠ir"))))))))

(deftest t64-register-kir-full-catalog-injection
  "T6.4 cljs bundler path: register-kir! injects full catalog so pure
   execution uses the same KIR docs (simulates nbb/bundler preload).
   Note: on JVM, classpath resources still satisfy ready? even with a
   deny-loader — set-resource-loader! is the cljs inject point; this test
   proves register-kir! round-trips the shipped artifacts."
  (oracle/clear-cache!)
  (let [docs (into {}
                   (map (fn [id] [id (oracle/load-kir id)])
                        (oracle/catalog-ids)))]
    (oracle/clear-cache!)
    (doseq [[id kir] docs]
      (oracle/register-kir! id kir))
    (try
      (doseq [id (sort (oracle/catalog-ids))]
        (testing (str id)
          (is (oracle/ready? id) (str "register-kir missing " id))
          (let [kir (get docs id)
                zeros (zero-arity-exports kir)
                exp (first zeros)]
            (is (= kir (oracle/load-kir id))
                (str "register-kir did not pin doc for " id))
            (when exp
              (is (= (ir/execute kir exp [])
                     (oracle/call id exp [])))))))
      (finally
        (oracle/clear-cache!)))))

(deftest t64-require-ready-and-preload-catalog
  "T6.4 preload contract: require-ready! / preload-catalog! for entrypoints
   that delete cljs host mirrors (kekkai pilot)."
  (oracle/clear-cache!)
  (is (true? (oracle/require-ready! :kekkai-gate)))
  (oracle/clear-cache!)
  (is (= (oracle/catalog-count) (oracle/preload-catalog!)))
  (doseq [id (oracle/catalog-ids)]
    (is (true? (oracle/require-ready! id)) (str "not ready after preload: " id)))
  (is (= 3 (oracle/preload! [:dash-state :fleet-inventory :task-plan]))))
