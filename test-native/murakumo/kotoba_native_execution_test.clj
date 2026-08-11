;; Every shipped decision, RUN on the native ISA and compared to the reference.
;;
;; ── what this adds to the qualification sweep ──
;;
;; `kotoba_native_qualification_test` asks both backends to ACCEPT all 35
;; cores, and its own docstring says that is what it asks. Acceptance is not
;; execution: a module can compile and still compute the wrong thing, and until
;; now exactly one function in this repository had ever run as machine code --
;; `prices_core/civil-days`, by hand, from a scratch directory, recorded in
;; ADR-260811 as "a demonstration, not a capability: nothing re-runs it".
;;
;; This re-runs it, and 700-odd others.
;;
;; ── it is a differential test, not an oracle test ──
;;
;; Both sides come from ONE compile: the native code and the KIR handed to the
;; reference interpreter are the same `compile-source` result. So a
;; disagreement is attributable to the native backend and to nothing else --
;; not to a compiler pin, not to drift between the shipped artifact and the
;; source. That also means this says nothing about whether
;; `resources/murakumo/oracle/*.kir.edn` is current; `kotoba_oracle_authority`
;; owns that question and still does.
;;
;; ── the inputs are generated, and that is the point ──
;;
;; Parity does not need meaningful arguments. It needs the SAME arguments on
;; both sides and the same answer out, so a fixed pool per type covering
;; boundaries (empty string, Long/MIN_VALUE, zero, non-ASCII) exercises the
;; sign, arena and comparison paths without anyone inventing a plausible fleet
;; scenario for 735 exports. What it cannot do is reach a branch that needs a
;; structured input, so coverage is by export, not by path.
;;
;; ── what "agree" means here ──
;;
;; Both engines answering the same value is agreement. Both engines REFUSING is
;; also agreement, and it has to be, because they do not share a failure
;; vocabulary: a guest fault is a JVM exception on one side and a supervisor
;; `:trap` status on the other. Requiring the labels to match would fail 31
;; times on `digit-char(-1)` and `nat-str(-1)` -- cases where both engines
;; correctly refuse -- and say nothing about semantics.
;;
;; What is NOT agreement, and is what this test exists to catch: one engine
;; producing a value where the other refuses, or the two producing different
;; values. Both are reported with the arguments that produced them.
;;
;; ── there is deliberately no production native path here ──
;;
;; `murakumo.kotoba.oracle/call` is a single seam, so pointing it at native
;; artifacts would be a ten-line change -- and it is not made, on purpose.
;; Native costs a process spawn per call (tens of ms) against an interpreter
;; that answers a word-typed planner in about 2 ms, so it only pays when one
;; call does real work. The calls here that DO real work -- `task_plan`'s
;; placement entry points, `infer_plan`, `infer_schedule` -- are exactly the
;; ones taking `[:ref …]`, so they are the 321 that cannot cross. Adding the
;; switch today would add a switch nobody should flip.
;;
;; What would change that: an export boundary for caller-constructed
;; aggregates (ADR-2608110200 names what that costs, and it is not a gate
;; edit), or a loader that keeps one process across calls.
;;
;; ── what is refused is counted, not skipped ──
;;
;; The native host boundary takes `:i64`, `:bool` and `:string`; a parameter or
;; result that is a `[:ref …]` or a `[:record …]` cannot cross a kexe export
;; (ADR-2608110200 -- refused, deliberately, not missing). Those exports are
;; reported as a number, per core, so "735 of 1083 ran" is visible rather than
;; a suite that quietly measures two thirds of the surface and calls it all.

(ns murakumo.kotoba-native-execution-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [kotoba.artifact.runtime-identity :as runtime-identity]
            [kotoba.verifier.signing :as signing]))

