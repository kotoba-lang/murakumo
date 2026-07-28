(ns murakumo.component-authority
  "Pure control-plane ownership of Component placement epochs.

  Murakumo decides where a Component may run and advances its epoch whenever
  that authority is revoked. Runtime hosts consume the emitted exact events;
  they do not infer authority from eventually-consistent placement telemetry.

  W6 product-shell: identifier?/epochs/sequence pure helpers via kotoba
  component_authority_core on JVM. Event maps + ed25519 stay host."
  (:require [clojure.string :as str]
            [kotoba.abi.contract :as abi]
            #?(:clj [ed25519.core :as ed])
            #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :component-authority)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(def event-version
  #?(:clj (long (o 'event-version []))
     :cljs 1))

(defn initial-state []
  {:epochs {} :placements {} :sequence 0})

(defn- identifier?
  "JVM: blank? + len via kotoba `identifier-len-ok?` (host projects blank + UTF-8 len)."
  [x]
  #?(:clj
     (and (string? x)
          (= 1 (o 'identifier-len-ok?
                  [(if (str/blank? x) 1 0)
                   (long (count (.getBytes ^String x "UTF-8")))])))
     :cljs (and (string? x) (not (str/blank? x)) (<= (count x) 4096))))

(defn- reject [reason message data]
  (throw (ex-info message (assoc data :murakumo.component/reason reason))))

(defn valid-event?
  "Strict event validation for the process-boundary representation."
  [event]
  (abi/valid-component-authority-event? event))

#?(:clj
   (defn- hex [bytes]
     (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

#?(:clj
   (defn sign-event
     "Create the only production process-boundary representation of EVENT.
     The receiver owns the public-key trust mapping; it is deliberately absent
     from this envelope."
     [event {:keys [seed key-id issuer audience issued-at-ms]}]
     (when-not (and seed (= 32 (count seed))
                    (every? identifier? [key-id issuer audience])
                    (pos-int? issued-at-ms)
                    (valid-event? event))
       (reject :invalid-signing-input
               "Complete Component authority signing input is required" {}))
     (let [fmt #?(:clj (keyword (o 'format-v1 []))
                  :cljs :murakumo.component-authority/v1)
           alg #?(:clj (keyword (o 'algorithm-ed25519 []))
                  :cljs :ed25519)
           unsigned {:format fmt
                     :algorithm alg
                     :key-id key-id
                     :issuer issuer
                     :audience audience
                     :issued-at-ms issued-at-ms
                     :event event}
           payload (.getBytes
                    ^String (abi/component-authority-signing-payload unsigned)
                    "UTF-8")
           envelope (assoc unsigned :signature (hex (ed/sign seed payload)))]
       (when-not (abi/valid-component-authority-envelope? envelope)
         (reject :invalid-envelope "Signed authority envelope is invalid" {}))
       envelope)))

(defn- event [state kind component-cid epoch node]
  {:murakumo.component/version event-version
   :murakumo.component/event kind
   :murakumo.component/component-cid component-cid
   :murakumo.component/epoch epoch
   :murakumo.component/sequence (:sequence state)
   :murakumo.component/node node})

(defn place
  "Authorize COMPONENT-CID on NODE. Returns [new-state event].

  A first placement starts at epoch 1. Adding replicas does not rotate an
  epoch; hosts receive the same current authority generation."
  [state component-cid node]
  (when-not (and (identifier? component-cid) (identifier? node))
    (reject :invalid-placement "Component CID and node must be bounded identifiers"
            {:component-cid component-cid :node node}))
  (let [prev (long (or (get-in state [:epochs component-cid]) 0))
        epoch #?(:clj (long (o 'place-epoch [prev]))
                 :cljs (get-in state [:epochs component-cid] 1))
        seq' #?(:clj (long (o 'next-sequence [(long (:sequence state))]))
                :cljs (inc (:sequence state)))
        state' (-> state
                   (assoc-in [:epochs component-cid] epoch)
                   (update-in [:placements component-cid] (fnil conj #{}) node)
                   (assoc :sequence seq'))]
    [state' (event state' #?(:clj (keyword (o 'event-kind ["place"]))
                             :cljs :placed)
                   component-cid epoch node)]))

(defn revoke
  "Revoke all existing placements and advance the Component epoch.

  Advancing even when no placement is currently observed is intentional:
  delayed or partitioned hosts holding an older lease must still fence."
  [state component-cid]
  (when-not (identifier? component-cid)
    (reject :invalid-component "Component CID must be a bounded identifier"
            {:component-cid component-cid}))
  (let [prev (long (or (get-in state [:epochs component-cid]) 0))
        epoch #?(:clj (long (o 'revoke-epoch [prev]))
                 :cljs (inc prev))
        seq' #?(:clj (long (o 'next-sequence [(long (:sequence state))]))
                :cljs (inc (:sequence state)))
        state' (-> state
                   (assoc-in [:epochs component-cid] epoch)
                   (update :placements dissoc component-cid)
                   (assoc :sequence seq'))]
    [state' (event state' #?(:clj (keyword (o 'event-kind ["revoke"]))
                             :cljs :revoked)
                   component-cid epoch nil)]))
(defn transition
  "Apply one exact authority command to immutable STATE."
  [state command]
  (case (:op command)
    :place (place state (:component-cid command) (:node command))
    :revoke (revoke state (:component-cid command))
    (reject :unknown-command "Unknown Component authority command"
            {:command command})))

(defn apply-command!
  "Atomically apply PLACE/REVOKE and publish its exact event.

  PUBLISH! is the transport boundary (QUIC, Datom log, or local test
  subscriber). A publisher failure is fail-closed to the caller; the state
  remains advanced so retrying publication cannot resurrect an old epoch."
  [state-atom publish! command]
  (when-not (and (instance? #?(:clj clojure.lang.IAtom :cljs cljs.core/Atom) state-atom)
                 (ifn? publish!))
    (reject :invalid-boundary "State atom and event publisher are required" {}))
  (let [emitted (volatile! nil)]
    (swap! state-atom
           (fn [state]
             (let [[state' event]
                   (transition state command)]
               (vreset! emitted event)
               state')))
    (publish! @emitted)
    @emitted))

#?(:clj
   (defn apply-signed-command!
     "Production command path: advance authority, bind the resulting event to
     one audience, sign it, and publish only the signed envelope."
     [state-atom publish! command signing]
     (let [envelope (volatile! nil)
           publish-envelope! (fn [event]
                               (let [signed (sign-event event signing)]
                                 (vreset! envelope signed)
                                 (publish! signed)))]
       (apply-command! state-atom publish-envelope! command)
       @envelope)))

(defn current-epoch [state component-cid]
  (get-in state [:epochs component-cid]))

(defn epoch-source
  "Create the callable source expected by Kototama's Component admission.
  Missing authority fails closed instead of manufacturing epoch 1."
  [state-atom component-cid]
  (fn []
    (or (current-epoch @state-atom component-cid)
        (reject :missing-authority "No Component authority epoch exists"
                {:component-cid component-cid}))))
