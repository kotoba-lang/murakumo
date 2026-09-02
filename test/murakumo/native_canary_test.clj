;; One core, run on the native ISA, in the DEFAULT suite.
;;
;; ── why this exists next to the full sweep ──
;;
;; `murakumo.kotoba-native-execution-test` runs every native-crossable export
;; of every core -- 735 of them -- and is the real check. It is opt-in
;; (`clojure -M:test:native`) for good reasons: a C toolchain, and minutes.
;;
;; Measured 2026-08-12: **nothing invokes that alias.** This repository has no
;; entry in `scripts/fleet-ci/gates.edn`. Default CI is job kotoba-operator
;; (kotoba compile wasm+web, file-on-disk, guest-run), not clojure -M:test.
;; Leftover JVM is leftover-jvm.yml, workflow_dispatch only. So native
;; execution was checked
;; only when a person remembered to type it -- the shape this repository's own
;; runner docstring warns about: 走らないテストは緑と区別が付かない.
;;
;; This is the canary: ONE small core, so that "the native backend produces
;; wrong answers" cannot reach `main` unnoticed, while the sweep stays opt-in
;; for the cases a canary cannot cover. It shares `murakumo.native-exec` with
;; the sweep, so there is one implementation, not two.
;;
;; It does NOT make the sweep unnecessary. A canary over one core says nothing
;; about the other thirty-four, and this file should not grow into a second
;; sweep -- if more coverage is wanted by default, invoke the alias from CI.
;;
;; ── a missing C toolchain fails rather than skips ──
;;
;; Same reason as everywhere else here: a native execution test that does not
;; execute has verified nothing, and a quiet skip is indistinguishable from a
;; passing one. Every fleet node is a Mac with the Xcode command line tools.

(ns murakumo.native-canary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [murakumo.native-exec :as native]))

(def ^:private canary-core
  "The smallest shipped core, chosen for speed rather than importance. The
  point is that the native path is exercised at all on every run, not that
  this particular decision matters more than the others."
  "overlay_stream_core.kotoba")

(deftest the-native-backend-still-agrees-on-one-core-every-run
  (is (some #{canary-core} (native/core-sources))
      (str canary-core " must still be a shipped core; if it was renamed or "
           "removed, point this canary at another small one"))
  (let [{:keys [module calls agreed both-refused crossing exported disagreements]}
        (native/run-core (native/native-host) canary-core)]
    (println (format "native canary: %s -- %d of %d exports crossed, %d calls, %d agreed"
                     module crossing exported calls agreed))
    (is (pos? crossing)
        "at least one export must reach the native ISA, or this measures nothing")
    (is (pos? agreed)
        "some call must produce a VALUE on both engines -- a run where every
         call merely faulted on both sides would satisfy the disagreement
         assertion while executing nothing")
    (is (empty? disagreements)
        (str "native disagrees with the reference interpreter:\n"
             (str/join "\n" (map pr-str disagreements))))
    (is (= calls (+ agreed both-refused))
        "every call is either an agreement or a mutual refusal")))
