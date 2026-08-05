;; murakumo.infer.join vs the aiueos node's own flattened mirror.
;;
;; kotoba-lang/aiueos carries `os/aiueos/kotoba/murakumo-join-plan.kotoba` so a
;; bare-metal node decides its own fleet participation with no Node or JVM host
;; present. That object cannot call this namespace -- it runs on an OS with
;; neither runtime -- so the two implementations can drift silently. Nothing
;; else checks them against each other.
;;
;; This drives the aiueos object across the full tier x kind x memory x
;; residency matrix and requires it to agree with THIS namespace on all three
;; decisions it packs: eligibility, relay need, and clamped residency.
;;
;; The encoding it has to bridge is real, not cosmetic: upstream uses typed
;; records, `[:option :i64]` and `:string` kind names; the aiueos object ABI is
;; scalar i64 with at most five parameters. `os/aiueos/contracts/murakumo-node-v1.edn`
;; is the authority for that encoding, including the work-kind codes checked here.

(ns murakumo.aiueos-join-plan-parity-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.infer.join :as join]))

(def ^:private aiueos-root
  "Sibling west checkout by default; AIUEOS_ROOT overrides for other layouts."
  (or (System/getenv "AIUEOS_ROOT")
      (str (io/file (System/getProperty "user.dir") ".." "aiueos"))))

(def ^:private source-file
  (io/file aiueos-root "os" "aiueos" "kotoba" "murakumo-join-plan.kotoba"))

;; Authority: os/aiueos/contracts/murakumo-node-v1.edn :work-kinds.
(def ^:private work-kind-codes
  {:media-postproc 1
   :small-shard 2
   :embarrassingly-parallel 3
   :prompt-eval 4
   :host-large-model 5
   :low-latency-pipeline 6
   :media-generate 7
   :full-shard 8})

(def ^:private tier-codes {:browser 0 :wasm 1 :native 2})

(defn- kir []
  (:kir (compiler/compile-source (slurp source-file) :wasm32-kotoba-v1 {})))

(defn- unpack
  "Unpack the object's single packed result into the three decisions it made."
  [packed]
  {:eligible? (= 1 (bit-and packed 1))
   :needs-relay? (= 1 (bit-and (quot packed 2) 1))
   :clamped (quot packed 4)})

(defn- aiueos-plan [k tier inbound? kind mem-bytes resident]
  (let [node (+ (tier-codes tier) (if inbound? 4 0))
        mem-request (if mem-bytes (inc (* 2 mem-bytes)) 0)]
    (unpack (ir/execute k 'aiueos-murakumo-join-plan
                        [node (work-kind-codes kind) mem-request resident]))))

(defn- upstream-plan [tier inbound? kind mem-bytes resident]
  (let [caps {:name "parity" :did "did:parity" :tier tier
              :inbound-reachable? inbound? :mem-bytes mem-bytes}
        node (join/enrollment caps)]
    {:eligible? (join/eligible-for-work? node {:work-kind kind
                                               :resident-bytes resident})
     :needs-relay? (join/needs-relay? caps)
     :clamped (get-in node [:node/caps :max-resident-bytes])}))

(def ^:private tiers [:browser :wasm :native])
(def ^:private kinds (vec (keys work-kind-codes)))
;; nil = no declaration at all, which upstream distinguishes from 0 bytes.
(def ^:private mems [nil 0 1073741824 34359738368])
(def ^:private residents [0 1000 4294967296 13958643713])

(deftest aiueos-object-source-is-present
  (is (.exists source-file)
      (str "aiueos checkout not found at " source-file
           " -- set AIUEOS_ROOT to the kotoba-lang/aiueos checkout. This test is"
           " the only thing checking the bare-metal node against this namespace,"
           " so it fails rather than skips.")))

(deftest join-plan-matches-upstream
  (when (.exists source-file)
    (let [k (kir)]
      (doseq [tier tiers
              inbound? [true false]
              kind kinds
              mem mems
              resident residents]
        (testing (pr-str {:tier tier :inbound? inbound? :kind kind
                          :mem mem :resident resident})
          (is (= (upstream-plan tier inbound? kind mem resident)
                 (aiueos-plan k tier inbound? kind mem resident))))))))

(deftest native-tier-answers-from-native-kinds-only
  ;; Pinned deliberately. This reads as a bug and is upstream's actual
  ;; behaviour, so the mirror must reproduce it rather than "fix" it.
  (when (.exists source-file)
    (let [k (kir)]
      (is (false? (:eligible? (aiueos-plan k :native true :media-postproc nil 1000))))
      (is (true? (:eligible? (aiueos-plan k :native true :media-generate nil 1000))))
      (is (= (:eligible? (upstream-plan :native true :media-postproc nil 1000))
             (:eligible? (aiueos-plan k :native true :media-postproc nil 1000)))))))

(deftest absent-memory-differs-from-zero-bytes
  ;; Upstream carries `[:option :i64]` precisely so "declared 0" and "declared
  ;; nothing" stay distinct; the packed encoding must not collapse them.
  (when (.exists source-file)
    (let [k (kir)]
      (is (not= (:clamped (aiueos-plan k :native true :media-generate nil 0))
                (:clamped (aiueos-plan k :native true :media-generate 0 0))))
      (is (= (:clamped (upstream-plan :native true :media-generate nil 0))
             (:clamped (aiueos-plan k :native true :media-generate nil 0))))
      (is (= (:clamped (upstream-plan :native true :media-generate 0 0))
             (:clamped (aiueos-plan k :native true :media-generate 0 0)))))))

(deftest work-kind-codes-cover-both-upstream-sets
  ;; The codes are a wire format shared across repos; a kind added upstream
  ;; without a code here would silently never be eligible on an aiueos node.
  (is (= (set (keys work-kind-codes))
         (set (concat (:can (join/tiers :browser))
                      (:can (join/tiers :native))))))
  (is (= (range 1 9) (sort (vals work-kind-codes)))))
