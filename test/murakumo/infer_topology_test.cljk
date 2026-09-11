;; murakumo.infer-topology-test — the evidence gate, in both directions.
;;
;; The claim under test is narrow and it is the whole point of the namespace:
;; **a fleet nobody measured cannot produce a fast-link plan, and a fleet
;; somebody measured can.** A gate that only ever refuses is as empty as one
;; that only ever passes, so every refusal case here is paired with the
;; measurement that lifts it.

(ns murakumo.infer-topology-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.topology :as topo]))

(def ^:private model
  ;; kv-heads 8 over 4 ranks divides, so a fast link reaches "tensor" rather
  ;; than falling through to "expert". That makes the strategy a sharp probe
  ;; of the link number instead of a blurry one.
  {:model/experts 128 :model/kv-heads 8})

(defn- plan-of
  "A shard plan shaped like murakumo.infer.plan's, with `n` serving ranks."
  [n]
  {:assignments (vec (for [i (range n)] {:node {:name (str "n" i)} :span 4}))})

(defn- chain
  "`n` ranks wired as an open pipeline chain, every boundary observed the same
  way."
  [n {:keys [mbps method]}]
  (let [ring (topo/ring-of (plan-of n))]
    (topo/fabric ring
                 (for [i (range (:expected ring))]
                   (topo/link {:from (str "n" i) :to (str "n" (inc i))
                               :mbps mbps :method method})))))

;; ── how many boundaries a plan owes evidence for ──────────────────────

(deftest ring-boundaries-follow-the-serving-ranks
  (testing "an open pipeline chain has one boundary fewer than ranks"
    (is (= 10 (:expected (topo/ring-of (plan-of 11))))))
  (testing "a closed ring carries the last rank back to the first"
    (is (= 11 (:expected (topo/ring-of (plan-of 11) :closed? true)))))
  (testing "one rank has no interconnect to have an opinion about"
    (is (= 0 (:expected (topo/ring-of (plan-of 1))))))
  (testing "ranks cut to zero layers are not on the wire"
    (is (= 1 (:expected (topo/ring-of
                         {:assignments [{:span 4} {:span 4} {:span 0}]}))))))

;; ── the four situations are four situations ───────────────────────────

(deftest evidence-distinguishes-absence-from-observation
  (testing "nothing observed"
    (is (= :none (topo/evidence (chain 4 {:mbps nil :method :tcp-stream})))))
  (testing "some boundaries observed"
    (let [ring (topo/ring-of (plan-of 4))
          f (topo/fabric ring [(topo/link {:from "n0" :to "n1" :mbps 940
                                           :method :tcp-stream})])]
      (is (= :partial (topo/evidence f)))))
  (testing "every boundary carries only what the NIC claims"
    (is (= :unverified (topo/evidence (chain 4 {:mbps 1000 :method :nominal})))))
  (testing "every boundary carries real traffic"
    (is (= :measured (topo/evidence (chain 4 {:mbps 940 :method :tcp-stream}))))))

;; ── the gate, refusing ────────────────────────────────────────────────

(deftest an-unmeasured-fleet-cannot-claim-a-fast-link
  ;; Delete the gate in strategy-link-gbps and this test goes red: 40000 Mbps
  ;; of *claimed* Thunderbolt is 40 Gbps, comfortably over choose-strategy's
  ;; 20 Gbps tensor threshold. The whole guard is that nobody transferred a
  ;; byte to find that out.
  (let [d (topo/decide {:fabric (chain 4 {:mbps 40000 :method :nominal})
                        :model model})]
    (is (= :pipeline (:strategy d)) "nominal Thunderbolt must not reach tensor")
    (is (= 0 (:link-gbps d)))
    (is (= :unverified (:evidence d)))
    (is (true? (:gated? d)))))

(deftest a-half-measured-fleet-cannot-claim-a-fast-link
  (let [ring (topo/ring-of (plan-of 4))
        f (topo/fabric ring
                       ;; two of three boundaries measured, both very fast
                       [(topo/link {:from "n0" :to "n1" :mbps 38000 :method :tcp-stream})
                        (topo/link {:from "n1" :to "n2" :mbps 37500 :method :tcp-stream})])
        d (topo/decide {:fabric f :model model})]
    (is (= :partial (:evidence d)))
    (is (= :pipeline (:strategy d)) "an unmeasured boundary is not a fast one")
    (is (= 0 (:link-gbps d)))))

;; ── the gate, passing ─────────────────────────────────────────────────

(deftest a-measured-fast-fabric-does-reach-tensor
  ;; The other direction. Without this, the namespace would be a gate that
  ;; refuses everything, which decides nothing.
  (let [d (topo/decide {:fabric (chain 4 {:mbps 24000 :method :tcp-stream})
                        :model model})]
    (is (= :measured (:evidence d)))
    (is (= 24 (:link-gbps d)))
    (is (= :tensor (:strategy d)))
    (is (false? (:gated? d)))))

;; ── the finding this namespace exists for ─────────────────────────────

