#!/usr/bin/env nbb
;; model-context-fit — what context does each model fit on a given budget?
;;
;; Answers the question infer.edn deliberately does NOT store: the fitting
;; context, which depends on the node and so cannot be a constant. Reads the
;; geometry recorded per model and computes against a budget you name.
;;
;;   nbb scripts/model-context-fit.cljs 13.8          # GB of budget
;;   nbb scripts/model-context-fit.cljs 13.8 --f16
;;
;; ⚠ A model with no :model/kv is reported as UNMEASURED, never as fitting.
;; Six of the twenty-six have measured geometry; the rest are unknown, and an
;; unknown that prints like a pass is the failure this whole exercise exists
;; to prevent.

(ns model-context-fit
  (:require ["fs" :as fs] [cljs.reader :as reader] [clojure.string :as str]))

(def ^:private steps [1024 2048 4096 8192 16384 32768 65536 131072 262144])

;; Compute buffers and the runtime, on top of weights and cache. Measured on
;; this fleet at roughly half a gigabyte for a 27B; explicit so it can be
;; argued with rather than buried in the arithmetic.
(def ^:private overhead-bytes 5.0E8)

(defn- fit [{:keys [elements-per-token encoder?]} bytes-per-el weight-bytes budget]
  (cond
    encoder? :no-kv-cache
    (nil? elements-per-token) :unmeasured
    :else
    (let [per-tok (* elements-per-token bytes-per-el)
          left (- budget (or weight-bytes 0) overhead-bytes)]
      (if-not (pos? left)
        :weights-alone-do-not-fit
        (or (last (filter #(<= (* per-tok %) left) steps)) :not-even-1024)))))

(defn- pad [w x]
  ;; cljs has no `format`; this is the whole of what was needed from it.
  (let [t (str x)] (str t (apply str (repeat (max 0 (- w (count t))) " ")))))

(defn- lpad [w x]
  (let [t (str x)] (str (apply str (repeat (max 0 (- w (count t))) " ")) t)))

(defn -main [& args]
  (let [f16? (some #{"--f16"} args)
        gb (js/parseFloat (or (first (remove #(str/starts-with? % "--") args)) "16"))
        budget (* gb 1e9)
        cfg (reader/read-string (fs/readFileSync "infer.edn" "utf8"))
        bpe ((if f16? :f16 :q8_0) (:kv-cache/bytes-per-element cfg))
        rows (for [[id m] (:models cfg)
                   :let [kv (:model/kv m)
                         w (or (:model/weight-bytes m) (:model/snapshot-bytes m))
                         r (if kv (fit kv bpe w budget) :unmeasured)]]
               [id m kv w r])]
    (println (str "budget " gb " GB, cache " (if f16? "f16" "q8_0")
                  " (" bpe " bytes/element), overhead " (/ overhead-bytes 1e9) " GB\n"))
    (println (str (pad 30 "model") (lpad 9 "weights") (lpad 10 "KiB/tok")
                  (lpad 10 "declared") "  fits"))
    (doseq [[id m kv w r] (sort-by (fn [[_ _ kv]] (- (or (:elements-per-token kv) 0))) rows)
            :when kv]
      (println (str (pad 30 id)
                    (lpad 9 (if w (str (.toFixed (/ w 1e9) 1) "G") "—"))
                    (lpad 10 (if (:elements-per-token kv)
                               (.toFixed (/ (* (:elements-per-token kv) bpe) 1024) 1) "—"))
                    (lpad 10 (str (or (:model/context m) "—")))
                    "  "
                    (if (number? r)
                      (str r (when (and (:model/context m) (< r (:model/context m)))
                               "  ⚠ BELOW the declared context"))
                      (name r)))))
    (let [un (remove (fn [[_ _ kv]] kv) rows)]
      (println (str "\nUNMEASURED (" (count un) " of " (count rows)
                    ") — no :model/kv, so no answer is available:"))
      (println (str "  " (str/join ", " (sort (map first un))))))))

(apply -main *command-line-args*)
