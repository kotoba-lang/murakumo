;; murakumo.reconcile-test — offline unit tests for the PURE reconcile core.
;; No SSH, no fleet, no kotoba binary: synthetic fleet + snapshot → assert the plan.
;; Run: `bb test` (from the murakumo dir).

(ns murakumo.reconcile-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.connect :as c]
            [murakumo.reconcile.plan :as r]))

(def fleet
  {:fleet/name "test-mesh"
   :nodes [{:name "a" :roles ["pin" "compute"]          :labels {:zone "jp" :tier "edge"}}
           {:name "b" :roles ["pin" "compute"]          :labels {:zone "jp" :tier "edge"}}
           {:name "c" :roles ["pin" "compute"]          :labels {:zone "jp" :tier "edge"}}
           {:name "d" :roles ["pin" "compute" "relay"]  :labels {:zone "jp" :role "canary"}}
           {:name "e" :roles ["pin"]                    :labels {:zone "us"}}]})

;; heartbeat is hosted on a (eligible) AND e (NOT eligible → misplaced drift).
(def snap
  {:nodes [{:name "a" :hosted ["bafyHEART"]}
           {:name "b" :hosted []}
           {:name "c" :hosted []}
           {:name "d" :hosted []}
           {:name "e" :hosted ["bafyHEART"]}]})

(deftest eligible-nodes-honours-labels-and-roles
  (testing "zone=jp ∧ role compute → a b c d (e is us + no compute)"
    (is (= ["a" "b" "c" "d"] (r/eligible-nodes fleet {:labels {:zone "jp"} :roles ["compute"]}))))
  (testing "role=canary label → only d"
    (is (= ["d"] (r/eligible-nodes fleet {:labels {:role "canary"}}))))
  (testing "no constraint → every node"
    (is (= ["a" "b" "c" "d" "e"] (r/eligible-nodes fleet {})))))

