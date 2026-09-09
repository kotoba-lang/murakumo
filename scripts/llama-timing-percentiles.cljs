#!/usr/bin/env nbb
;; Distribution of llama.cpp server decode/prefill rates from a journal dump.
;;
;; llama-server prints one pair of lines per completed task:
;;
;;   slot print_timing: ... | prompt eval time = 599.23 ms / 361 tokens ( 1.66 ms per token, 602.44 tokens per second)
;;   slot print_timing: ... |        eval time = 3924.20 ms / 193 tokens ( 20.44 ms per token,  48.93 tokens per second)
;;
;; Report the DISTRIBUTION, never a single sample: a head that is fast when
;; idle and slow under load has one number for each and neither is the truth.
;;
;; A task that produced 1 token in 0.00 ms is below the clock's resolution,
;; not a measurement. Both ends of that divide-by-zero were observed in this
;; fleet on the same day, from the same condition:
;;
;;   gad  eval time = 0.00 ms / 1 tokens ( 0.00 ms per token, 1000000.00 tokens per second)
;;   b70  eval time = 0.00 ms / 1 tokens ( 0.00 ms per token,       0.00 tokens per second)
;;
;; The unmeasurable sample looks like the fastest one on one build and the
;; slowest on the other. Filtering only zero TOKENS leaves this in; the filter
;; is on zero DURATION as well, and the count of what was dropped is printed
;; beside every distribution so that "excluded" can never read as "clean".
;;
;; Usage (the head does the grepping; this does the arithmetic):
;;
;;   ssh <head> 'journalctl -u <unit> --since "24 hours ago" --no-pager' \
;;     | nbb scripts/llama-timing-percentiles.cljs --label <head>
;;
;;   nbb scripts/llama-timing-percentiles.cljs --label b70 < saved-journal.txt

(ns llama-timing-percentiles
  (:require [kotoba.lang.text :as str]))

(def argv (vec (drop 2 (js->clj (aget js/process "argv")))))

(defn flag [name default]
  (let [i (.indexOf argv name)]
    (if (neg? i) default (get argv (inc i) default))))

(def label (flag "--label" "head"))

;; "prompt eval time = 599.23 ms / 361 tokens ( 1.66 ms per token, 602.44 tokens per second)"
(def line-re
  #"(prompt eval time|eval time)\s*=\s*([0-9.]+)\s*ms\s*/\s*(\d+)\s*(?:tokens|runs)\s*\(\s*([0-9.]+)\s*ms per token,\s*([0-9.]+)\s*tokens per second")

(defn parse-line [line]
  (when-let [[_ kind ms n _per-tok tps] (re-find line-re line)]
    {:kind (if (str/starts-with? kind "prompt") :prefill :decode)
     :ms (js/parseFloat ms)
     :tokens (js/parseInt n 10)
     :tps (js/parseFloat tps)}))

(defn pct [sorted p]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted))
                     (int (js/Math.floor (* p (count sorted))))))))

(defn fmt [x] (when x (.toFixed x 2)))

(defn report! [kind samples degenerate]
  (let [tps (sort (map :tps samples))
        mspt (sort (map #(/ (:ms %) (:tokens %)) samples))]
    (println (str "  " (name kind)))
    (if (empty? samples)
      (println "    n=0  -- NO SAMPLES (not 'no traffic': check the unit name and window)")
      (do
        (println (str "    n=" (count samples)
                      "  excluded-degenerate=" degenerate))
        (println (str "    tok/s      min " (fmt (first tps))
                      "  p25 " (fmt (pct tps 0.25))
                      "  median " (fmt (pct tps 0.50))
                      "  p75 " (fmt (pct tps 0.75))
                      "  max " (fmt (last tps))))
        (println (str "    ms/token   min " (fmt (first mspt))
                      "  median " (fmt (pct mspt 0.50))
                      "  max " (fmt (last mspt))))
        (println (str "    tokens     total " (reduce + (map :tokens samples))))))))

(defn main [text]
  (let [parsed (keep parse-line (str/split-lines text))
        ;; zero tokens OR zero elapsed ms: both are "the clock could not
        ;; resolve this", and both must be excluded rather than ranked.
        degenerate? #(or (zero? (:tokens %)) (<= (:ms %) 0.0))
        good (remove degenerate? parsed)
        by-kind (group-by :kind good)
        dropped (group-by :kind (filter degenerate? parsed))]
    (println (str "=== " label " === parsed-timing-lines=" (count parsed)))
    (when (zero? (count parsed))
      (println "  REFUSING to report a distribution: zero timing lines matched.")
      (println "  That is 'could not measure', not 'idle'. Check unit name/window.")
      (.exit js/process 2))
    (doseq [k [:prefill :decode]]
      (report! k (get by-kind k []) (count (get dropped k []))))))

(let [chunks (atom [])
      stdin (aget js/process "stdin")]
  (.setEncoding stdin "utf8")
  (.on stdin "data" #(swap! chunks conj %))
  (.on stdin "end" #(main (str/join @chunks))))
