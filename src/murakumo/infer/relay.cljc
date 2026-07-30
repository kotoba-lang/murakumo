;; murakumo.infer.relay — the work-dispatch protocol + queue as pure data (cljc).
;;
;; W6 product-shell: make-id / lease-expired? / msg kind strings via kotoba
;; infer_relay_core when oracle loadable (JVM or cljs/nbb).
;; Queue/worker map state machine stays host.

(ns murakumo.infer.relay
  "Work-dispatch protocol + queue as pure data."
  (:require [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :infer-relay)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn init
  "Empty relay state."
  []
  {:workers {}
   :queue []
   :assigned {}
   :settled #{}
   :next 0})

(defn- gen-id [state prefix]
  (let [n (:next state)
        id (try-oracle
            #(o 'make-id [(str prefix) (oracle/as-i64 n)])
            #(str prefix "-" n))]
    [id (update state :next inc)]))

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
  (let [worker (get-in state [:workers worker-id])
        job-kw (keyword (try-oracle #(o 'msg-job []) (fn [] "job")))
        idle-kw (keyword (try-oracle #(o 'msg-idle []) (fn [] "idle")))]
    (if-let [job (and worker (eligible-job state worker))]
      (let [jid (:job-id job)
            state (-> state
                      (update :queue (fn [q] (vec (remove #(= jid (:job-id %)) q))))
                      (assoc-in [:assigned jid] {:worker-id worker-id :job job :at-ms now-ms})
                      (assoc-in [:workers worker-id :job-id] jid))]
        [{:msg job-kw
          :job-id jid :kind (:kind job) :input (:input job) :price (:price job)}
         state])
      [{:msg idle-kw} state])))

(defn on-result
  "Worker returns a job result. Settles credits to the worker's did (once) and
   frees the worker. → [reply state'] where reply is {:msg :settled …} or nil
   for a stale/duplicate result."
  [state worker-id {:keys [job-id output ms]}]
  (let [assignment (get-in state [:assigned job-id])
        settled-kw (keyword (try-oracle #(o 'msg-settled []) (fn [] "settled")))]
    (cond
      (contains? (:settled state) job-id) [nil state]
      (not= worker-id (:worker-id assignment)) [nil state]
      :else
      (let [did (get-in state [:workers worker-id :did])
            price (get-in assignment [:job :price] 0)
            state (-> state
                      (update :assigned dissoc job-id)
                      (update :settled conj job-id)
                      (assoc-in [:workers worker-id :job-id] nil))]
        [{:msg settled-kw
          :job-id job-id :did did :credits price :output output :ms ms}
         state]))))

(defn expire-leases
  "Requeue jobs whose lease is older than `ttl-ms` (worker vanished)."
  [state now-ms ttl-ms]
  (let [dead (for [[jid {:keys [at-ms job worker-id]}] (:assigned state)
                   :when (try-oracle
                          #(oracle/bool->host
                            (o 'lease-expired?
                               [(oracle/as-i64 now-ms)
                                (oracle/as-i64 at-ms)
                                (oracle/as-i64 ttl-ms)]))
                          #(> (- now-ms at-ms) ttl-ms))]
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
