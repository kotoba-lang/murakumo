;; murakumo.reconcile.plan — portable desired/observed -> reconcile plan core.
;;
;; No filesystem, subprocess, wall-clock, Java-only APIs, or live fleet access.
;; This namespace is the .cljc source of truth for the wadm-style planning logic;
;; murakumo.reconcile wraps it with CLI, collection, apply, and persistence.

(ns murakumo.reconcile.plan
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [murakumo.connect :as connect]))

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
  "Choose `n` placement targets, preferring least-loaded nodes, then name."
  [candidates n load-by-node]
  (->> candidates
       (sort-by (juxt #(get load-by-node % 0) identity))
       (take (max 0 n))
       vec))

(defn reconcile-app
  "Pure per-app reconciliation.

   action in #{:needs-build :satisfied :place :over :blocked}
   :needs-build — app has no CID yet
   :satisfied   — desired replica count is met, with no misplacement
   :place       — under-replicated; `:targets` are proposed placement targets
   :over        — too many eligible hosts are running it; no auto-evict
   :blocked     — under-replicated and no eligible target is free"
  [fleet snapshot connect-spec {:keys [name cid replicas placement] :as app}]
  (let [desired   (or replicas 1)
        eligible  (set (eligible-nodes fleet placement connect-spec))
        hosts     (get (observed-hosts snapshot) cid #{})
        running   (set/intersection hosts eligible)
        misplaced (set/difference hosts eligible)
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
             :reason (str (count running) " running > " desired
                          " desired (no auto-evict; lower :replicas or stop a node)"))

      (and (zero? deficit) (empty? misplaced))
      (assoc base :action :satisfied)

      (pos? deficit)
      (let [targets (pick-targets candidates deficit load)]
        (if (empty? targets)
          (assoc base :action :blocked
                 :reason (str "need " deficit " more but no eligible node free"
                              " (eligible=" (count eligible)
                              ", running=" (count running) ")"))
          (assoc base :action :place :targets targets)))

      :else
      (assoc base :action :satisfied))))

(defn reconcile-plan
  "Pure whole-fleet plan. `ts` is caller-supplied so this namespace has no clock."
  [fleet snapshot connect-spec manifest ts]
  {:ts ts
   :fleet (:fleet/name fleet)
   :apps (mapv #(reconcile-app fleet snapshot connect-spec %) (:apps manifest))})

(defn plan-converged?
  "True when every app is satisfied."
  [plan]
  (every? #(= :satisfied (:action %)) (:apps plan)))

(defn apply-apps
  "Apps that require an apply pass."
  [plan]
  (filterv #(= :place (:action %)) (:apps plan)))

(defn watch-sleep-ms
  "Milliseconds to sleep between reconcile watch iterations."
  [seconds]
  (* 1000 seconds))

(defn- parse-int [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn parse-flags
  "Parse reconcile CLI flags into data.

   Pure helper used by the bb shell. Unknown --flags are ignored, matching the
   original command parser; the first non-flag token is the manifest path."
  [args]
  (reduce (fn [m a]
            (cond
              (= a "--dry-run") (assoc m :dry-run true)
              (= a "--apply") (assoc m :apply true)
              (str/starts-with? a "--watch")
              (assoc m :watch (let [[_ v] (str/split a #"=")]
                                (if v (parse-int v) 30)))
              (str/starts-with? a "--snapshot=") (assoc m :snapshot (subs a 11))
              (str/starts-with? a "--") m
              :else (assoc m :manifest a)))
          {} args))

(defn reconcile-command-error
  "Validation error keyword for reconcile command flags, or nil."
  [{:keys [manifest]}]
  (when (str/blank? (str manifest))
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
   JSON dependency."
  [plan plan-json]
  {:$type "com.murakumo.fleet.reconcile"
   :ts (:ts plan)
   :fleet (:fleet plan)
   :converged (plan-converged? plan)
   :apps (mapv reconcile-app-record (:apps plan))
   :plan plan-json})
