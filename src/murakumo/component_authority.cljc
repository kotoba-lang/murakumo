(ns murakumo.component-authority
  "Pure control-plane ownership of Component placement epochs.

  Murakumo decides where a Component may run and advances its epoch whenever
  that authority is revoked. Runtime hosts consume the emitted exact events;
  they do not infer authority from eventually-consistent placement telemetry.

  W6 product-shell + T6.4: identifier?/epochs/sequence pure helpers + op/event
  tokens require the shipped `:component-authority` KIR on **every** platform.
  Host pure mirrors are gone — cljs/nbb must preload shipped KIR (resources/
  via nbb cwd, register-kir!, or set-resource-loader!) before requiring this ns
  (ADR-260731-w6-t64-cauth-mirror-delete).
  Event maps + ed25519 stay host."
  (:require [clojure.string :as str]
            [kotoba.abi.contract :as abi]
            [murakumo.kotoba.oracle :as oracle]
            #?(:clj [ed25519.core :as ed])))

(def ^:private oid :component-authority)

(defn- o
  "Call a pure export. Requires the shipped oracle on every platform (T6.4)."
  [export args]
  (oracle/require-ready! oid)
  (oracle/call oid export args))

(defn- o-record
  "T5.2: structural host map → call-record (requires shipped oracle)."
  [export host-map field-specs]
  (oracle/require-ready! oid)
  (oracle/call-record oid export host-map field-specs))

;; ── scalars (oracle SSoT) ──────────────────────────────────────────────

(def event-version
  (oracle/i64->host (o 'event-version [])))

(def format-v1
  "Envelope format token. Kotoba SSoT (requires oracle)."
  (o 'format-v1 []))

(def algorithm-ed25519
  "Signing algorithm token. Kotoba SSoT (requires oracle)."
  (o 'algorithm-ed25519 []))

(def op-place
  "Place command op token. Kotoba SSoT (requires oracle)."
  (o 'op-place []))

(def op-revoke
  (o 'op-revoke []))

(def op-unknown
  (o 'op-unknown []))

(def event-placed
  (o 'event-placed []))

(def event-revoked
  (o 'event-revoked []))

(defn initial-state []
  {:epochs {} :placements {} :sequence 0})

(defn- utf8-len [s]
  #?(:clj (count (.getBytes ^String s "UTF-8"))
     :cljs (count s)))

(defn- identifier?
  "Bounded non-blank identifier. Kotoba `identifier-len-ok?` (required).
   Host projects blank + UTF-8 length."
  [x]
  (if-not (string? x)
    false
    (oracle/bool->host
     (o 'identifier-len-ok?
        [(boolean (str/blank? x))
         (oracle/as-i64 (utf8-len x))]))))

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
     (let [fmt (keyword format-v1)
           alg (keyword algorithm-ed25519)
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
  epoch; hosts receive the same current authority generation.
  Epoch/sequence via kotoba (required)."
  [state component-cid node]
  (when-not (and (identifier? component-cid) (identifier? node))
    (reject :invalid-placement "Component CID and node must be bounded identifiers"
            {:component-cid component-cid :node node}))
  (let [prev (long (or (get-in state [:epochs component-cid]) 0))
        epoch (oracle/i64->host
               (o-record 'place-epoch
                         {:prev prev}
                         [[:prev :i64]]))
        seq' (oracle/i64->host
              (o-record 'next-sequence
                        {:sequence (:sequence state)}
                        [[:sequence :i64]]))
        kind (keyword (o 'event-kind [op-place]))
        state' (-> state
                   (assoc-in [:epochs component-cid] epoch)
                   (update-in [:placements component-cid] (fnil conj #{}) node)
                   (assoc :sequence seq'))]
    [state' (event state' kind component-cid epoch node)]))

(defn revoke
  "Revoke all existing placements and advance the Component epoch.

  Advancing even when no placement is currently observed is intentional:
  delayed or partitioned hosts holding an older lease must still fence.
  Epoch/sequence via kotoba (required). T5.2: call-record for epoch/seq."
  [state component-cid]
  (when-not (identifier? component-cid)
    (reject :invalid-component "Component CID must be a bounded identifier"
            {:component-cid component-cid}))
  (let [prev (long (or (get-in state [:epochs component-cid]) 0))
        epoch (oracle/i64->host
               (o-record 'revoke-epoch
                         {:prev prev}
                         [[:prev :i64]]))
        seq' (oracle/i64->host
              (o-record 'next-sequence
                        {:sequence (:sequence state)}
                        [[:sequence :i64]]))
        kind (keyword (o 'event-kind [op-revoke]))
        state' (-> state
                   (assoc-in [:epochs component-cid] epoch)
                   (update :placements dissoc component-cid)
                   (assoc :sequence seq'))]
    [state' (event state' kind component-cid epoch nil)]))

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
