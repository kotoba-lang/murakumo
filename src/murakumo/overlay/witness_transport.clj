;; murakumo.overlay.witness-transport — wires kotoba.lang.witness-quorum's
;; pre-commit orchestrator onto the overlay QUIC transport (ADR-2607110300
;; Phase 2). Converts witness-quorum's attestation collection from an
;; in-process function call (`create-in-memory-witness-transport`) into a
;; real network round-trip between fleet nodes, using the generalized
;; request/response RPC added to `murakumo.overlay.quic-driver`.
;;
;; Honesty note (ADR-2607110300): every witness reached through this
;; transport is still operated by the same organization (the fleet in
;; fleet.edn). This buys crash-fault tolerance across physical machines,
;; not Byzantine consensus across independent operators -- do not represent
;; it as the latter.

(ns murakumo.overlay.witness-transport
  (:require [kotoba.lang.witness-quorum.attestation :as attestation]
            [kotoba.lang.witness-quorum.selector :as selector]
            [murakumo.overlay.quic-driver :as quic-driver])
  (:import [java.util.concurrent ConcurrentHashMap LinkedBlockingQueue TimeUnit]
           [java.util.function Function]))

(defn fleet-edn->witness-fleet
  "Map murakumo's fleet.edn `:nodes` (each `{:name ... :host ... :roles [...]}`)
  into witness-quorum fleet-cell maps. One witness cell per physical node
  (cell-id \"witness\") -- Phase 2 treats each fleet machine as one witness
  identity, not sub-node cells."
  [{:keys [nodes]}]
  (selector/flatten-fleet (map (fn [node] {:name (:name node) :cells ["witness"]}) nodes)))

(defn node-connect-target
  "Build the `{:host :port}` overlay QUIC requests expect, from a fleet.edn
  node map and the fleet's default p2p port (`:fleet/p2p-port`)."
  [{:keys [rpc-ip host p2p-port]} default-p2p-port]
  {:host (or rpc-ip host) :port (or p2p-port default-p2p-port)})

(defn fleet-edn->node-lookup
  "Build the `node-lookup` fn `create-overlay-witness-transport` needs:
  witness-quorum fleet-cell `:node` (the node name) -> `{:host :port}`."
  [{:keys [nodes] :fleet/keys [p2p-port]}]
  (let [by-name (into {} (map (fn [n] [(:name n) n]) nodes))]
    (fn [node-name]
      (when-let [node (get by-name node-name)]
        (node-connect-target node p2p-port)))))

(defn witness-request-handler
  "Server-side handler a fleet node runs to answer witness-attestation RPCs:
  wraps `attestation/produce-attestation` behind the overlay RPC envelope.
  `opts`: {:cell ... :signer ... :validators ...} -- same shape
  `kotoba.lang.witness-quorum.orchestrator/make-standard-cell-handler`
  takes, so a signer already wired for local (in-memory) attestation works
  unchanged once served over the network."
  [{:keys [cell signer validators]}]
  (fn [payload]
    (attestation/produce-attestation
     {:record-uri (:record-uri payload)
      :record-cid (:record-cid payload)
      :record (:record payload)
      :cell cell
      :rule (:rule payload)
      :validators validators
      :signer signer})))

(defn serve-witness!
  "Start a long-running QUIC RPC listener answering witness-attestation
  requests for one fleet node. `request` is the overlay connect-request map
  (`{:connect {:host :port} ...}`) this node listens on; `handler` is a
  `witness-request-handler` result (or any `(fn [payload] -> response)`).
  Blocks forever -- run in its own thread/process per fleet node."
  [request handler]
  (quic-driver/serve-rpc! request handler))

(defn- computing-queue ^LinkedBlockingQueue [^ConcurrentHashMap queues key]
  (.computeIfAbsent queues key
                     (reify Function
                       (apply [_ _] (LinkedBlockingQueue.)))))

(defn create-overlay-witness-transport
  "witness-quorum WitnessTransport backed by the murakumo overlay QUIC RPC --
  same shape as, and a drop-in network replacement for, witness-quorum's
  own `create-in-memory-witness-transport`: each `:request-attestation`
  call dials the target node over QUIC in a `future`, and the response
  lands on a per-quorum-group `LinkedBlockingQueue` that
  `:subscribe-attestations`'s poll-fn drains. The ONLY thing that changes
  vs. the in-memory transport is that the attestation now crosses a real
  network hop instead of a direct function call.

  `opts`:
    :node-lookup  `(fn [node-name] -> {:host :port} or nil)`, e.g.
                  `fleet-edn->node-lookup`. Resolves a witness-quorum
                  fleet-cell's :node to a dialable target.
    :session      overlay session map (`{:overlay :node :name :principal}`)
                  identifying the CALLING node, threaded into every request
                  envelope.
    :timeout-ms   per-request QUIC timeout. Default
                  `quic-driver/default-timeout-ms`."
  [{:keys [node-lookup session timeout-ms]}]
  (let [queues (ConcurrentHashMap.)
        timeout-ms (or timeout-ms quic-driver/default-timeout-ms)]
    {:request-attestation
     (fn [req]
       (future
         (when-let [target (node-lookup (:node (:cell req)))]
           (let [quic-request {:type "murakumo.overlay.adapter-request"
                                :version 1
                                :transport :quic
                                :session session
                                :connect target}
                 payload {:cell-id (:cell-id (:cell req))
                          :record-uri (:record-uri req)
                          :record-cid (:record-cid req)
                          :record (:record req)
                          :rule (:rule req)}
                 {:keys [ok? response]} (quic-driver/request! quic-request payload timeout-ms)]
             (when (and ok? (map? response) (not (:error response)))
               (.put (computing-queue queues (:quorum-group response)) response)))))
       nil)

     :subscribe-attestations
     (fn [quorum-group]
       (let [q (computing-queue queues quorum-group)]
         (fn [remaining-ms]
           (let [item (.poll q (long (max 0 remaining-ms)) TimeUnit/MILLISECONDS)]
             (if item
               {:status :value :value item}
               {:status :timeout})))))}))
