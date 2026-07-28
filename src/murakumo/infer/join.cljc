;; murakumo.infer.join — participation tiers for the fleet (pure cljc).
;;
;; murakumo's headline: ANYONE can contribute compute — not just people who can
;; run a native binary. A browser tab (WebGPU + wasm) or an embedded wasm worker
;; is a first-class fleet member. This namespace models the three tiers, what
;; work each can take, and — crucially — how they connect, because that is where
;; browser/wasm turn out to be EASIER than native, not harder.
;;
;; The connectivity insight (the differentiator):
;;   Native full nodes need an inbound-reachable rpc-server → NAT/firewall pain.
;;   Browser/wasm workers only ever dial OUT to a relay (WebRTC/WebTransport),
;;   so they traverse NAT for free — a laptop on hotel wifi can contribute.
;; That inverts the usual "native is more capable" assumption: for reach, the
;; browser tier is the widest possible contributor base (every device with a
;; modern browser), and it needs zero install.
;;
;; Pure data → data: runs in bb (the operator), the CF Worker (enrollment +
;; scheduling), JVM tests, AND inside the wasm worker itself (a joiner computes
;; its own capabilities client-side before enrolling).
;;
;; W6 product-shell authority: tier max-resident, needs-relay?, can?, clamp,
;; eligible-for-work? DELEGATE to precompiled kotoba/infer_join_core.kotoba on
;; JVM. Tier maps and partition-work folds stay host.

(ns murakumo.infer.join
  (:require #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :infer-join)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(defn- tier-code
  "0 browser | 1 wasm | 2 native (default)."
  [tier]
  (case tier
    :browser 0
    :wasm 1
    :native 2
    2))

(def tiers
  "Participation tiers, widest-reach first. Each declares the work it can take
   and how it connects. `:reach` is the qualitative contributor-base size.
   JVM: :max-resident-bytes from oracle `max-resident-bytes` by tier code."
  {:browser
   {:tier :browser
    :install :none
    :runtime :webgpu-wasm
    :connect :webrtc
    :reach :widest
    :can [:media-postproc :small-shard :embarrassingly-parallel :prompt-eval]
    :cannot [:host-large-model :low-latency-pipeline]
    :max-resident-bytes #?(:clj (long (o 'max-resident-bytes [0]))
                           :cljs (* 2 1024 1024 1024))}

   :wasm
   {:tier :wasm
    :install :embed
    :runtime :webgpu-wasm
    :connect :webtransport
    :reach :wide
    :can [:media-postproc :small-shard :embarrassingly-parallel :prompt-eval]
    :cannot [:host-large-model :low-latency-pipeline]
    :max-resident-bytes #?(:clj (long (o 'max-resident-bytes [1]))
                           :cljs (* 4 1024 1024 1024))}

   :native
   {:tier :native
    :install :curl-sh
    :runtime :metal-cuda-cpu
    :connect :quic
    :reach :narrow
    :can [:host-large-model :low-latency-pipeline :media-generate :full-shard]
    :cannot []
    :max-resident-bytes #?(:clj (long (o 'max-resident-bytes [2]))
                           :cljs (* 13 1024 1024 1024))}})

(defn tier-of [caps]
  (get tiers (or (:tier caps) :native)))

(defn can?
  "Can a joiner with `caps` take work of `kind`?
   JVM: kotoba `can?` with tier code + kind name (no colon)."
  [caps kind]
  #?(:clj
     (= 1 (o 'can? [(long (tier-code (:tier (tier-of caps))))
                    (name kind)]))
     :cljs (boolean (some #{kind} (:can (tier-of caps))))))

(defn needs-relay?
  "Browser/wasm ALWAYS need a relay (no inbound). Native needs one only when it
   declares itself un-reachable (behind NAT with no port).
   JVM: kotoba `needs-relay?`."
  [caps]
  #?(:clj
     (= 1 (o 'needs-relay?
             [(long (tier-code (:tier (tier-of caps))))
              ;; nil/false ⇒ not inbound (needs relay); only true is inbound=1
              (if (true? (:inbound-reachable? caps)) 1 0)]))
     :cljs
     (or (contains? #{:browser :wasm} (:tier caps))
         (not (:inbound-reachable? caps)))))

(defn enrollment
  "The record a joiner posts to /infer/nodes. did:key is the account (the credits
   ledger pays it); the tier + capabilities drive the scheduler. Pure — the
   browser computes this client-side (it knows its own WebGPU limits, RAM, link)
   and signs it with its in-browser key.
   JVM: max-resident clamp via kotoba `clamp-resident`."
  [{:keys [name did tier mem-bytes link-gbps engine gpu] :as caps}]
  (let [t (tier-of caps)
        tmax (:max-resident-bytes t)
        max-res #?(:clj (long (o 'clamp-resident
                                 [(if (some? mem-bytes) (long mem-bytes) -1)
                                  (long tmax)]))
                   :cljs (min (or mem-bytes tmax) tmax))]
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
  "Extends murakumo.infer.schedule: a node (enrolled) can take a job when its
   tier `:can` covers the job's `:work-kind` AND the job's residency fits.
   JVM: kotoba `eligible-for-work?` with host-projected can-kind 0/1."
  [node {:keys [work-kind resident-bytes] :as _job}]
  #?(:clj
     (let [can-kind (if (some #{work-kind} (:node/can node)) 1 0)
           max-res (long (get-in node [:node/caps :max-resident-bytes] 0))
           res (long (or resident-bytes 0))]
       (= 1 (o 'eligible-for-work? [can-kind max-res res])))
     :cljs
     (and (some #{work-kind} (:node/can node))
          (<= (or resident-bytes 0)
              (get-in node [:node/caps :max-resident-bytes] 0)))))

(defn partition-work
  "Route a batch of jobs across enrolled nodes by tier: heavy/low-latency work to
   native, embarrassingly-parallel + media-postproc + small shards to the
   browser/wasm swarm (the widest, NAT-free pool). Returns
   {:native [...] :swarm [...] :unschedulable [...]}."
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
