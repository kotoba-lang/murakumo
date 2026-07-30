;; murakumo.infer.join — participation tiers for the fleet (pure cljc).
;;
;; W6 product-shell + T6.4: tier max-resident, needs-relay?, can?, clamp,
;; eligible-for-work? require the shipped `:infer-join` KIR on **every**
;; platform. Host pure mirrors are gone — cljs/nbb must preload shipped KIR
;; (resources/ via nbb cwd, register-kir!, or set-resource-loader!) before
;; requiring this ns (ADR-260731-w6-t64-infer-small-mirror-delete).
;; Tier maps and partition-work folds stay host.

(ns murakumo.infer.join
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-join)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(defn- tier-code
  "0 browser | 1 wasm | 2 native (default)."
  [tier]
  (case tier
    :browser 0
    :wasm 1
    :native 2
    2))

(defn- max-res-for-tier [code]
  (oracle/i64->host (o 'max-resident-bytes [(oracle/as-i64 code)])))

(def tiers
  "Participation tiers, widest-reach first."
  {:browser
   {:tier :browser
    :install :none
    :runtime :webgpu-wasm
    :connect :webrtc
    :reach :widest
    :can [:media-postproc :small-shard :embarrassingly-parallel :prompt-eval]
    :cannot [:host-large-model :low-latency-pipeline]
    :max-resident-bytes (max-res-for-tier 0)}

   :wasm
   {:tier :wasm
    :install :embed
    :runtime :webgpu-wasm
    :connect :webtransport
    :reach :wide
    :can [:media-postproc :small-shard :embarrassingly-parallel :prompt-eval]
    :cannot [:host-large-model :low-latency-pipeline]
    :max-resident-bytes (max-res-for-tier 1)}

   :native
   {:tier :native
    :install :curl-sh
    :runtime :metal-cuda-cpu
    :connect :quic
    :reach :narrow
    :can [:host-large-model :low-latency-pipeline :media-generate :full-shard]
    :cannot []
    :max-resident-bytes (max-res-for-tier 2)}})

(defn tier-of [caps]
  (get tiers (or (:tier caps) :native)))

(defn can?
  "Can a joiner with `caps` take work of `kind`? Profile 5: guest :bool."
  [caps kind]
  (oracle/bool->host
   (o 'can? [(oracle/as-i64 (tier-code (:tier (tier-of caps))))
             (name kind)])))

(defn needs-relay?
  "Browser/wasm ALWAYS need a relay (no inbound). Native only when un-reachable.
   Profile 5: guest :bool."
  [caps]
  (oracle/bool->host
   (o 'needs-relay?
      [(oracle/as-i64 (tier-code (:tier (tier-of caps))))
       (true? (:inbound-reachable? caps))])))

(defn enrollment
  "The record a joiner posts to /infer/nodes."
  [{:keys [name did tier mem-bytes link-gbps engine gpu] :as caps}]
  (let [t (tier-of caps)
        tmax (:max-resident-bytes t)
        max-res (oracle/i64->host
                 (o 'clamp-resident
                    [(oracle/option-i64 mem-bytes)
                     (oracle/as-i64 tmax)]))]
    {:node/name name
     :node/did did
     :node/tier (:tier t)
     :node/connect (:connect t)
     :node/needs-relay? (needs-relay? caps)
     :node/caps {:engine (or engine (:runtime t))
                 :mem-bytes mem-bytes
                 :max-resident-bytes max-res
                 :link-gbps link-gbps
                 :gpu gpu}
     :node/can (:can t)}))

(defn eligible-for-work?
  "A node can take a job when its tier :can covers work-kind AND residency fits.
   Profile 5: can-kind arg and result are :bool."
  [node {:keys [work-kind resident-bytes] :as _job}]
  (let [can-kind (boolean (some #{work-kind} (:node/can node)))
        max-res (or (get-in node [:node/caps :max-resident-bytes]) 0)
        res (or resident-bytes 0)]
    (oracle/bool->host
     (o 'eligible-for-work?
        [can-kind
         (oracle/as-i64 max-res)
         (oracle/as-i64 res)]))))

(defn partition-work
  "Route a batch of jobs across enrolled nodes by tier."
  [nodes jobs]
  (let [native (filter #(= :native (:node/tier %)) nodes)
        swarm (filter #(#{:browser :wasm} (:node/tier %)) nodes)]
    (reduce
     (fn [acc job]
       (cond
         (and (some #(eligible-for-work? % job) native)
              (#{:host-large-model :low-latency-pipeline :media-generate :full-shard}
               (:work-kind job)))
         (update acc :native conj job)

         (some #(eligible-for-work? % job) swarm)
         (update acc :swarm conj job)

         (some #(eligible-for-work? % job) native)
         (update acc :native conj job)

         :else (update acc :unschedulable conj job)))
     {:native [] :swarm [] :unschedulable []}
     jobs)))
