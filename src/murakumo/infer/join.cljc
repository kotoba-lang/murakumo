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

(ns murakumo.infer.join)

(def tiers
  "Participation tiers, widest-reach first. Each declares the work it can take
   and how it connects. `:reach` is the qualitative contributor-base size."
  {:browser
   {:tier :browser
    :install :none                         ; visit a URL; the tab becomes a worker
    :runtime :webgpu-wasm                  ; kotodama.inference over num/wgsl
    :connect :webrtc                       ; dials OUT to a relay — NAT-free
    :reach :widest                         ; every device with a modern browser
    :can [:media-postproc :small-shard :embarrassingly-parallel :prompt-eval]
    :cannot [:host-large-model :low-latency-pipeline]
    :max-resident-bytes (* 2 1024 1024 1024)}  ; a tab won't hold >~2GB reliably

   :wasm
   {:tier :wasm
    :install :embed                        ; a wasm module inside any host page/app
    :runtime :webgpu-wasm
    :connect :webtransport
    :reach :wide
    :can [:media-postproc :small-shard :embarrassingly-parallel :prompt-eval]
    :cannot [:host-large-model :low-latency-pipeline]
    :max-resident-bytes (* 4 1024 1024 1024)}

   :native
   {:tier :native
    :install :curl-sh                      ; curl murakumo.cloud/join | sh
    :runtime :metal-cuda-cpu               ; rpc-server / ComfyUI, full engines
    :connect :quic                         ; direct QUIC when reachable, else relay
    :reach :narrow                         ; installs + (ideally) inbound reach
    :can [:host-large-model :low-latency-pipeline :media-generate :full-shard]
    :cannot []
    :max-resident-bytes (* 13 1024 1024 1024)}})

(defn tier-of [caps]
  (get tiers (or (:tier caps) :native)))

(defn can?
  "Can a joiner with `caps` take work of `kind`?"
  [caps kind]
  (boolean (some #{kind} (:can (tier-of caps)))))

(defn needs-relay?
  "Browser/wasm ALWAYS need a relay (no inbound). Native needs one only when it
   declares itself un-reachable (behind NAT with no port)."
  [caps]
  (or (contains? #{:browser :wasm} (:tier caps))
      (not (:inbound-reachable? caps))))

(defn enrollment
  "The record a joiner posts to /infer/nodes. did:key is the account (the credits
   ledger pays it); the tier + capabilities drive the scheduler. Pure — the
   browser computes this client-side (it knows its own WebGPU limits, RAM, link)
   and signs it with its in-browser key."
  [{:keys [name did tier mem-bytes link-gbps engine gpu] :as caps}]
  (let [t (tier-of caps)]
    {:node/name name
     :node/did did                          ; did:key:z6Mk… — the account
     :node/tier (:tier t)
     :node/connect (:connect t)
     :node/needs-relay? (needs-relay? caps)
     :node/caps {:engine (or engine (:runtime t))
                 :mem-bytes mem-bytes
                 :max-resident-bytes (min (or mem-bytes (:max-resident-bytes t))
                                          (:max-resident-bytes t))
                 :link-gbps link-gbps
                 :gpu gpu}
     :node/can (:can t)}))

(defn eligible-for-work?
  "Extends murakumo.infer.schedule: a node (enrolled) can take a job when its
   tier `:can` covers the job's `:work-kind` AND the job's residency fits."
  [node {:keys [work-kind resident-bytes] :as _job}]
  (and (some #{work-kind} (:node/can node))
       (<= (or resident-bytes 0)
           (get-in node [:node/caps :max-resident-bytes] 0))))

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
