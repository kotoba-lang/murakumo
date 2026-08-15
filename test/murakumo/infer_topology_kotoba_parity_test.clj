;; Pure-planner oracle: murakumo.infer.topology vs kotoba/infer_topology_core.kotoba.
;;
;; What this pins is drift between the SOURCE in kotoba/ and the ARTIFACT in
;; resources/murakumo/oracle/. The cljc side has no host mirror to compare
;; against (T6.4 deleted them all); it executes the shipped KIR. So the test
;; compiles the source afresh, runs both, and requires them to agree — which
;; goes red the moment someone edits the .kotoba and forgets
;; `clojure -M:test:gen`, and would otherwise be invisible until a plan came
;; out wrong on the fleet.

(ns murakumo.infer-topology-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.topology :as topo]))

(def port-source (slurp "kotoba/infer_topology_core.kotoba"))

(def ^:private export-prefix
  (str "expected-links evidence-measured evidence-partial evidence-none "
       "evidence-unverified evidence-code evidence-name coverage-complete? "
       "usable-link? class-unknown class-wan class-gbe class-fast "
       "link-class-code link-class-name wan-ceiling-mbps gbe-ceiling-mbps "
       "min-mbps-of strategy-link-gbps"))

(def ^:private fabric-ty
  (str "[:record :topology/fabric [[:observed :i64] [:expected :i64] "
       "[:min-mbps :i64] [:unverified :bool]]]"))

(def ^:private link-ty
  "[:record :topology/link [[:mbps :i64] [:observed :bool]]]")

(def ^:private ring-ty
  "[:record :topology/ring [[:ranks :i64] [:closed :bool]]]")

(defn- compile-cases
  "Append zero-arg wrappers to the real source and execute them, so the guest
   under test is the shipped file plus nothing."
  [result-type cases]
  (let [defs (for [[nm body] cases]
               (str "(defn " nm " [] " result-type " " body ")"))
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " (map first cases)) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [[nm _]] [nm (ir/execute kir (symbol nm) [])]) cases))))

(defn- fabric-lit [{:keys [observed expected min-mbps unverified]}]
  (str "(record-new " fabric-ty " " observed " " expected " " min-mbps " " unverified ")"))

;; ── ring shapes ───────────────────────────────────────────────────────

(def ^:private rings
  [{:ranks 11 :closed false} {:ranks 11 :closed true}
   {:ranks 2 :closed false} {:ranks 1 :closed false} {:ranks 0 :closed false}])

(deftest expected-links-matches-the-cljc-ring
  (let [cases (into {} (map-indexed
                        (fn [i {:keys [ranks closed]}]
                          [(str "e" i)
                           (str "(expected-links (record-new " ring-ty " " ranks " " closed "))")])
                        rings))
        actual (compile-cases ":i64" cases)]
    (doseq [[i {:keys [ranks closed]}] (map-indexed vector rings)]
      (testing (str ranks (if closed " closed" " open"))
        (is (= (:expected (topo/ring-of
                           {:assignments (vec (repeat ranks {:span 1}))}
                           :closed? closed))
               (get actual (str "e" i))))))))

;; ── the four evidence situations ──────────────────────────────────────

(def ^:private fabrics
  ;; Every branch of evidence-code, including the two that are easy to get
  ;; backwards: complete-but-asserted, and expected-zero.
  [{:observed 0 :expected 0 :min-mbps 0 :unverified false}
   {:observed 0 :expected 3 :min-mbps 0 :unverified false}
   {:observed 1 :expected 3 :min-mbps 24000 :unverified false}
   {:observed 3 :expected 3 :min-mbps 1000 :unverified true}
   {:observed 3 :expected 3 :min-mbps 940 :unverified false}
   {:observed 4 :expected 3 :min-mbps 24000 :unverified false}
   {:observed 3 :expected 0 :min-mbps 24000 :unverified false}])

(defn- host-fabric [m]
  ;; The cljc entry points take the folded map, so feed them the same numbers
  ;; rather than reconstructing them from links — this test is about the two
  ;; implementations of the judgement, not about the fold.
  {:observed (:observed m) :expected (:expected m)
   :min-mbps (:min-mbps m) :unverified? (:unverified m)})

(deftest evidence-code-matches-the-shipped-artifact
  (let [cases (into {} (map-indexed
                        (fn [i f] [(str "v" i) (str "(evidence-code " (fabric-lit f) ")")])
                        fabrics))
        actual (compile-cases ":i64" cases)
        code {:measured 0 :partial 1 :none 2 :unverified 3}]
    (doseq [[i f] (map-indexed vector fabrics)]
      (testing (pr-str f)
        (is (= (code (topo/evidence (host-fabric f)))
               (get actual (str "v" i))))))))

(deftest strategy-link-gbps-matches-the-shipped-artifact
  (let [cases (into {} (map-indexed
                        (fn [i f] [(str "g" i) (str "(strategy-link-gbps " (fabric-lit f) ")")])
                        fabrics))
        actual (compile-cases ":i64" cases)]
    (doseq [[i f] (map-indexed vector fabrics)]
      (testing (pr-str f)
        (is (= (topo/strategy-link-gbps (host-fabric f))
               (get actual (str "g" i))))))))

(deftest the-gbps-rounding-boundary-is-where-it-says-it-is
  ;; Round-half-up: 500 -> 1, 499 -> 0. Truncation was the original bug (a
  ;; measured 940 Mbps ethernet fleet reported 0 Gbps, the same number an
  ;; unmeasured one reports), so the boundary itself is pinned, not just the
  ;; happy values.
  (let [mbps [0 499 500 940 1000 19499 19500 24000]
        fab (fn [m] {:observed 1 :expected 1 :min-mbps m :unverified false})
        cases (into {} (map-indexed
                        (fn [i m] [(str "r" i) (str "(strategy-link-gbps " (fabric-lit (fab m)) ")")])
                        mbps))
        actual (compile-cases ":i64" cases)]
    (doseq [[i m] (map-indexed vector mbps)]
      (testing (str m " Mbps")
        (is (= (topo/strategy-link-gbps (host-fabric (fab m)))
               (get actual (str "r" i))))))
    (is (= 0 (get actual "r1")) "499 Mbps rounds down")
    (is (= 1 (get actual "r2")) "500 Mbps rounds up")
    (is (= 1 (get actual "r3")) "a real 1 GbE boundary must not read as 0")
    (is (= 20 (get actual "r6")) "19500 reaches the tensor threshold; 19499 does not")
    (is (= 19 (get actual "r5")))))

;; ── per-boundary labels ───────────────────────────────────────────────

(def ^:private links
  [{:mbps 0 :observed true} {:mbps 0 :observed false}
   {:mbps 120 :observed true} {:mbps 499 :observed true} {:mbps 500 :observed true}
   {:mbps 940 :observed true} {:mbps 4999 :observed true} {:mbps 5000 :observed true}
   {:mbps 40000 :observed true} {:mbps 24000 :observed false}])

(deftest link-class-matches-the-shipped-artifact
  (let [src-cases (into {} (map-indexed (fn [i l] [(str "c" i) (str "(link-class-code (record-new "
                                                                   link-ty " " (:mbps l) " "
                                                                   (:observed l) "))")])
                                        links))
        actual (compile-cases ":i64" src-cases)
        code {:unknown 0 :wan 1 :gbe 2 :fast 3}]
    (doseq [[i l] (map-indexed vector links)]
      (testing (pr-str l)
        (let [host (topo/link {:mbps (when (:observed l) (:mbps l))
                               :method :tcp-stream})]
          (is (= (code (topo/link-class host)) (get actual (str "c" i)))))))))