(def ^:private host-target
  (if (contains? #{"aarch64" "arm64"} (str/lower-case (System/getProperty "os.arch")))
    :aarch64-kotoba-v1
    :x86_64-kotoba-v1))

(def ^:private crossable #{:i64 :bool :string})

(defn- crossable? [t] (contains? crossable t))

(defn- pool
  "Fixed, boundary-covering values per native-crossable type."
  [t]
  (case t
    :i64 [0 1 -1 7919 Long/MAX_VALUE Long/MIN_VALUE]
    :bool [true false]
    :string ["" "a" "murakumo" "0" "ノード" "tag:v/1"]))

(def ^:private tuples-per-export 3)

(defn- arg-tuples [param-types]
  (if (empty? param-types)
    [[]]
    (for [round (range tuples-per-export)]
      (vec (map-indexed (fn [index t]
                          (let [values (pool t)]
                            (nth values (mod (+ index round) (count values)))))
                        param-types)))))

;; ── the two engines ────────────────────────────────────────────────────

(defn- interpret [kir export args]
  (try {:ok (ir/execute kir export (vec args))}
       (catch Throwable failure {:refused (or (:problem (ex-data failure)) :threw)})))

(defn- run-native [invoke session export args]
  (try
    (let [evidence (:evidence (invoke session {} {:args (vec args)}
                                      {:now (quot (System/currentTimeMillis) 1000)
                                       :entry export}))]
      (if (= :ok (:status evidence))
        {:ok (:result evidence)}
        {:refused (:status evidence)}))
    (catch Throwable failure {:refused (or (:problem (ex-data failure)) :threw)})))

;; ── one measured loader for the whole suite ────────────────────────────

(defn- loader-source-dir
  "amu owns `tools/kexe_loader.c` and does not put it on a classpath -- it is C.
  Find it the only way a git dependency's non-classpath files can be found:
  from a file amu DOES put on the classpath, walk up to the checkout root."
  []
  (let [anchor (or (io/resource "kotoba/compiler/core.clj")
                   (throw (ex-info "amu is not on this classpath" {})))]
    (when-not (= "file" (.getProtocol anchor))
      (throw (ex-info "amu must be a source checkout, not a jar" {:anchor (str anchor)})))
    (->> (iterate #(.getParentFile ^java.io.File %) (io/file (.toURI anchor)))
         (take-while some?)
         (take 8)
         (map #(io/file % "tools"))
         (filter #(.isFile (io/file % "kexe_loader.c")))
         first
         (#(some-> ^java.io.File % .getPath)))))

(defn- native-host []
  (let [measure (requiring-resolve 'kototama.native.executor/measure-runtime)
        {:keys [runtime loader-bytes]} (measure {:loader-source-dir (loader-source-dir)})
        loader (doto (java.io.File/createTempFile "murakumo-kexe-loader-" "")
                 (.deleteOnExit))
        signing-key (signing/generate-keypair)]
    (with-open [out (io/output-stream loader)] (.write out ^bytes loader-bytes))
    (when-not (.setExecutable loader true true)
      (throw (ex-info "cannot make the measured loader executable" {})))
    {:runtime runtime
     :loader-path (.getPath loader)
     :signing-key signing-key
     :trust {:format :kotoba.trust/v1
             :trusted-signers #{(:signer signing-key)}
             :revoked-signers #{}
             :revoked-artifacts #{}
             :trusted-runtime-sha256 #{(runtime-identity/identity-sha256 runtime)}}}))

(defn- core-sources []
  (->> (.listFiles (io/file "kotoba"))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".kotoba"))
       sort
       vec))

;; ── the sweep ──────────────────────────────────────────────────────────

(defn- run-core
  "Execute every native-crossable export of one core on both engines."
  [host module]
  (let [{:keys [runtime loader-path signing-key trust]} host
        prepare (requiring-resolve 'kototama.native.executor/prepare)
        invoke (requiring-resolve 'kototama.native.executor/invoke)
        close! (requiring-resolve 'kototama.native.executor/close!)
        now (quot (System/currentTimeMillis) 1000)
        result (compiler/compile-source (slurp (io/file "kotoba" module)) host-target {})
        artifact (:artifact result)
        kir (:kir result)
        functions (:functions kir)
        exported (filter #(contains? (:exports artifact) (:name %)) functions)
        {crossing true refused false}
        (group-by #(boolean (and (every? crossable? (:param-types %))
                                 (crossable? (:result %))))
                  exported)
        envelope (signing/sign artifact signing-key {:not-before (- now 60)
                                                     :expires (+ now 86400)})
        session (prepare envelope trust {:now now :runtime runtime
                                         :loader-path loader-path})]
    (try
      (let [outcomes
            (doall
             (for [function crossing
                   args (arg-tuples (:param-types function))
                   :let [export (:name function)
                         reference (interpret kir export args)
                         native (run-native invoke session export args)]]
               (cond
                 ;; Both refused. They cannot agree on a label -- see the
                 ;; header -- so agreement is that neither produced a value.
                 (and (contains? reference :refused) (contains? native :refused))
                 :both-refused

                 (= reference native) :agreed

                 :else {:module module :export export :args args
                        :reference reference :native native})))]
        {:module module
         :calls (count outcomes)
         :agreed (count (filter #{:agreed} outcomes))
         :both-refused (count (filter #{:both-refused} outcomes))
         :crossing (count crossing)
         :refused (count refused)
         :exported (count exported)
         :disagreements (vec (remove keyword? outcomes))})
      (finally (close! session)))))

(deftest every-crossable-export-agrees-with-the-reference-on-the-native-isa
  (let [modules (core-sources)
        _ (is (seq modules) "there must be cores to run")
        host (native-host)
        reports (mapv #(run-core host %) modules)
        crossing (reduce + (map :crossing reports))
        refused (reduce + (map :refused reports))
        calls (reduce + (map :calls reports))
        agreed (reduce + (map :agreed reports))
        both-refused (reduce + (map :both-refused reports))
        disagreements (mapcat :disagreements reports)]
    (println (format (str "native execution: %d cores, %d of %d exports crossed the host boundary, "
                          "%d calls -- %d same value, %d both refused, %d disagreed")
                     (count reports) crossing (+ crossing refused) calls
                     agreed both-refused (count disagreements)))
    (doseq [{:keys [module crossing refused exported]} reports]
      (println (format "  %-34s %3d/%-3d ran  (%d cannot cross)" module crossing exported refused)))
    (is (pos? crossing) "at least one export must reach the native ISA")
    (is (pos? agreed)
        "some call must produce a VALUE on both engines -- a run where every
         call merely faulted on both sides would satisfy the disagreement
         assertion while executing nothing")
    (is (empty? disagreements)
        (str "native disagrees with the reference interpreter:\n"
             (str/join "\n" (map pr-str (take 20 disagreements)))))
    ;; A boundary that swallowed everything would leave `crossing` at zero and
    ;; the assertion above would catch it. A boundary that let everything
    ;; through would be a different bug, so the split is asserted too.
    (is (= (+ crossing refused) (reduce + (map :exported reports))))))
