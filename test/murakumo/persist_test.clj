;; murakumo.persist-test — offline tests for portable persistence helpers.

(ns murakumo.persist-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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

;; ── internal-trust header (ADR-2608124000, "clients first") ──────────────────
;; kotoba-server's require_internal_trust gate returns success while
;; KOTOBA_INTERNAL_SECRET is unset, and it is unset across this fleet — so
;; sending the header today changes nothing on the wire. These tests pin the
;; shape NOW so arming the server later is a one-variable decision rather than a
;; fleet-wide outage that, on this background write path, would be silent.
;; The values below are obviously synthetic; no real secret is read or generated.
(def ^:private synthetic-trust "synthetic-internal-trust-not-a-real-secret")

(deftest internal-trust-header-present-when-configured
  (is (= "x-internal-trust" persist/internal-trust-header-name))
  (is (= "KOTOBA_INTERNAL_SECRET" persist/internal-trust-env)
      "the same variable the server and the Cloudflare gateway read")
  (is (= (str "x-internal-trust: " synthetic-trust)
         (persist/internal-trust-header synthetic-trust)))
  (is (= ["curl" "-s" "-m" "6" "-X" "POST"
          "http://localhost:18099/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write"
          "-H" "Authorization: Bearer tok"
          "-H" "content-type: application/json"
          "-H" (str "x-internal-trust: " synthetic-trust)
          "-d" "{\"ok\":true}"]
         (persist/repo-write-curl-argv 18099 "tok" "{\"ok\":true}" synthetic-trust))
      "trust header is appended; Authorization and content-type are untouched"))

(deftest internal-trust-header-absent-when-unconfigured
  (is (nil? (persist/internal-trust-header nil)))
  (is (nil? (persist/internal-trust-header "")) "blank is absent, not an empty header")
  (is (nil? (persist/internal-trust-header "   ")))
  ;; the no-op property, asserted: with no secret the argv is byte-identical to
  ;; the argv this code has always produced.
  (let [expected ["curl" "-s" "-m" "6" "-X" "POST"
                  "http://localhost:18099/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write"
                  "-H" "Authorization: Bearer tok"
                  "-H" "content-type: application/json"
                  "-d" "{\"ok\":true}"]]
    (is (= expected (persist/repo-write-curl-argv 18099 "tok" "{\"ok\":true}" nil)))
    (is (= expected (persist/repo-write-curl-argv 18099 "tok" "{\"ok\":true}" "")))
    (is (= expected (persist/repo-write-curl-argv 18099 "tok" "{\"ok\":true}"))
        "3-arity keeps its historical shape, so existing assertions stay hermetic")
    (is (not-any? #(str/includes? (str %) "x-internal-trust") expected))))

(deftest internal-trust-absence-is-reported-not-silent
  (is (= :unconfigured (persist/internal-trust-status nil)))
  (is (= :unconfigured (persist/internal-trust-status "")))
  (is (= :configured (persist/internal-trust-status synthetic-trust))))

(deftest write-curl-argv-threads-trust-through
  (let [snapshot {:ts "t" :nodes []}
        plan (persist/snapshot-write-plan 1000 1 snapshot "{\"snapshot\":true}")]
    ;; positive control: Authorization still built from the operator token
    (is (= "Authorization: Bearer tok" (persist/auth-header "tok")))
    (is (= (persist/repo-write-curl-argv 18099 "tok" "{\"ok\":true}" synthetic-trust)
           (persist/write-curl-argv plan "tok" "{\"ok\":true}" synthetic-trust)))
    (is (= (persist/repo-write-curl-argv 18099 "tok" "{\"ok\":true}" nil)
           (persist/write-curl-argv plan "tok" "{\"ok\":true}")))))
