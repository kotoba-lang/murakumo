;; Pure-planner oracle: murakumo.infer.windows' rolling-window rate rule
;; vs kotoba/windows_core.kotoba.
;;
;; The cljc side owns the feed (folding usage events); the core owns the
;; arithmetic those folds feed into. Both are exercised here: the scalar rules
;; directly, and `admit-code` against the real `windows/admit` verdict built
;; from a real event feed, so the two halves are pinned at their seam and not
;; only in isolation.

(ns murakumo.windows-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.windows :as windows]))

(def port-source (slurp "kotoba/windows_core.kotoba"))

(def export-prefix
  (str "short-window-code week-window-code short-window-ms week-window-ms "
       "window-span-ms short-units-from-week window-cutoff in-window? "
       "recovers-at remaining indivisible? admit-code verdict-allow "
       "verdict-short verdict-week retry-after-ms retry-after-seconds "
       "retry-after-minutes"))

(def ^:private admit-ty
  (str "[:record :windows/admit [[:units :i64] [:short-limit :i64] "
       "[:short-remaining :i64] [:week-remaining :i64]]]"))

(defn- compile-cases [result-type cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] " result-type " " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest window-spans-match-the-specs
  (let [actual (compile-cases ":i64" {"s" "(window-span-ms 0)"
                                      "w" "(window-span-ms 1)"})]
    (is (= (get-in windows/window-specs [:short :ms]) (get actual "s")))
    (is (= (get-in windows/window-specs [:week :ms]) (get actual "w")))))

(def ^:private week-corpus
  ;; The shipped plan sizes plus the values where round-half-up can go wrong:
  ;; every residue mod 4, and the exact .5 case (w = 1140 -> 285.5).
  [0 1 2 3 4 5 6 7 1140 1142 2000 100000])

(deftest short-window-derivation-matches-the-float-rounding
  ;; cljc computes this in doubles: (long (+ 0.5 (* w 0.25))). The core does it
  ;; in i64. They must agree for every residue, not just on the shipped sizes --
  ;; a floor/round mix-up shows up in exactly one residue class and nowhere else.
  (let [cases (into {} (map-indexed
                        (fn [i w] [(str "u" i) (str "(short-units-from-week " w ")")])
                        week-corpus))
        actual (compile-cases ":i64" cases)]
    (doseq [[i w] (map-indexed vector week-corpus)]
      (testing (str w " units/week")
        (is (= (:short (windows/plan-limits {:plan/units-per-week w}))
               (get actual (str "u" i))))))))

(deftest remaining-never-goes-negative
  (let [corpus [[100 0] [100 40] [100 100] [100 101] [100 1000] [0 0] [0 5]]
        cases (into {} (map-indexed
                        (fn [i [lim used]] [(str "r" i) (str "(remaining " lim " " used ")")])
                        corpus))
        actual (compile-cases ":i64" cases)]
    (doseq [[i [lim used]] (map-indexed vector corpus)]
      (testing (str lim "-" used)
        (is (= (long (max 0.0 (- (double lim) (double used))))
               (get actual (str "r" i))))))))

(def ^:private now 1000000000000)

(deftest in-window-boundary-matches-the-cljc-cutoff
  ;; `consumed` counts events strictly after the cutoff. An event exactly one
  ;; span old has just fallen out; off by one here silently double-counts or
  ;; drops a consumption at every window edge.
  (doseq [[wkey wcode] [[:short 0] [:week 1]]]
    (let [span (get-in windows/window-specs [wkey :ms])
          ats [(- now span 1) (- now span) (- now span -1) (- now 1) now]
          cases (into {} (map-indexed
                          (fn [i at] [(str "i" i) (str "(in-window? " at " " now " " span ")")])
                          ats))
          actual (compile-cases ":bool" cases)]
      (doseq [[i at] (map-indexed vector ats)]
        (testing (str wkey " at=" at)
          ;; the cljc predicate, reached through `consumed` itself: a feed of
          ;; exactly one 1-unit event is counted iff the event is in-window
          (let [feed [(windows/usage-event :acct 1 at {})]]
            (is (= (pos? (windows/consumed feed :acct wkey now))
                   (get actual (str "i" i))))
            (is (= (get actual (str "i" i))
                   (> at (- now span))) "and the cutoff itself")))))))

