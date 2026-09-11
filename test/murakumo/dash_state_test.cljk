;; murakumo.dash-state-test — offline tests for portable dashboard state helpers.

(ns murakumo.dash-state-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.dash.state :as state]))

(def snapshot
  {:ts "2026-06-30T00:00:00Z"
   :fleet "test"
   :nodes [{:name "asher" :health "ok" :links 2 :hosted ["bafyAAA" "bafyBBB"]}
           {:name "judah" :health "down" :links 0 :hosted []}]})

(deftest snapshot-record-shape
  (is (= snapshot
         (state/snapshot {:fleet/name "test"} "2026-06-30T00:00:00Z"
                         [{:name "asher" :health "ok" :links 2 :hosted ["bafyAAA" "bafyBBB"]}
                          {:name "judah" :health "down" :links 0 :hosted []}])))
  (is (= [{:action :probe :node {:name "asher" :host "asher" :online? true}}
          {:action :down :node {:name "judah" :host "judah" :online? true}}
          {:action :down :node {:name "levi" :host "levi" :online? false}}]
         (state/collect-node-plans
          [{:name "asher" :host "asher" :online? true}
           {:name "judah" :host "judah" :online? true}
           {:name "levi" :host "levi" :online? false}]
          #(= "asher" (:host %)))))
  (is (= [{:node "asher" :cid "bafyAAA"} {:node "asher" :cid "bafyBBB"}]
         (state/placements snapshot)))
  (is (= 2 (state/links-total snapshot)))
  (is (= {:$type "com.murakumo.fleet.snapshot"
          :ts "2026-06-30T00:00:00Z"
          :fleet "test"
          :nodes 2
          :links_total 2
          :placements [{:node "asher" :cid "bafyAAA"} {:node "asher" :cid "bafyBBB"}]
          :snapshot "{\"ok\":true}"}
         (state/snapshot-record snapshot "{\"ok\":true}"))))

(deftest diff-alerts-detects-liveness-and-placement-changes
  (testing "nil previous snapshot yields no alerts"
    (is (nil? (state/diff-alerts nil snapshot))))
  (let [prev {:ts "t0"
              :nodes [{:name "asher" :health "ok" :links 3 :hosted ["bafyAAAAAAAAAAAAAA" "bafyKEEP"]}
                      {:name "judah" :health "down" :links 0 :hosted []}
                      {:name "levi" :health "ok" :links 1 :hosted []}]}
        curr {:ts "t1"
              :nodes [{:name "asher" :health "ok" :links 1 :hosted ["bafyKEEP"]}
                      {:name "judah" :health "ok" :links 0 :hosted []}
                      {:name "levi" :health "down" :links 0 :hosted []}]}
        alerts (state/diff-alerts prev curr)]
    (is (= #{"links degraded 3→1" "component evicted: bafyAAAAAAAAAA"
             "node recovered" "node went DOWN" "lost all mesh links (1→0)"}
           (set (map :msg alerts))))
    (is (= #{"asher" "judah" "levi"} (set (map :node alerts))))))

(deftest dashboard-selection-and-display-helpers
  (is (= 12 (state/query-at "at=12")))
  (is (nil? (state/query-at "page=1")))
  (testing "query-at only matches the exact key `at`, not a longer key that
            happens to END in \"at=<digits>\" as a substring -- regression:
            the regex #\"at=(\\d+)\" was unanchored, so \"format=5\" matched
            the substring \"at=5\" inside \"form\" + \"at=5\" and was
            misread as at=5 (a request to time-travel to history offset 5)
            instead of nil (no history offset requested)"
    (is (nil? (state/query-at "format=5")))
    (is (nil? (state/query-at "chat=5")))
    (is (nil? (state/query-at "combat=12")))
    (is (nil? (state/query-at "flat=5")))
    (is (= 12 (state/query-at "page=1&at=12")) "still matches the real key when combined with other params")
    (is (= 7 (state/query-at "format=5&at=7")) "still matches the real key even alongside a colliding-shaped param"))
  (is (= {:port 8899 :interval 15} (state/dashboard-options [])))
  (is (= {:port 9000 :interval 5} (state/dashboard-options ["9000" "5"])))
  (is (= 5000 (state/interval-sleep-ms 5)))
  (is (= 0 (state/clamp-at -1 3)))
  (is (= 2 (state/clamp-at 99 3)))
  (is (= "ok" (state/health-class {:health "ok"})))
  (is (= "down" (state/health-class {:health "no-resp"})))
  (is (= "bafy12345678901234" (state/short-hosted-cid "bafy12345678901234567890")))
  (is (= "bafyA bafyB" (state/hosted-summary {:hosted ["bafyA" "bafyB"]})))
  (is (nil? (state/hosted-summary {:hosted []})))
  (let [history [{:ts "old"} {:ts "new"}]
        cache {:ts "cache"}]
    (is (= {:at 0 :total 2 :live? true :snapshot {:ts "new"}}
           (state/selected-snapshot history cache 0)))
    (is (= {:at 1 :total 2 :live? false :snapshot {:ts "old"}}
           (state/selected-snapshot history cache 1)))
    (is (= {:at 0 :total 0 :live? true :snapshot cache}
           (state/selected-snapshot [] cache 99))))
  (is (= [{:id 3} {:id 2}]
         (state/recent-alerts [{:id 1} {:id 2} {:id 3}] 2)))
  (is (= [2 3 4]
         (state/append-capped [1 2 3] 3 4)))
  (is (= [3 4 5]
         (state/concat-capped [1 2 3] 3 [4 5]))))

(deftest dashboard-html-rendering-is-pure
  (let [html (state/render-html
              {:ts "t1"
               :fleet "test"
               :nodes [{:name "asher" :health "ok" :wasm "ready" :links 2 :p2p 4001
                        :hosted ["bafy12345678901234567890"]}
                       {:name "judah" :health "down" :links 0 :hosted []}]}
              1
              3
              false
              7
              [{:level "warn" :node "asher" :msg "links degraded 3→2" :ts "t1"}])]
    (is (re-find #"murakumo — test · time-travel" html))
    (is (re-find #"snapshot t1 · persisted 7 snapshots" html))
    (is (re-find #"history 2/3" html))
    (is (re-find #"\?at=2'>◀ older" html))
    (is (re-find #"\?at=0'>newer ▶" html))
    (is (re-find #"links degraded 3→2" html))
    (is (re-find #"bafy12345678901234" html))
    (is (re-find #"<span class=muted>—</span>" html))))

(deftest dashboard-http-response-shapes-are-stable
  (is (= {:status 200
          :headers {"content-type" "application/json"}
          :body "{\"ok\":true}"}
         (state/json-response "{\"ok\":true}")))
  (is (= {:status 200
          :headers {"content-type" "text/html; charset=utf-8"}
          :body "<html/>"}
         (state/html-response "<html/>"))))

(deftest probe-output-parsing-is-pure
  (let [out "H:{\"subsystems\":{\"wasm_executor\":\"ok\"}}\nL:2\nP:bafyA,bafyB,\n"
        lines (state/probe-lines out)]
    (is (re-find #"http://localhost:8077/health"
                 (state/probe-command 8077)))
    (is (re-find #"trigger: executed"
                 (state/probe-command 8077)))
    (is (= {"H" "{\"subsystems\":{\"wasm_executor\":\"ok\"}}" "L" "2" "P" "bafyA,bafyB,"}
           lines))
    (is (= 2 (state/parse-links (get lines "L"))))
    (is (= 0 (state/parse-links "not-int")))
    (is (= ["bafyA" "bafyB"] (state/parse-hosted (get lines "P"))))
    (is (= {:subsystems {:wasm_executor "ok"}}
           (state/parse-health (fn [_] {:subsystems {:wasm_executor "ok"}}) "{}")))
    (is (nil? (state/parse-health (fn [_] (throw (Exception. "bad"))) "{bad")))
    (is (= {:node {:name "asher"}
            :health-json {:ok true}
            :links "2"
            :p2p-port 4001}
           (state/status-row-input {:name "asher"} {:ok true} "2" 4001)))
    (is (= {:name "asher"
            :host "asher"
            :ip "100.0.0.1"
            :online true
            :health "ok"
            :wasm "ok"
            :links 2
            :p2p 4001
            :hosted ["bafyA" "bafyB"]}
           (state/probe-node {:name "asher" :host "asher" :ip "100.0.0.1" :online? true}
                             {:subsystems {:wasm_executor "ok"}}
                             lines
                             4001)))
    (is (= {:name "judah" :online false :health "down" :links 0 :hosted []}
           (state/down-node {:name "judah"})))))
