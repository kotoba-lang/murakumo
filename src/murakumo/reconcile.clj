;; murakumo.reconcile — the wadm half of murakumo.
;;
;; `deploy` is imperative (compile → distribute → publish, once). This namespace
;; is the DECLARATIVE control loop: you write a fleet manifest (`murakumo.app.edn`)
;; that says *what should be running and with what spread*, and `reconcile` folds
;; the live placement (from dash/collect) against it, computes the drift, and either
;; reports it (--dry-run) or converges the fleet to it (--apply / --watch).
;;
;; This mirrors the kotoba mesh ADR (docs/ADR-kotoba-mesh-wasm-hosting.md, L5):
;; desired state AND observed state are both datoms; the reconciler is just their
;; diff. So the CORE of this file is PURE (desired/observed → plan) and unit-tested
;; offline (test/murakumo/reconcile_test.clj) — no SSH, no fleet, deterministic.
;; The IMPURE shell (collect / deploy / persist) wraps that pure core.

(ns murakumo.reconcile
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [murakumo.connect :as connect]
            [murakumo.dash :as dash]
            [murakumo.fleet :as fleet])
  (:import (java.security MessageDigest)
           (java.util Base64)))

;; ── PURE core (offline-testable) ─────────────────────────────────────────────

(defn eligible-nodes
  "Node names whose labels/roles/reach satisfy an app's `:placement` constraint —
   i.e. where the lattice auction is *allowed* to place this component. A node is
   eligible when every label in `:placement {:labels …}` matches, every role in
   `:placement {:roles […]}` is present, and (if `connect` is given) the node can
   REACH every client class in `:placement {:reach […]}` per connect.edn. No
   constraint ⇒ every node is eligible."
  ([fleet placement] (eligible-nodes fleet placement nil))
  ([fleet {:keys [labels roles reach] :as _placement} connect-spec]
   (->> (:nodes fleet)
        (filter (fn [n]
                  (and (every? (fn [[k v]] (= v (get (:labels n) k))) labels)
                       (every? (set (:roles n)) roles)
                       ;; reach degrades OPEN when no connect.edn is present (a
                       ;; missing connectivity SSoT is an operator-setup issue, not
                       ;; a reason to silently block every placement).
                       (or (empty? reach)
                           (nil? connect-spec)
                           (connect/serves-all? connect-spec n reach)))))
        (mapv :name))))