(deftest recovers-at-matches-the-feed-fold
  (let [span (get-in windows/window-specs [:short :ms])
        oldest (- now 1000)
        feed [(windows/usage-event :acct 1 oldest {})
              (windows/usage-event :acct 1 (- now 500) {})]
        actual (compile-cases ":i64" {"r" (str "(recovers-at " oldest " " span ")")})]
    (is (= (windows/recovers-at feed :acct :short now) (get actual "r")))))

(def ^:private week-limit 1140)

(def ^:private admit-corpus
  ;; [label units short-limit short-used week-only-used]
  ;;
  ;; Stated as CONSUMPTION, not as remainings. A consumption inside the short
  ;; window is necessarily inside the week too, so the two remainings are not
  ;; independent -- `short-remaining 0` with `week-remaining 1140` is not a
  ;; state the system can be in, and a corpus written in remainings quietly
  ;; asks for states no feed produces. `week-only-used` is the consumption that
  ;; has already fallen out of the short window but not the week.
  [["plenty"                    10  285   0    0]
   ["exactly fills the short window" 285 285 0  0]
   ["indivisible by one unit"   286  285   0    0]
   ["Seedance 5s on Plus"       305  285   0    0]
   ["Seedance, week cannot take it" 305 285 0 840]
   ["indivisible, short exhausted"  305 285 285  0]
   ["short window blocks"        10  285 280    0]
   ["week window blocks"         10  285   0 1135]
   ["both block, short reported" 10  285 280  855]
   ["a zero-unit job"             0  285 285  855]
   ["short limit of zero"         1    0   0    0]])

(deftest admit-code-matches-the-cljc-verdict
  (let [span-short (get-in windows/window-specs [:short :ms])
        rows (for [[label units short-limit short-used week-only] admit-corpus]
               (let [feed (cond-> []
                            (pos? short-used)
                            (conj (windows/usage-event :acct short-used (- now 1000) {}))
                            (pos? week-only)
                            (conj (windows/usage-event :acct week-only
                                                       (- now span-short 1000) {})))
                     limits {:short short-limit :week week-limit}
                     verdict (windows/admit feed :acct units limits now)
                     st (:status verdict)]
                 {:label label :units units :short-limit short-limit
                  :short-rem (long (get-in st [:window :short :remaining]))
                  :week-rem (long (get-in st [:window :week :remaining]))
                  :expected (if (:allow? verdict)
                              0
                              (case (:window verdict) :short 1 :week 2))}))
        cases (into {} (map-indexed
                        (fn [i {:keys [units short-limit short-rem week-rem]}]
                          [(str "a" i)
                           (str "(admit-code (record-new " admit-ty " "
                                units " " short-limit " " short-rem " " week-rem "))")])
                        rows))
        actual (compile-cases ":i64" cases)]
    ;; Every verdict must be reachable, and the corpus must reach all three.
    (is (= #{0 1 2} (set (map :expected rows)))
        "the corpus exercises admit, short-blocked and week-blocked")
    (doseq [[i {:keys [label expected short-rem week-rem units]}] (map-indexed vector rows)]
      (testing (str label " — " units "u, short-rem=" short-rem " week-rem=" week-rem)
        (is (= expected (get actual (str "a" i))))))))

(deftest retry-after-rounding-matches
  (let [ms-corpus [0 1 999 1000 1001 59999 60000 60001 3600000]
        cases (merge
               (into {} (map-indexed
                         (fn [i ms] [(str "s" i) (str "(retry-after-seconds " ms ")")])
                         ms-corpus))
               (into {} (map-indexed
                         (fn [i ms] [(str "m" i)
                                     (str "(retry-after-minutes (retry-after-seconds " ms "))")])
                         ms-corpus)))
        actual (compile-cases ":i64" cases)]
    (doseq [[i ms] (map-indexed vector ms-corpus)]
      (testing (str ms "ms")
        (let [verdict {:retry-after-ms ms}
              secs (windows/retry-after-seconds verdict)]
          (is (= secs (get actual (str "s" i))))
          ;; the minutes figure `explain` prints
          (is (= (long (Math/ceil (/ (double secs) 60.0)))
                 (get actual (str "m" i)))))))))

(deftest retry-after-ms-is-clamped
  (let [corpus [[(+ now 5000) now] [now now] [(- now 5000) now]]
        cases (into {} (map-indexed
                        (fn [i [rec n]] [(str "d" i) (str "(retry-after-ms " rec " " n ")")])
                        corpus))
        actual (compile-cases ":i64" cases)]
    (doseq [[i [rec n]] (map-indexed vector corpus)]
      (testing (str rec "-" n)
        (is (= (long (max 0 (- rec n))) (get actual (str "d" i))))))))
