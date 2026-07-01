;; murakumo.persist-test — offline tests for portable persistence helpers.

(ns murakumo.persist-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.identity :as identity]
            [murakumo.persist :as persist]))

(deftest repo-write-envelope-is-stable
  (is (= "murakumo-fleet" persist/fleet-graph-name))
  (is (= "com.murakumo.fleet.snapshot" persist/snapshot-collection))
  (is (= "com.murakumo.fleet.reconcile" persist/reconcile-collection))
  (is (= 18099 persist/snapshot-local-port))
  (is (= 18098 persist/reconcile-local-port))
  (is (= 400 persist/forward-settle-ms))
  (is (= (identity/graph-cid "murakumo-fleet")
         (persist/fleet-graph-cid)))
  (is (= "snap-1000-1" (persist/snapshot-rkey 1000 1)))
  (is (= "rec-1000-2" (persist/reconcile-rkey 1000 2)))
  (is (= "at://did:web:etzhayyim.com:murakumo/com.murakumo.fleet.snapshot/snap-1"
         (persist/repo-uri "com.murakumo.fleet.snapshot" "snap-1")))
  (is (= "http://localhost:18099/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write"
         (persist/repo-write-url 18099)))
  (is (= ["curl" "-s" "-m" "6" "-X" "POST"
          "http://localhost:18099/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write"
          "-H" "Authorization: Bearer tok"
          "-H" "content-type: application/json"
          "-d" "{\"ok\":true}"]
         (persist/repo-write-curl-argv 18099 "tok" "{\"ok\":true}")))
	  (is (= {:graph (identity/graph-cid "murakumo-fleet")
	          :uri "at://did:web:etzhayyim.com:murakumo/com.murakumo.fleet.snapshot/snap-1"
	          :operation "create"
	          :cid (identity/graph-cid "snap-1")
	          :record {:$type "com.murakumo.fleet.snapshot" :ts "t"}}
         (persist/repo-write-envelope
          (identity/graph-cid "murakumo-fleet")
	          "com.murakumo.fleet.snapshot"
	          "snap-1"
	          {:$type "com.murakumo.fleet.snapshot" :ts "t"})))
  (let [snapshot {:ts "t" :fleet "fleet" :nodes [{:name "asher" :links 2 :hosted ["bafy1"]}]}
        plan {:ts "t" :fleet "fleet" :apps [{:app "app" :cid "bafy1" :desired 1
                                             :running ["asher"] :action :satisfied}]}]
    (is (= {:graph (identity/graph-cid "murakumo-fleet")
            :uri "at://did:web:etzhayyim.com:murakumo/com.murakumo.fleet.snapshot/snap-1"
            :operation "create"
            :cid (identity/graph-cid "snap-1")
            :record {:$type "com.murakumo.fleet.snapshot"
                     :ts "t"
                     :fleet "fleet"
                     :nodes 1
                     :links_total 2
                     :placements [{:node "asher" :cid "bafy1"}]
                     :snapshot "{\"snapshot\":true}"}}
           (persist/snapshot-write-envelope "snap-1" snapshot "{\"snapshot\":true}")))
	    (is (= {:graph (identity/graph-cid "murakumo-fleet")
	            :uri "at://did:web:etzhayyim.com:murakumo/com.murakumo.fleet.reconcile/rec-1"
	            :operation "create"
            :cid (identity/graph-cid "rec-1")
            :record {:$type "com.murakumo.fleet.reconcile"
                     :ts "t"
                     :fleet "fleet"
                     :converged true
                     :apps [{:app "app"
                             :cid "bafy1"
                             :desired 1
                             :running 1
                             :action "satisfied"
                             :targets []}]
	                     :plan "{\"plan\":true}"}}
	           (persist/reconcile-write-envelope "rec-1" plan "{\"plan\":true}")))))

(deftest write-plans-are-stable
  (let [snapshot {:ts "t" :fleet "fleet" :nodes [{:name "asher" :links 2 :hosted ["bafy1"]}]}
        plan {:ts "t" :fleet "fleet" :apps [{:app "app" :cid "bafy1" :desired 1
                                             :running ["asher"] :action :satisfied}]}]
    (is (= {:local-port 18099
            :rkey "snap-1000-1"
            :envelope (persist/snapshot-write-envelope "snap-1000-1"
                                                       snapshot
                                                       "{\"snapshot\":true}")}
           (persist/snapshot-write-plan 1000 1 snapshot "{\"snapshot\":true}")))
    (is (= "pgrep -f '18099:localhost:8077 asher' >/dev/null 2>&1 || ssh -o BatchMode=yes -fN -L 18099:localhost:8077 asher"
           (persist/write-forward-command
            (persist/snapshot-write-plan 1000 1 snapshot "{\"snapshot\":true}")
            8077
            "asher")))
    (is (= ["curl" "-s" "-m" "6" "-X" "POST"
            "http://localhost:18099/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write"
            "-H" "Authorization: Bearer tok"
            "-H" "content-type: application/json"
            "-d" "{\"ok\":true}"]
           (persist/write-curl-argv
            (persist/snapshot-write-plan 1000 1 snapshot "{\"snapshot\":true}")
            "tok"
            "{\"ok\":true}")))
    (is (= {:local-port 18098
            :rkey "rec-1000-2"
            :envelope (persist/reconcile-write-envelope "rec-1000-2"
                                                        plan
                                                        "{\"plan\":true}")}
           (persist/reconcile-write-plan 1000 2 plan "{\"plan\":true}")))))

(deftest write-ok-detects-repo-status
  (is (true? (persist/write-ok? "{\"status\":\"ok\"}")))
  (is (false? (persist/write-ok? "{\"status\":\"error\"}")))
  (is (false? (persist/write-ok? nil))))
