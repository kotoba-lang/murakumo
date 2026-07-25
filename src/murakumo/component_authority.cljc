(ns murakumo.component-authority
  "Pure control-plane ownership of Component placement epochs.

  Murakumo decides where a Component may run and advances its epoch whenever
  that authority is revoked. Runtime hosts consume the emitted exact events;
  they do not infer authority from eventually-consistent placement telemetry."
  (:require [clojure.string :as str]))

(def event-version 1)
(def event-keys
  #{:murakumo.component/version :murakumo.component/event
    :murakumo.component/component-cid :murakumo.component/epoch
    :murakumo.component/sequence :murakumo.component/node})

(defn initial-state []
  {:epochs {} :placements {} :sequence 0})

(defn- identifier? [x]
  (and (string? x) (not (str/blank? x)) (<= (count x) 4096)))

(defn- reject [reason message data]
  (throw (ex-info message (assoc data :murakumo.component/reason reason))))

(defn valid-event?
  "Strict event validation for the process-boundary representation."
  [event]
  (and (map? event)
       (= event-keys (set (keys event)))
       (= event-version (:murakumo.component/version event))
       (contains? #{:placed :revoked} (:murakumo.component/event event))
       (identifier? (:murakumo.component/component-cid event))
       (pos-int? (:murakumo.component/epoch event))
       (pos-int? (:murakumo.component/sequence event))
       (or (nil? (:murakumo.component/node event))
           (identifier? (:murakumo.component/node event)))
       (if (= :placed (:murakumo.component/event event))
         (some? (:murakumo.component/node event))
         (nil? (:murakumo.component/node event)))))

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
  (let [epoch (get-in state [:epochs component-cid] 1)
        state' (-> state
                   (assoc-in [:epochs component-cid] epoch)
                   (update-in [:placements component-cid] (fnil conj #{}) node)
                   (update :sequence inc))]
    [state' (event state' :placed component-cid epoch node)]))

(defn revoke
  "Revoke all existing placements and advance the Component epoch.

  Advancing even when no placement is currently observed is intentional:
  delayed or partitioned hosts holding an older lease must still fence."
  [state component-cid]
  (when-not (identifier? component-cid)
    (reject :invalid-component "Component CID must be a bounded identifier"
            {:component-cid component-cid}))
  (let [epoch (inc (get-in state [:epochs component-cid] 0))
        state' (-> state
                   (assoc-in [:epochs component-cid] epoch)
                   (update :placements dissoc component-cid)
                   (update :sequence inc))]
    [state' (event state' :revoked component-cid epoch nil)]))

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
                   (case (:op command)
                     :place (place state (:component-cid command) (:node command))
                     :revoke (revoke state (:component-cid command))
                     (reject :unknown-command "Unknown Component authority command"
                             {:command command}))]
               (vreset! emitted event)
               state')))
    (publish! @emitted)
    @emitted))

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