(deftest pipeline-from-measurement-is-distinguishable-from-pipeline-from-silence
  ;; Both are :pipeline. Before this namespace, both were also the same value
  ;; arriving at choose-strategy -- `(or link-gbps 0)`. The fleet's real
  ;; interconnect, measured 2026-08-15, is the first of these.
  (let [measured (topo/decide {:fabric (chain 11 {:mbps 940 :method :tcp-stream})
                               :model model})
        silent (topo/decide {:fabric (chain 11 {:mbps nil :method :tcp-stream})
                             :model model})]
    (is (= :pipeline (:strategy measured) (:strategy silent)))
    (is (not= (:evidence measured) (:evidence silent))
        "same plan, different reason — that difference is the deliverable")
    (is (= [:measured 1 false] [(:evidence measured) (:link-gbps measured)
                                (:gated? measured)]))
    (is (= [:none 0 true] [(:evidence silent) (:link-gbps silent)
                           (:gated? silent)]))))

;; ── folding rules that are easy to get quietly wrong ──────────────────

(deftest the-slowest-measured-boundary-sets-the-fabric-speed
  (let [ring (topo/ring-of (plan-of 4))
        f (topo/fabric ring [(topo/link {:from "n0" :to "n1" :mbps 24000 :method :tcp-stream})
                             (topo/link {:from "n1" :to "n2" :mbps 940 :method :tcp-stream})
                             (topo/link {:from "n2" :to "n3" :mbps 23000 :method :tcp-stream})])]
    (is (= 940 (:min-mbps f)))
    (is (= :measured (topo/evidence f)))
    (is (= :pipeline (:strategy (topo/decide {:fabric f :model model})))
        "one 1 GbE hop in a Thunderbolt fabric is still a 1 GbE fabric")))

(deftest an-unobserved-boundary-does-not-masquerade-as-a-dead-one
  ;; If unmeasured links folded in as 0 Mbps, the minimum would collapse to 0
  ;; and a partially-measured fast fabric would look like a measured dead one.
  ;; Coverage is what reports absence; the minimum reports speed.
  (let [ring (topo/ring-of (plan-of 4))
        f (topo/fabric ring [(topo/link {:from "n0" :to "n1" :mbps 24000 :method :tcp-stream})
                             (topo/link {:from "n1" :to "n2" :mbps nil :method :tcp-stream})])]
    (is (= 24000 (:min-mbps f)))
    (is (= 1 (:observed f)))
    (is (= :partial (topo/evidence f)))))

(deftest one-asserted-hop-taints-an-otherwise-measured-fabric
  ;; Provenance folds like speed does: worst boundary wins. Two real transfers
  ;; and one NIC claim is not "mostly measured", it is a ring nobody measured
  ;; end to end — and the claim is the fast one, which is how this would have
  ;; been convenient to get wrong.
  (let [ring (topo/ring-of (plan-of 4))
        f (topo/fabric ring [(topo/link {:from "n0" :to "n1" :mbps 24000 :method :tcp-stream})
                             (topo/link {:from "n1" :to "n2" :mbps 24000 :method :tcp-stream})
                             (topo/link {:from "n2" :to "n3" :mbps 40000 :method :nominal})])]
    (is (= 3 (:observed f)) "the claim is still an observation")
    (is (= :unverified (topo/evidence f)))
    (is (= 0 (topo/strategy-link-gbps f)))
    (is (= :pipeline (:strategy (topo/decide {:fabric f :model model}))))))

(deftest a-boundary-observed-as-dead-is-an-observation
  ;; 0 Mbps from a real transfer attempt is a fact, unlike nil. It is still
  ;; not usable, so it does not count toward coverage -- but the distinction
  ;; is the one this whole namespace is built on, so it is pinned.
  (let [dead (topo/link {:from "a" :to "b" :mbps 0 :method :tcp-stream})
        absent (topo/link {:from "a" :to "b" :mbps nil :method :tcp-stream})]
    (is (false? (topo/usable-link? dead)))
    (is (false? (topo/usable-link? absent)))
    (is (= :unknown (topo/link-class dead) (topo/link-class absent)))))

;; ── labels ────────────────────────────────────────────────────────────

(deftest link-class-labels
  (testing "this fleet's measured ethernet"
    (is (= :gbe (topo/link-class (topo/link {:mbps 940 :method :tcp-stream})))))
  (testing "a slow tunnel"
    (is (= :wan (topo/link-class (topo/link {:mbps 120 :method :tcp-stream})))))
  (testing "a Thunderbolt bridge"
    (is (= :fast (topo/link-class (topo/link {:mbps 24000 :method :tcp-stream})))))
  (testing "the class labels the number, whatever produced it"
    ;; A claimed 40 Gbps really is in the fast class *as a claim*. Refusing it
    ;; is the evidence gate's job, and doing it twice — once by mislabelling
    ;; the number here as well — would leave the report unable to say what the
    ;; fleet is claiming, which is exactly what you want to read while
    ;; deciding whether to buy the cables.
    (is (= :fast (topo/link-class (topo/link {:mbps 40000 :method :nominal}))))
    (is (= 0 (topo/strategy-link-gbps
              (topo/fabric {:expected 1}
                           [(topo/link {:mbps 40000 :method :nominal})])))))
  (testing "never observed has no class"
    (is (= :unknown (topo/link-class (topo/link {:mbps nil :method :tcp-stream}))))))