(defn observed-hosts
  "From a dash snapshot, build `cid → #{node-name …}` of where each component CID
   is actually placed + running (the node's `:hosted` = CIDs it executed)."
  [snapshot]
  (reduce (fn [m n]
            (reduce (fn [m cid] (update m cid (fnil conj #{}) (:name n)))
                    m (:hosted n)))
          {} (:nodes snapshot)))

(defn- pick-targets
  "Deterministically choose `n` placement targets from `candidates`, preferring the
   nodes that currently host the FEWEST components (spread the load), then by name
   for stability. `load-by-node` is name → hosted-count."
  [candidates n load-by-node]
  (->> candidates
       (sort-by (juxt #(get load-by-node % 0) identity))
       (take (max 0 n))
       vec))

(defn reconcile-app
  "Pure per-app reconciliation: desired (manifest) vs observed (snapshot) → a
   decision. Returns a map with the action and enough context to act/report.

   action ∈ #{:needs-build :satisfied :place :over :blocked}
     :needs-build — no :cid (must compile :manifest first; placement unknown)
     :satisfied   — running count == desired, no misplacement
     :place       — under-replicated; `:targets` are where to deploy
     :over        — running count > desired (we report, we do not evict)
     :blocked     — under-replicated but no eligible node left to place on
                    (incl. nobody able to REACH the app's required client class)"
  [fleet snapshot connect-spec {:keys [name cid replicas placement] :as app}]
  (let [desired   (or replicas 1)
        eligible  (set (eligible-nodes fleet placement connect-spec))
        hosts     (get (observed-hosts snapshot) cid #{})
        running   (set/intersection hosts eligible)
        misplaced (set/difference hosts eligible)   ; running where NOT allowed
        deficit   (max 0 (- desired (count running)))
        load      (into {} (map (fn [n] [(:name n) (count (:hosted n))]) (:nodes snapshot)))
        candidates (vec (sort (set/difference eligible running)))
        base      {:app name :cid cid :desired desired :manifest (:manifest app)
                   :reach (vec (:reach placement))
                   :eligible (vec (sort eligible))
                   :running (vec (sort running))
                   :misplaced (vec (sort misplaced))
                   :deficit deficit}]
    (cond
      (nil? cid)
      (assoc base :action :needs-build
             :reason "no :cid — compile :manifest (clj→wasm) to resolve a CID first")

      (> (count running) desired)
      (assoc base :action :over
             :reason (format "%d running > %d desired (no auto-evict; lower :replicas or stop a node)"
                             (count running) desired))

      (and (zero? deficit) (empty? misplaced))
      (assoc base :action :satisfied)

      (pos? deficit)
      (let [targets (pick-targets candidates deficit load)]
        (if (empty? targets)
          (assoc base :action :blocked
                 :reason (format "need %d more but no eligible node free (eligible=%d, running=%d)"
                                 deficit (count eligible) (count running)))
          (assoc base :action :place :targets targets)))

      :else (assoc base :action :satisfied))))

(defn reconcile-plan
  "Pure whole-fleet plan: run `reconcile-app` over every app in the manifest.
   `connect-spec` (connect.edn) lets placement honour `:reach`; nil ⇒ reach is a
   no-op. Returns `{:ts … :apps [decision …]}` (`:ts` passed in — the sandbox has
   no wall-clock)."
  [fleet snapshot connect-spec manifest ts]
  {:ts ts
   :fleet (:fleet/name fleet)
   :apps (mapv #(reconcile-app fleet snapshot connect-spec %) (:apps manifest))})

(defn plan-converged?
  "True when no app needs action (everything :satisfied)."
  [plan]
  (every? #(= :satisfied (:action %)) (:apps plan)))

;; ── reporting ────────────────────────────────────────────────────────────────

(defn- fmt-cid [cid] (if cid (subs cid 0 (min 16 (count cid))) "—"))

(defn print-plan
  "Render a reconcile plan as a fleet-operator table."
  [plan]
  (println (format "reconcile %s  @ %s" (or (:fleet plan) "fleet") (:ts plan)))
  (println (format "  %-14s %-10s %-7s %-7s %-9s %s"
                   "APP" "CID" "DESIRED" "RUNNING" "ACTION" "DETAIL"))
  (doseq [a (:apps plan)]
    (let [detail (case (:action a)
                   :place   (str "→ " (str/join "," (:targets a)))
                   :satisfied (if (seq (:running a)) (str "on " (str/join "," (:running a))) "")
                   (:reason a ""))]
      (println (format "  %-14s %-10s %-7d %-7d %-9s %s"
                       (:app a) (fmt-cid (:cid a)) (:desired a) (count (:running a))
                       (clojure.core/name (:action a)) detail))
      (when (seq (:reach a))
        (println (format "  %-14s %-10s reach: %s → eligible(by transport)=%s"
                         "" "" (str/join "," (map clojure.core/str (:reach a)))
                         (str/join "," (:eligible a)))))
      (when (seq (:misplaced a))
        (println (format "  %-14s %-10s drift: running on non-eligible node(s): %s"
                         "" "" (str/join "," (:misplaced a))))))))

;; ── persist (desired/observed/drift → Datom log, append-only as-of) ──────────
;; Same graph as dash ('murakumo-fleet'), a distinct $type so reconcile history is
;; queryable alongside the heartbeat snapshots. Reuses dash/graph-cid (public).

(defn- operator-seed [fleet]
  (or (System/getenv (:fleet/operator-seed-env fleet)) (System/getenv "MURAKUMO_OPERATOR_SEED")))

(defn- bin [] (let [b (str (System/getProperty "user.dir") "/bin/kotoba")]
                (if (.exists (java.io.File. b)) b "kotoba")))

(defn- did-for [seed] (str/trim (str (:out (p/sh (bin) "did-derive" seed)))))

(defn- b64url [^bytes b] (-> (.encodeToString (Base64/getUrlEncoder) b) (str/replace "=" "")))

(defn- op-token [did]
  (str (b64url (.getBytes "{\"alg\":\"HS256\",\"typ\":\"JWT\"}" "UTF-8")) "."
       (b64url (.getBytes (format "{\"sub\":\"%s\",\"exp\":9999999999}" did) "UTF-8")) "."
       "kotoba-cli-media"))

(def ^:private persist-port 18098)
(def ^:private rec-seq (atom 0))

(defn persist-plan!
  "Write the reconcile plan as an atproto record into 'murakumo-fleet' — one tx per
   reconcile ⇒ the fleet's desired-vs-observed history is itself a queryable Datom
   chain. Best-effort; returns true on a recorded tx."
  [fleet token target plan]
  (try
    (let [gcid (dash/graph-cid "murakumo-fleet")
          n (swap! rec-seq inc)
          rkey (str "rec-" (System/currentTimeMillis) "-" n)
          port (fleet/node-port fleet target)]
      (p/sh "bash" "-c" (format "pgrep -f '%d:localhost:%d %s' >/dev/null 2>&1 || ssh -o BatchMode=yes -fN -L %d:localhost:%d %s"
                                persist-port port (:host target) persist-port port (:host target)))
      (Thread/sleep 400)
      (let [body (json/generate-string
                  {:graph gcid
                   :uri (format "at://did:web:etzhayyim.com:murakumo/com.murakumo.fleet.reconcile/%s" rkey)
                   :operation "create" :cid (dash/graph-cid rkey)
                   :record {:$type "com.murakumo.fleet.reconcile"
                            :ts (:ts plan) :fleet (:fleet plan)
                            :converged (plan-converged? plan)
                            :apps (mapv (fn [a] {:app (:app a) :cid (:cid a)
                                                 :desired (:desired a) :running (count (:running a))
                                                 :action (clojure.core/name (:action a))
                                                 :targets (vec (:targets a))}) (:apps plan))
                            :plan (json/generate-string plan)}})
            r (p/sh "curl" "-s" "-m" "6" "-X" "POST"
                    (format "http://localhost:%d/xrpc/com.etzhayyim.apps.kotoba.atproto.repo.write" persist-port)
                    "-H" (str "Authorization: Bearer " token) "-H" "content-type: application/json"
                    "-d" body)]
        (some? (re-find #"\"status\":\"ok\"" (str (:out r))))))
    (catch Exception e (binding [*out* *err*] (println "reconcile persist error:" (.getMessage e))) false)))

;; ── apply (converge) ─────────────────────────────────────────────────────────
;; Placement is delegated to the existing `deploy` path: publishing an app's
;; triggers/routes to one node gossips it fleet-wide and the auction places it on
;; eligible nodes. We re-publish for each app still short of its desired replicas
;; (the auction + placement constraints do the node selection; murakumo's job is to
;; keep *declaring* the desired set until observed == desired).

(defn- apply-app!
  "Converge one app by (re)publishing it via `murakumo deploy`. `deploy-fn` is
   injected so the pure->impure boundary stays testable. Only acts on :place."
  [deploy-fn manifest-dir a]
  (when (= :place (:action a))
    (println (format "  applying %s → publish (auction will place on eligible: %s)"
                     (:app a) (str/join "," (:targets a))))
    (deploy-fn a manifest-dir)))

;; ── command ──────────────────────────────────────────────────────────────────

(defn- parse-flags [args]
  (reduce (fn [m a]
            (cond
              (= a "--dry-run") (assoc m :dry-run true)
              (= a "--apply")   (assoc m :apply true)
              (str/starts-with? a "--watch") (assoc m :watch (let [[_ v] (str/split a #"=")]
                                                               (if v (Integer/parseInt v) 30)))
              (str/starts-with? a "--snapshot=") (assoc m :snapshot (subs a 11))
              (str/starts-with? a "--") m
              :else (assoc m :manifest a)))
          {} args))

(defn- load-snapshot
  "Observed state: a recorded snapshot file (offline/testing) or a live fleet poll."
  [fleet snapshot-file]
  (if snapshot-file
    (edn/read-string (slurp snapshot-file))
    (dash/collect fleet)))

(defn cmd-reconcile
  "Declarative fleet reconcile (murakumo's wadm). Usage:
     reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]] [--snapshot=f]
   --dry-run (default) prints the desired-vs-observed plan and exits.
   --apply re-publishes under-replicated apps so the auction converges the fleet.
   --watch loops apply every N s and persists each plan to the Datom log.
   Placement reach is honoured via connect.edn (the single connectivity SSoT)."
  [fleet [_ & args] deploy-fn]
  (let [{:keys [manifest dry-run apply watch snapshot]} (parse-flags args)]
    (when-not manifest
      (binding [*out* *err*] (println "usage: reconcile <murakumo.app.edn> [--dry-run|--apply|--watch[=secs]]"))
      (System/exit 2))
    (let [manifest-dir (str/replace manifest #"/[^/]+$" "")
          man (edn/read-string (slurp manifest))
          conn (connect/load-connect)   ; single connectivity description (may be nil)
          do-once (fn [ts]
                    (let [fleet* (fleet/enrich fleet)
                          snap (load-snapshot fleet* snapshot)
                          plan (reconcile-plan fleet* snap conn man ts)]
                      (print-plan plan)
                      (when (and apply (not dry-run))
                        (doseq [a (:apps plan)] (apply-app! deploy-fn manifest-dir a)))
                      plan))]
      (cond
        watch
        (let [seed (operator-seed fleet)
              token (when seed (op-token (did-for seed)))
              target (first (filter #(= "asher" (:name %)) (:nodes fleet)))]
          (when-not token (println "(no MURAKUMO_OPERATOR_SEED → watch without Datom persistence)"))
          (println (format "── reconcile --watch (every %ds) ; Ctrl-C to stop ──" watch))
          (loop []
            (let [plan (do-once (.toString (java.time.Instant/now)))]
              (when (and token target) (persist-plan! fleet token target plan))
              (when (plan-converged? plan) (println "  ✓ converged")))
            (Thread/sleep (* 1000 watch))
            (recur)))

        :else
        (do (do-once (.toString (java.time.Instant/now)))
            (when-not apply
              (println "\n(dry-run; re-run with --apply to converge, or --watch to keep it converged)")))))))
