#!/usr/bin/env nbb
;; verify-one-model-per-node — a node serves at most one model.
;;
;; Owner instruction 2026-09-10: 「基本的に1台当たり1つの model しか host しない
;; ように設計して」.
;;
;; ⚠ THIS CHECKS THE DECLARATION, NOT THE MACHINE. `murakumo.fleet.one-model`
;; answers what a node IS hosting, by classifying its process list. This
;; answers what a node is DECLARED to host, from `fleet.edn`. Both are needed
;; and neither substitutes for the other: a declaration nothing enforces is a
;; wish, and a measurement nothing declares has nothing to be measured against.
;; Reconciling the two is a third thing and is not done here.
;;
;; ⚠ SERVING IS NOT CACHING. ADR-260712 already decided that "model cache
;; placement does not imply runtime compatibility", and a node may hold any
;; number of model files on disk. This binds the ENGINE, not the filesystem.
;;
;; ⚠ THE LIMIT IS ON THE NODE SIDE ONLY. A node serves at most one model; a
;; MODEL may be served by many nodes, and `murakumo.infer.plan` cutting one
;; model across ranks stays legal — that is the dual of this rule, not a
;; violation of it.
;;
;; `:node/serves` is one of:
;;   "model-id"   exactly one model, which must exist in infer.edn's :models
;;   :none        deliberately serves nothing (head/relay, cache-only)
;;   :unstated    not yet described — NOT conformant, and counted separately
;;   ["a" "b"]    an exception, valid only with :node/serves-exception
;;
;; Exit codes are four different answers and must not be collapsed:
;;   0  every node stated, none violating
;;   1  a violation: two or more models without a live exception, an expired
;;      exception, or a model id nothing defines
;;   2  could not answer: a file would not read, or zero nodes were scanned
;;   3  no violations, but N nodes are :unstated

(ns verify-one-model-per-node
  (:require ["fs" :as fs] [cljs.reader :as reader] [clojure.string :as str]))

(defn- read-edn [path]
  (try (reader/read-string (fs/readFileSync path "utf8"))
       (catch :default e
         (println (str "REFUSED  cannot read " path ": " (.-message e)))
         (js/process.exit 2))))

(defn- today [] (.toISOString (js/Date.)))

(defn- classify
  "One node's standing. `models` is the set of defined model ids."
  [node models]
  (let [serves (:node/serves node)
        exc (:node/serves-exception node)]
    (cond
      (nil? serves)      {:kind :unstated :why "no :node/serves key at all"}
      (= :unstated serves) {:kind :unstated :why "declared :unstated"}
      (= :none serves)   {:kind :ok :why "declared to serve nothing"}
      (string? serves)   (if (contains? models serves)
                           {:kind :ok :why serves}
                           {:kind :violation
                            :why (str "serves \"" serves "\" which no :models entry defines")})
      (vector? serves)
      (cond
        (< (count serves) 2)
        {:kind :violation :why "a vector must name two or more models; use the bare id for one"}
        (nil? exc)
        {:kind :violation
         :why (str "serves " (count serves) " models with no :node/serves-exception")}
        (not (and (:reason exc) (:adr exc) (:until exc)))
        {:kind :violation
         :why "an exception needs :reason, :adr and :until — all three"}
        (neg? (compare (str (:until exc)) (subs (today) 0 10)))
        {:kind :violation
         :why (str "exception expired on " (:until exc))}
        (some #(not (contains? models %)) serves)
        {:kind :violation :why "an exception may not name an undefined model"}
        :else {:kind :ok :why (str "exception until " (:until exc) ": " (:reason exc))})
      :else {:kind :violation :why (str "unrecognised :node/serves " (pr-str serves))})))

(defn -main [& args]
  (let [fleet (read-edn "fleet.edn")
        infer (read-edn "infer.edn")
        nodes (vec (:nodes fleet))
        models (set (keys (:models infer)))
        _ (when (zero? (count nodes))
            (println "REFUSED  fleet.edn named no nodes; refusing to report a pass")
            (js/process.exit 2))
        _ (when (zero? (count models))
            (println "REFUSED  infer.edn named no models; every id would look undefined")
            (js/process.exit 2))
        ;; ⚠ `:model/replicas` IS THE OTHER DIRECTION AND CONTRADICTS THIS ONE.
        ;; It survives in infer.edn on two models, has ZERO readers anywhere in
        ;; the tree, and one of the two says `:all-edge-nodes` -- i.e. the only
        ;; machine-readable placement statement in the system asserts that every
        ;; edge node hosts that model, which is exactly what this rule forbids.
        ;; It is reported rather than deleted: a field nothing reads is not
        ;; load-bearing, but silently removing a declaration of intent loses the
        ;; intent. The node side is authoritative; this must go, deliberately.
        replicas (into {} (keep (fn [[id m]]
                                  (when (contains? m :model/replicas)
                                    [id (:model/replicas m)]))
                                (:models infer)))
        rows (mapv (fn [n] [(:name n) (classify n models)]) nodes)
        by (fn [k] (filterv #(= k (:kind (second %))) rows))
        violations (by :violation)
        unstated (by :unstated)]
    (println (str "SCANNED\t" (count nodes) " nodes, " (count models) " models"))
    (doseq [[name {:keys [kind why]}] rows]
      (println (str "  " (case kind :ok "ok      " :unstated "UNSTATED" :violation "VIOLATION")
                    "  " name "  " why)))
    (when (seq replicas)
      (println (str "CONTRADICTION  " (count replicas)
                    " model(s) still carry :model/replicas, which places from the"
                    " MODEL side and has no readers:"))
      (doseq [[id v] replicas] (println (str "    " id " -> " (pr-str v)))))
    (println (str "ok=" (count (by :ok))
                  " unstated=" (count unstated)
                  " violations=" (count violations)))
    (cond
      (seq violations)
      (do (println "FAIL  a node may serve at most one model") (js/process.exit 1))
      (seq unstated)
      (do (println (str "INCOMPLETE  " (count unstated)
                        " node(s) undeclared. No violation, but this is not a pass —"
                        " an undeclared node is unmeasured, not conformant."))
          (js/process.exit 3))
      :else (println "OK  every node declares at most one model"))))

(apply -main *command-line-args*)