(deftest observed-hosts-inverts-snapshot
  (is (= {"bafyHEART" #{"a" "e"}} (r/observed-hosts snap))))

(deftest place-when-under-replicated
  (let [d (r/reconcile-app fleet snap nil
                           {:name "heartbeat" :cid "bafyHEART" :replicas 3
                            :placement {:labels {:zone "jp"} :roles ["compute"]}})]
    (testing "running counts only eligible hosts; e is surfaced as misplaced drift"
      (is (= ["a"] (:running d)))
      (is (= ["e"] (:misplaced d))))
    (testing "deficit drives placement onto the least-loaded eligible nodes, deterministically"
      (is (= :place (:action d)))
      (is (= 2 (:deficit d)))
      (is (= ["b" "c"] (:targets d))))))   ; b,c (load 0) before d — and d is the 3rd, not needed

(deftest satisfied-when-met-and-no-drift
  (let [snap2 {:nodes [{:name "a" :hosted ["bafyHEART"]}
                       {:name "b" :hosted []} {:name "c" :hosted []}
                       {:name "d" :hosted []} {:name "e" :hosted []}]}
        d (r/reconcile-app fleet snap2 nil
                           {:name "heartbeat" :cid "bafyHEART" :replicas 1
                            :placement {:labels {:zone "jp"} :roles ["compute"]}})]
    (is (= :satisfied (:action d)))
    (is (= ["a"] (:running d)))
    (is (empty? (:misplaced d)))))

(deftest blocked-when-no-eligible-node
  (let [d (r/reconcile-app fleet snap nil
                           {:name "ghost" :cid "bafyGHOST" :replicas 1
                            :placement {:labels {:zone "antarctica"}}})]
    (is (= :blocked (:action d)))
    (is (empty? (:eligible d)))))

(deftest over-when-too-many-running
  (let [snap3 {:nodes [{:name "a" :hosted ["bafyHEART"]}
                       {:name "b" :hosted ["bafyHEART"]}
                       {:name "c" :hosted []} {:name "d" :hosted []} {:name "e" :hosted []}]}
        d (r/reconcile-app fleet snap3 nil
                           {:name "heartbeat" :cid "bafyHEART" :replicas 1
                            :placement {:labels {:zone "jp"} :roles ["compute"]}})]
    (is (= :over (:action d)))
    (is (= ["a" "b"] (:running d)))))

(deftest needs-build-without-cid
  (let [d (r/reconcile-app fleet snap nil
                           {:name "uncompiled" :replicas 1 :placement {}})]
    (is (= :needs-build (:action d)))))

;; ── connect.edn transport-reach wiring ───────────────────────────────────────

(def connect
  {:connect/version 1
   :classes {:native  {:read [:http] :live [:quic]                :dialable true}
             :edge    {:read [:http] :live [:wss]                  :dialable true}
             :browser {:read [:http] :live [:webrtc :webtransport] :dialable false}}
   :default-class :native})

(deftest serves-reach-resolves-planes
  (let [native {:name "a"}]                       ; no :class ⇒ :native
    (testing "read plane is universal (CID-over-HTTP)"
      (is (true?  (c/serves-reach? connect native :browser/read)))
      (is (true?  (c/serves-reach? connect native :edge/read))))
    (testing "live plane needs a shared transport — native(quic) can't reach browser(webrtc)"
      (is (false? (c/serves-reach? connect native :browser/live)))
      (is (false? (c/serves-reach? connect native :edge/live)))   ; quic ∉ {wss}
      (is (true?  (c/serves-reach? connect native :native/live))))))   ; quic ∩ quic

(deftest eligible-honours-reach-via-connect
  (testing "with a :reach browser/live constraint, native quic-only nodes are NOT eligible"
    (is (empty? (r/eligible-nodes fleet {:roles ["compute"] :reach [:browser/live]} connect))))
  (testing "a :reach browser/read constraint keeps every compute node (http is universal)"
    (is (= ["a" "b" "c" "d"]
           (r/eligible-nodes fleet {:labels {:zone "jp"} :roles ["compute"] :reach [:browser/read]} connect))))
  (testing "no connect spec ⇒ reach is a no-op (degrades, never blocks)"
    (is (seq (r/eligible-nodes fleet {:roles ["compute"] :reach [:browser/live]} nil)))))

(deftest real-connect-edn-native-speaks-webrtc
  ;; Guard the 2026-06-27 flip: connect.edn :native :live now includes :webrtc, so
  ;; native fleet nodes are eligible for :reach :browser/live apps. Backed by
  ;; kotoba#229 (webrtc-direct transport + KOTOBA_WEBRTC listen) + provision render.
  (let [conn (c/load-connect "connect.edn")]
    (is (some? conn) "connect.edn loads")
    (is (true? (c/serves-reach? conn {:name "asher"} :browser/live))
        "native must serve browser/live after the flip")
    (is (true? (c/serves-reach? conn {:name "asher"} :native/live)))))

(deftest reach-unreachable-is-blocked
  (let [d (r/reconcile-app fleet snap connect
                           {:name "ui" :cid "bafyUI" :replicas 1
                            :placement {:roles ["compute"] :reach [:browser/live]}})]
    (testing "needs a browser-live node, fleet has none ⇒ blocked (honest, not silently placed)"
      (is (= :blocked (:action d)))
      (is (empty? (:eligible d))))))

(deftest reach-after-wiring-webrtc-into-native
  ;; the connect.edn WIRING KNOB: add :webrtc to native :live → browser-live reachable.
  (let [wired (assoc-in connect [:classes :native :live] [:quic :webrtc])
        d (r/reconcile-app fleet snap wired
                           {:name "ui" :cid "bafyUI" :replicas 1
                            :placement {:labels {:zone "jp"} :roles ["compute"] :reach [:browser/live]}})]
    (is (= :place (:action d)))
    (is (= ["b"] (:targets d)))))   ; a hosts bafyHEART (load 1) → least-loaded eligible is b

(deftest whole-plan-and-convergence
  (let [man {:apps [{:name "heartbeat" :cid "bafyHEART" :replicas 1
                     :placement {:labels {:zone "jp"} :roles ["compute"]}}
                    {:name "relay" :cid "bafyRELAY" :replicas 1
                     :placement {:labels {:role "canary"}}}]}
        plan (r/reconcile-plan fleet snap nil man "2026-06-27T00:00:00Z")]
    (testing "two apps planned; relay needs placement on d, so not converged"
      (is (= 2 (count (:apps plan))))
      (is (= :place (:action (second (:apps plan)))))
      (is (= ["d"] (:targets (second (:apps plan)))))
      (is (false? (r/plan-converged? plan)))
      (is (= ["relay"] (mapv :app (r/apply-apps plan)))))))

(deftest command-flags-are-pure-data
  (is (= {:manifest "murakumo.app.edn" :dry-run true :snapshot "snap.edn"}
         (r/parse-flags ["murakumo.app.edn" "--dry-run" "--snapshot=snap.edn"])))
  (is (= {:manifest "murakumo.app.edn" :apply true :watch 30}
         (r/parse-flags ["--apply" "--watch" "murakumo.app.edn"])))
  (is (= {:manifest "murakumo.app.edn" :watch 5}
         (r/parse-flags ["--ignored" "--watch=5" "murakumo.app.edn"])))
  (is (= :missing-manifest (r/reconcile-command-error {})))
  (is (nil? (r/reconcile-command-error {:manifest "murakumo.app.edn"})))
  (is (= 5000 (r/watch-sleep-ms 5))))

(deftest reconcile-record-shape
  (let [plan {:ts "t"
              :fleet "test"
              :apps [{:app "heartbeat" :cid "bafyHEART" :desired 2
                      :running ["a"] :action :place :targets ["b"]}]}
        rec (r/reconcile-record plan "{\"plan\":true}")]
    (is (= {:$type "com.murakumo.fleet.reconcile"
            :ts "t"
            :fleet "test"
            :converged false
            :apps [{:app "heartbeat" :cid "bafyHEART" :desired 2
                    :running 1 :action "place" :targets ["b"]}]
            :plan "{\"plan\":true}"}
           rec))))
