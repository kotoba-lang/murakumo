(ns murakumo.kotoba-native-execution-test
  "The full sweep: every native-crossable export of every core.

  Opt-in (`clojure -M:test:native`) because it needs a C toolchain and minutes.
  The machinery lives in `murakumo.native-exec`, which the default suite's
  canary uses too -- one implementation, two callers.

  The reasoning for what this measures, why it is differential rather than an
  oracle test, why generated inputs are the point, what \"agree\" means, and why
  refusals are counted rather than skipped, is in `murakumo.native-exec`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [murakumo.native-exec :as native]))

(deftest every-crossable-export-agrees-with-the-reference-on-the-native-isa
  (let [modules (native/core-sources)
        _ (is (seq modules) "there must be cores to run")
        host (native/native-host)
        reports (mapv #(native/run-core host %) modules)
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
