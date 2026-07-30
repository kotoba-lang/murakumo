;; murakumo.reconcile.plan — portable desired/observed -> reconcile plan core.
;;
;; No filesystem, subprocess, wall-clock, Java-only APIs, or live fleet access.
;; This namespace is the .cljc source of truth for the wadm-style planning logic;
;; murakumo.reconcile wraps it with CLI, collection, apply, and persistence.
;;
;; W6 product-shell authority (ADR-260728-w6-collection-record-types-pure-oracle +
;; ADR-260728-w6-reconcile-flag-tokens-pure-oracle +
;; ADR-260728-w6-reconcile-oracle-authority + flags):
;; pure scalar + flag/action tokens + classifiers + reconcile-record-type DELEGATE
;; to precompiled kotoba/reconcile_plan_core.kotoba →
;; resources/murakumo/oracle/reconcile_plan_core.kir.edn when oracle is loadable
;; (JVM classpath or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
;; Host remains: eligible-nodes / observed-hosts set algebra, variable-length
;; pick-targets sort, reason strings, parse-flags reduce fold. cljs mirrors as fallback.

(ns murakumo.reconcile.plan
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [murakumo.connect :as connect]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :reconcile-plan)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "Run oracle body; on failure use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn- oracle-str-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(def ^:private mirror-flag-dry-run "--dry-run")
(def ^:private mirror-flag-apply "--apply")
(def ^:private mirror-flag-watch "--watch")
(def ^:private mirror-flag-watch-eq-prefix "--watch=")
(def ^:private mirror-flag-snapshot-prefix "--snapshot=")
(def ^:private mirror-flag-dash-prefix "--")
(def ^:private mirror-action-satisfied "satisfied")
(def ^:private mirror-action-place "place")
(def ^:private mirror-action-over "over")
(def ^:private mirror-action-blocked "blocked")
(def ^:private mirror-action-needs-build "needs-build")

(def flag-dry-run
  "CLI token for dry-run. Kotoba when ready."
  (oracle-str-const 'flag-dry-run mirror-flag-dry-run))

(def flag-apply
  (oracle-str-const 'flag-apply mirror-flag-apply))

(def flag-watch
  (oracle-str-const 'flag-watch mirror-flag-watch))

(def flag-watch-eq-prefix
  (oracle-str-const 'flag-watch-eq-prefix mirror-flag-watch-eq-prefix))

(def flag-snapshot-prefix
  (oracle-str-const 'flag-snapshot-prefix mirror-flag-snapshot-prefix))

(def flag-dash-prefix
  (oracle-str-const 'flag-dash-prefix mirror-flag-dash-prefix))

(def action-satisfied
  (oracle-str-const 'action-satisfied mirror-action-satisfied))

(def action-place
  (oracle-str-const 'action-place mirror-action-place))

(def action-over
  (oracle-str-const 'action-over mirror-action-over))

(def action-blocked
  (oracle-str-const 'action-blocked mirror-action-blocked))

(def action-needs-build
  (oracle-str-const 'action-needs-build mirror-action-needs-build))

(def ^:private mirror-reconcile-record-type "com.murakumo.fleet.reconcile")

(def reconcile-record-type
  "Atproto $type / collection NSID for fleet reconcile records. Kotoba when ready."
  (oracle-str-const 'reconcile-record-type mirror-reconcile-record-type))


(defn eligible-nodes
  "Node names whose labels/roles/reach satisfy an app's `:placement` constraint.
   A node is eligible when every requested label matches, every requested role is
   present, and (if `connect-spec` is given) the node can reach every requested
   client class/plane. Missing connect-spec degrades reach constraints to no-op."
  ([fleet placement] (eligible-nodes fleet placement nil))
  ([fleet {:keys [labels roles reach] :as _placement} connect-spec]
   (->> (:nodes fleet)
        (filter (fn [n]
                  (and (every? (fn [[k v]] (= v (get (:labels n) k))) labels)
                       (every? (set (:roles n)) roles)
                       (or (empty? reach)
                           (nil? connect-spec)
                           (connect/serves-all? connect-spec n reach)))))
        (mapv :name))))

(defn observed-hosts
  "From a dash snapshot, build `cid -> #{node-name ...}` for hosted components."
  [snapshot]
  (reduce (fn [m n]
            (reduce (fn [m cid] (update m cid (fnil conj #{}) (:name n)))
                    m (:hosted n)))
          {} (:nodes snapshot)))

(defn- pick-targets
  "Choose `n` placement targets, preferring least-loaded nodes, then name.

   Host projection: variable-length candidates still sort here. Oracle exposes
   better-target?/pick-targets-2-record/pick-targets-3-first for fixed 2/3
   candidate tournaments (used by parity tests); product path keeps one sort."
  [candidates n load-by-node]
  (->> candidates
       (sort-by (juxt #(get load-by-node % 0) identity))
       (take (max 0 n))
       vec))

(defn- mirror-desired-n [replicas]
  (or replicas 1))

(defn- mirror-deficit-n [desired running-count]
  (max 0 (- desired running-count)))

(defn- mirror-action-kw [cid running-count desired free-candidates]
  (cond
    (nil? cid) :needs-build
    (< desired running-count) :over
    (zero? (max 0 (- desired running-count))) :satisfied
    (< free-candidates 1) :blocked
    :else :place))

(defn- desired-n
  "Replica desired count — kotoba `desired` with Product Value ABI optional i64
   when oracle ready."
  [replicas]
  (try-oracle
   #(oracle/i64->host (o 'desired [(oracle/option-i64 replicas)]))
   #(mirror-desired-n replicas)))

(defn- deficit-n
  [desired running-count]
  (try-oracle
   #(oracle/i64->host
     (o 'deficit [(oracle/as-i64 desired) (oracle/as-i64 running-count)]))
   #(mirror-deficit-n desired running-count)))

(defn- action-kw
  "Project reconcile-app inputs to kotoba `action-name` then keywordize.
   Optional cid string (no has-cid / has-misplaced sentinels) when oracle ready."
  [cid running-count desired free-candidates]
  (try-oracle
   #(keyword (o 'action-name
                [(oracle/option-string cid)
                 (oracle/as-i64 running-count)
                 (oracle/as-i64 desired)
                 (oracle/as-i64 free-candidates)]))
   #(mirror-action-kw cid running-count desired free-candidates)))
(defn reconcile-app
  "Pure per-app reconciliation.

   action in #{:needs-build :satisfied :place :over :blocked}
   :needs-build — app has no CID yet
   :satisfied   — desired replica count is met, with no misplacement
   :place       — under-replicated; `:targets` are proposed placement targets
   :over        — too many eligible hosts are running it; no auto-evict
   :blocked     — under-replicated and no eligible target is free"
  [fleet snapshot connect-spec {:keys [name cid replicas placement] :as app}]
  (let [desired   (desired-n replicas)
        eligible  (set (eligible-nodes fleet placement connect-spec))
        hosts     (get (observed-hosts snapshot) cid #{})
        running   (set/intersection hosts eligible)
        misplaced (set/difference hosts eligible)
        deficit   (deficit-n desired (count running))
        load      (into {} (map (fn [n] [(:name n) (count (:hosted n))]) (:nodes snapshot)))
        candidates (vec (sort (set/difference eligible running)))
        base      {:app name :cid cid :desired desired :manifest (:manifest app)
                   :reach (vec (:reach placement))
                   :eligible (vec (sort eligible))
                   :running (vec (sort running))
                   :misplaced (vec (sort misplaced))
                   :deficit deficit}
        action (action-kw cid
                          (count running)
                          desired
                          (count candidates))]
    (case action
      :needs-build
      (assoc base :action :needs-build
             :reason "no :cid — compile :manifest (clj→wasm) to resolve a CID first")

      :over
      (assoc base :action :over
             :reason (str (count running) " running > " desired
                          " desired (no auto-evict; lower :replicas or stop a node)"))

      :satisfied
      (assoc base :action :satisfied)

      :blocked
      (assoc base :action :blocked
             :reason (str "need " deficit " more but no eligible node free"
                          " (eligible=" (count eligible)
                          ", running=" (count running) ")"))

      :place
      (let [targets (pick-targets candidates deficit load)]
        (if (empty? targets)
          (assoc base :action :blocked
                 :reason (str "need " deficit " more but no eligible node free"
                              " (eligible=" (count eligible)
                              ", running=" (count running) ")"))
          (assoc base :action :place :targets targets)))

      ;; defensive: treat unknown as satisfied
      (assoc base :action :satisfied))))

(defn reconcile-plan
  "Pure whole-fleet plan. `ts` is caller-supplied so this namespace has no clock."
  [fleet snapshot connect-spec manifest ts]
  {:ts ts
   :fleet (:fleet/name fleet)
   :apps (mapv #(reconcile-app fleet snapshot connect-spec %) (:apps manifest))})

(defn- action-is-satisfied?
  "Kotoba `action-is-satisfied?` when ready."
  [action]
  (try-oracle
   #(= 1 (oracle/i64->host
          (o 'action-is-satisfied? [(name action)])))
   #(= (name action) action-satisfied)))

(defn- action-is-place?
  "Kotoba `action-is-place?` when ready."
  [action]
  (try-oracle
   #(= 1 (oracle/i64->host
          (o 'action-is-place? [(name action)])))
   #(= (name action) action-place)))

(defn plan-converged?
  "True when every app is satisfied.
   Per-app gate via kotoba; fold stays host."
  [plan]
  (every? #(action-is-satisfied? (:action %)) (:apps plan)))

(defn apply-apps
  "Apps that require an apply pass.
   Per-app gate via kotoba; fold stays host."
  [plan]
  (filterv #(action-is-place? (:action %)) (:apps plan)))

(defn apply-targets
  "Flatten :place apps into one (app, target) deploy pair PER target node.

   No cross-node lattice auction is wired (ADR-2606271600 known gap, confirmed
   converging kenchi-valuation 2026-07-07, ADR-2607071500 追記3): publishing a
   deploy message to one node and waiting for the gossipsub auction to also
   place it on the OTHER desired nodes never converges — the auction only
   places locally. So murakumo's own control plane (this planner already
   picked `:targets` via `pick-targets`, least-loaded first) must imperatively
   deploy to each target itself, same as the manual `bb deploy <app.edn>
   <node>` workflow that converged kenchi's 2nd replica."
  [plan]
  (vec (for [a (apply-apps plan) target (:targets a)]
         {:app a :target target})))

(defn watch-sleep-ms
  "Milliseconds to sleep between reconcile watch iterations.
   Kotoba `watch-sleep-ms` when oracle ready."
  [seconds]
  (try-oracle
   #(oracle/i64->host (o 'watch-sleep-ms [(oracle/as-i64 seconds)]))
   #(* 1000 seconds)))
(defn- parse-int [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn- mirror-watch-seconds [a]
  (let [a (str a)]
    (cond
      (= a flag-watch) 30
      (str/starts-with? a flag-watch-eq-prefix)
      (try (parse-int (subs a (count flag-watch-eq-prefix)))
           (catch #?(:clj Exception :cljs :default) _ 30))
      :else 30)))

(defn- watch-seconds
  "Seconds for --watch / --watch=N. Kotoba when ready."
  [a]
  (try-oracle
   #(oracle/i64->host (o 'watch-seconds [(str a)]))
   #(mirror-watch-seconds a)))

(defn- snapshot-value
  "Path after --snapshot=. Kotoba when ready."
  [a]
  (try-oracle
   #(o 'snapshot-value [(str a)])
   #(let [a (str a)]
      (if (str/starts-with? a flag-snapshot-prefix)
        (subs a (count flag-snapshot-prefix))
        ""))))

(defn- flag-is-dry-run? [a]
  (try-oracle
   #(= 1 (oracle/i64->host (o 'flag-is-dry-run? [(str a)])))
   #(= (str a) flag-dry-run)))

(defn- flag-is-apply? [a]
  (try-oracle
   #(= 1 (oracle/i64->host (o 'flag-is-apply? [(str a)])))
   #(= (str a) flag-apply)))

(defn- flag-is-watch? [a]
  (try-oracle
   #(= 1 (oracle/i64->host (o 'flag-is-watch? [(str a)])))
   #(str/starts-with? (str a) flag-watch)))

(defn- flag-is-snapshot? [a]
  (try-oracle
   #(= 1 (oracle/i64->host (o 'flag-is-snapshot? [(str a)])))
   #(str/starts-with? (str a) flag-snapshot-prefix)))

(defn- flag-is-dash? [a]
  (try-oracle
   #(= 1 (oracle/i64->host (o 'flag-is-dash? [(str a)])))
   #(str/starts-with? (str a) flag-dash-prefix)))

(defn parse-flags
  "Parse reconcile CLI flags into data.

   Pure flag classifiers + watch/snapshot value extract via kotoba when ready.
   Reduce fold stays host. Unknown --flags are ignored; first non-flag token
   is the manifest path."
  [args]
  (reduce (fn [m a]
            (cond
              (flag-is-dry-run? a) (assoc m :dry-run true)
              (flag-is-apply? a) (assoc m :apply true)
              (flag-is-watch? a) (assoc m :watch (watch-seconds a))
              (flag-is-snapshot? a) (assoc m :snapshot (snapshot-value a))
              (flag-is-dash? a) m
              :else (assoc m :manifest a)))
          {} args))

(defn reconcile-command-error
  "Validation error keyword for reconcile command flags, or nil.
   Kotoba `missing-manifest?` when ready."
  [{:keys [manifest]}]
  (when (try-oracle
         #(= 1 (oracle/i64->host
                (o 'missing-manifest? [(str (or manifest ""))])))
         #(str/blank? (str manifest)))
    :missing-manifest))

(defn reconcile-app-record
  "Compact per-app summary stored in reconcile history records."
  [app]
  {:app (:app app)
   :cid (:cid app)
   :desired (:desired app)
   :running (count (:running app))
   :action (name (:action app))
   :targets (vec (:targets app))})

(defn reconcile-record
  "Build the atproto record payload for a reconcile plan.

   `plan-json` is supplied by the host shell so this namespace stays free of any
   JSON dependency. $type dual-sourced via `reconcile-record-type`."
  [plan plan-json]
  {:$type reconcile-record-type
   :ts (:ts plan)
   :fleet (:fleet plan)
   :converged (plan-converged? plan)
   :apps (mapv reconcile-app-record (:apps plan))
   :plan plan-json})
