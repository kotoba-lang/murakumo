;; murakumo.infer.relay — the work-dispatch protocol + queue as pure data (cljc).
;;
;; The transport last mile: browser/wasm workers can't be dialed inbound, so they
;; dial OUT to a relay and PULL work over that one connection (v1 = WSS; a
;; WebRTC/WebTransport upgrade swaps the socket, not this protocol). This
;; namespace is the relay's brain — a pure state machine over messages and a
;; work queue — so it runs identically in the bb relay server, the CF Worker,
;; JVM tests, and (for the client half) inside the browser worker.
;;
;; Protocol (JSON on the wire, EDN here). Every message is {:msg <kind> …}:
;;   worker→relay  :hello   {:did :tier :caps}     enroll this connection
;;                 :ready   {}                      "give me work"
;;                 :result  {:job-id :output :ms}   done — settle + next
;;                 :ping    {}
;;   relay→worker  :welcome {:worker-id}
;;                 :job     {:job-id :kind :input :price}
;;                 :idle    {}                       nothing queued
;;                 :settled {:job-id :credits}       credited to your did
;;
;; Fair dispatch, at-least-once: a job assigned to a worker that disconnects
;; before :result is requeued (lease expiry), so no work is lost and none is
;; double-credited (a late duplicate :result for an already-settled job is a
;; no-op).

(ns murakumo.infer.relay)

(defn init
  "Empty relay state."
  []
  {:workers {}            ; worker-id → {:did :tier :caps :job-id | nil}
   :queue []              ; pending jobs (FIFO)
   :assigned {}           ; job-id → {:worker-id :job :at-ms}
   :settled #{}           ; job-ids already credited (dedupe)
   :next 0})              ; monotonic id counter (ids passed in; see note)

(defn- gen-id [state prefix]
  ;; ids are derived from the injected counter to stay pure/reproducible
  [(str prefix "-" (:next state)) (update state :next inc)])

(defn enqueue
  "Add a job {:kind :input :price} to the queue. Returns [job-id state']."
  [state job]
  (let [[jid state] (gen-id state "job")]
    [jid (update state :queue conj (assoc job :job-id jid))]))

(defn on-hello
  "A worker connection identifies itself. Returns [worker-id state']."
  [state {:keys [did tier caps]}]
  (let [[wid state] (gen-id state "w")]
    [wid (assoc-in state [:workers wid] {:did did :tier tier :caps caps :job-id nil})]))

(defn- eligible-job
  "First queued job this worker's tier can take (browser/wasm: light work only)."
  [state worker]
  (let [can (get-in worker [:caps :can] #{})]
    (first (filter #(or (empty? can) (some #{(:kind %)} can)) (:queue state)))))

(defn on-ready
  "Worker asks for work. → [reply state'] where reply is {:msg :job …} or
   {:msg :idle}. Leases the job to the worker (at `now-ms`)."
  [state worker-id now-ms]
  (let [worker (get-in state [:workers worker-id])]
    (if-let [job (and worker (eligible-job state worker))]
      (let [jid (:job-id job)
            state (-> state
                      (update :queue (fn [q] (vec (remove #(= jid (:job-id %)) q))))
                      (assoc-in [:assigned jid] {:worker-id worker-id :job job :at-ms now-ms})
                      (assoc-in [:workers worker-id :job-id] jid))]
        [{:msg :job :job-id jid :kind (:kind job) :input (:input job) :price (:price job)}
         state])
      [{:msg :idle} state])))

(defn on-result
  "Worker returns a job result. Settles credits to the worker's did (once) and
   frees the worker. → [reply state'] where reply is {:msg :settled …} or nil
   for a stale/duplicate result."
  [state worker-id {:keys [job-id output ms]}]
  (let [assignment (get-in state [:assigned job-id])]
    (cond
      (contains? (:settled state) job-id) [nil state]           ; duplicate — no-op
      (not= worker-id (:worker-id assignment)) [nil state]      ; not the leaseholder
      :else
      (let [did (get-in state [:workers worker-id :did])
            price (get-in assignment [:job :price] 0)
            state (-> state
                      (update :assigned dissoc job-id)
                      (update :settled conj job-id)
                      (assoc-in [:workers worker-id :job-id] nil))]
        [{:msg :settled :job-id job-id :did did :credits price :output output :ms ms}
         state]))))

(defn expire-leases
  "Requeue jobs whose lease is older than `ttl-ms` (worker vanished). Keeps
   at-least-once delivery. Returns state'."
  [state now-ms ttl-ms]
  (let [dead (for [[jid {:keys [at-ms job worker-id]}] (:assigned state)
                   :when (> (- now-ms at-ms) ttl-ms)]
               [jid job worker-id])]
    (reduce (fn [st [jid job wid]]
              (-> st
                  (update :assigned dissoc jid)
                  (update :queue conj job)
                  (assoc-in [:workers wid :job-id] nil)))
            state dead)))

(defn drop-worker
  "Worker disconnected: free its lease (requeue) and forget it."
  [state worker-id now-ms]
  (let [jid (get-in state [:workers worker-id :job-id])
        job (get-in state [:assigned jid :job])]
    (cond-> (update state :workers dissoc worker-id)
      jid (-> (update :assigned dissoc jid)
              (update :queue conj job)))))

(defn stats [state]
  {:workers (count (:workers state))
   :queued (count (:queue state))
   :in-flight (count (:assigned state))
   :settled (count (:settled state))})
