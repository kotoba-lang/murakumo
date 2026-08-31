;; murakumo.fleet.accelerator — accelerator memory admission (host face).
;;
;; The pure truth is kotoba/accelerator_budget_core.kotoba, shipped as
;; resources/murakumo/oracle/accelerator_budget_core.kir.edn. This namespace
;; only measures, folds and names; it decides nothing.
;;
;; ## Why measuring is a separate namespace from deciding
;;
;; ADR-2608318500 measured gad: an aperture of 71.3 GB with a Hunyuan3D
;; instance holding 81.77 GB of it, and 262 core dumps since 2026-08-14
;; because the order of operations was start -> allocate -> the driver dies.
;; The missing step was a test, and a test that reads its own inputs from a
;; config file is not a test -- CLAUDE.md's first question is what a check
;; returns when it could not measure, and `pass` is the defect.
;;
;; So `probe-command` reads sysfs and `parse-probe` folds it, and BOTH of them
;; are allowed to answer `unmeasured`. That answer propagates into the core as
;; a -1 aperture and comes back out as a REFUSAL, never as an empty device.
;;
;; Host keeps: ssh/exec, sysfs paths, /proc walking, string parsing.
;; Pure: every comparison and every byte of arithmetic.
(ns murakumo.fleet.accelerator
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :accelerator-budget)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(def unmeasured
  "What a probe returns when it could not read the device.

  Deliberately NOT 0: zero is a real answer meaning nothing is allocated, and
  collapsing the two is how a failed probe becomes an idle accelerator that
  admits everything."
  -1)

(def classes
  "The only two. A third would put `who wins` back into runtime accident."
  {:resident 0 :opportunistic 1})

;; ── measurement (host) ─────────────────────────────────────────────────

(def probe-command
  "One shell command answering an accelerator's aperture and its claims.

  Emits three kinds of line and nothing else, so the parser can tell a value
  it read from a value it did not:

    vram-total<TAB><bytes>
    gtt-total<TAB><bytes>
    claim<TAB><pid><TAB><bytes><TAB><comm>

  A field the kernel does not expose is simply absent, and `parse-probe` then
  reports the aperture as unmeasured rather than inventing a zero."
  (str "for f in mem_info_vram_total mem_info_gtt_total; do "
       "  v=$(cat /sys/class/drm/card*/device/$f 2>/dev/null | head -1); "
       "  [ -n \"$v\" ] && printf '%s\\t%s\\n' \"${f#mem_info_}\" \"$v\"; "
       "done | sed 's/^vram_total/vram-total/; s/^gtt_total/gtt-total/'; "
       "for d in /proc/[0-9]*; do "
       "  p=${d#/proc/}; "
       "  b=$(grep -h drm-total-vram $d/fdinfo/* 2>/dev/null "
       "      | awk '{s+=$2} END{if (s>0) print s*1024}'); "
       "  [ -n \"$b\" ] && printf 'claim\\t%s\\t%s\\t%s\\n' \"$p\" \"$b\" "
       "      \"$(tr -d '\\000' < $d/cmdline 2>/dev/null | cut -c1-60)\"; "
       "done"))

(defn- parse-long* [s]
  (try #?(:clj (Long/parseLong (str/trim s)) :cljs (js/parseInt (str/trim s) 10))
       (catch #?(:clj Exception :cljs :default) _ nil)))

(defn parse-probe
  "Probe stdout → {:vram-total :gtt-total :claims [{:pid :bytes :comm}]}.

  Absent totals stay `unmeasured`. A claim line that does not parse is DROPPED
  and counted in `:unreadable-claims`, because a claim we could not read is not
  a claim of zero bytes -- under-counting commitments is what lets an admission
  test admit something that does not fit."
  [out]
  (reduce
   (fn [acc line]
     (let [cells (str/split line #"\t")]
       (case (first cells)
         "vram-total" (assoc acc :vram-total (or (parse-long* (second cells)) unmeasured))
         "gtt-total"  (assoc acc :gtt-total (or (parse-long* (second cells)) unmeasured))
         "claim" (if-let [b (parse-long* (nth cells 2 ""))]
                   (update acc :claims conj {:pid (nth cells 1 "?")
                                             :bytes b
                                             :comm (nth cells 3 "")})
                   (update acc :unreadable-claims inc))
         acc)))
   {:vram-total unmeasured :gtt-total unmeasured :claims [] :unreadable-claims 0}
   (remove str/blank? (str/split-lines (or out "")))))

;; ── budget (pure, via the oracle) ──────────────────────────────────────

(defn aperture-bytes [{:keys [vram-total gtt-total]}]
  (o "aperture-bytes" [(or vram-total unmeasured) (or gtt-total unmeasured)]))

(defn headroom-default-milli [] (o "headroom-default-milli" []))

(defn usable-bytes
  ([probe] (usable-bytes probe (headroom-default-milli)))
  ([probe headroom-milli] (o "usable-bytes" [(aperture-bytes probe) headroom-milli])))

(defn committed
  "Claims folded into the two classes by a caller-supplied registry.

  `registry` is pid or comm-substring → :resident | :opportunistic. A claim
  that matches nothing is counted as OPPORTUNISTIC, which is the conservative
  direction: an unknown holder is assumed evictable-but-present, so it reduces
  what opportunistic work may take and does not silently free up room."
  [{:keys [claims]} registry]
  (reduce (fn [acc {:keys [pid comm bytes]}]
            (let [k (or (get registry pid)
                        (some (fn [[pat klass]]
                                (when (and (string? pat) (str/includes? comm pat)) klass))
                              registry)
                        :opportunistic)]
              (update acc k + bytes)))
          {:resident 0 :opportunistic 0}
          claims))

(defn admit
  "Answer whether `request-bytes` may be allocated, and why not when it may not.

  Returns {:admitted? :code :reason :granted :usable :resident :opportunistic}.
  `:reason` is always present -- a refusal that does not say why is the thing
  that made 262 core dumps unreadable."
  [probe registry klass request-bytes & [headroom-milli]]
  (let [usable (usable-bytes probe (or headroom-milli (headroom-default-milli)))
        {res :resident opp :opportunistic} (committed probe registry)
        k (get classes klass -1)
        code (o "admit-code" [k request-bytes usable res opp])]
    {:admitted? (zero? code)
     :code code
     :reason (keyword (o "code-name" [code]))
     :granted (o "granted-bytes" [k usable res opp])
     :usable usable
     :resident res
     :opportunistic opp}))
