;; murakumo.overlay.driver — portable native overlay driver planning.
;;
;; This is the first executable boundary behind `murakumo.cloud connect`: it
;; validates a canonical `dial` argv and emits the session record a real stream or
;; packet driver will later use to open QUIC/WebRTC/WebTransport/relay paths.

(ns murakumo.overlay.driver
  (:require [clojure.string :as str]))

(def required-dial-options
  [:overlay :node :name :from :to :capability :direct :transport])

(def required-relay-options
  [:overlay :name :region :url :transports])

(def required-bootstrap-options
  [:manifest-file])

(declare command-result)

(defn keyword-option [flag]
  (keyword (subs flag 2)))

(defn split-option [flag]
  (let [[option value] (str/split (str flag) #"=" 2)]
    [(keyword-option option) value]))

(defn parse-argv
  "Parse `dial --k v ...` into a command/options map."
  [args]
  (let [[command & flags] args]
    (loop [opts {:command (keyword command)}
           flags flags]
      (if (empty? flags)
        opts
        (let [[flag value & more] flags]
          (cond
            (and (str/starts-with? (str flag) "--")
                 (str/includes? (str flag) "="))
            (let [[option inline-value] (split-option flag)]
              (recur (assoc opts option inline-value)
                     (if value (cons value more) more)))

            (and (str/starts-with? (str flag) "--") value)
            (recur (assoc opts (keyword-option flag) value) more)

            :else
            (recur (update opts :extra (fnil conj []) flag) (cons value more))))))))

(defn missing-options [required opts]
  (filterv #(str/blank? (str (get opts %))) required))

(defn endpoint-kind [endpoint]
  (cond
    (str/starts-with? endpoint "quic://") :quic
    (str/starts-with? endpoint "webrtc://") :webrtc
    (str/starts-with? endpoint "https://") :webtransport
    (str/starts-with? endpoint "relay://") :relay
    :else :unknown))

(defn dial-session
  "Normalised session request for the native overlay driver."
  [opts]
  (cond-> {:type "murakumo.overlay.session"
           :overlay (:overlay opts)
           :node (:node opts)
           :name (:name opts)
           :principal {:from (:from opts)
                       :to (:to opts)
                       :capability (:capability opts)}
           :direct {:transport (:transport opts)
                    :endpoint (:direct opts)
                    :kind (endpoint-kind (:direct opts))}
           :relay (when (:relay opts)
                    {:transport (:relay-transport opts)
                     :endpoint (:relay opts)
                     :kind (endpoint-kind (:relay opts))})}
    (:auth-key opts) (assoc :auth-key (:auth-key opts))))

(defn dial-result
  "Validate parsed driver options and return an executable driver result."
  [opts]
  (let [missing (missing-options required-dial-options opts)]
    (cond
      (not= :dial (:command opts))
      {:ok? false
       :reason :unknown-command
       :command (:command opts)}

      (seq missing)
      {:ok? false
       :reason :missing-options
       :missing missing}

      :else
      {:ok? true
       :session (dial-session opts)})))

(defn relay-session
  "Normalised relay process request for the native overlay driver."
  [opts]
  (cond-> {:type "murakumo.overlay.relay"
           :overlay (:overlay opts)
           :name (:name opts)
           :region (:region opts)
           :url (:url opts)
           :transports (->> (str/split (str (:transports opts)) #",")
                            (remove str/blank?)
                            vec)}
    (:auth-key opts) (assoc :auth-key (:auth-key opts))
    (:require-auth opts) (assoc :require-auth? (#{"true" "1" "yes"} (:require-auth opts)))
    (:max-frame-bytes opts) (assoc :max-frame-bytes
                                   #?(:clj (Long/parseLong (str (:max-frame-bytes opts)))
                                      :cljs (js/parseInt (:max-frame-bytes opts) 10)))))

(defn relay-result
  "Validate parsed relay options and return an executable relay result."
  [opts]
  (let [missing (missing-options required-relay-options opts)]
    (cond
      (seq missing)
      {:ok? false
       :reason :missing-options
       :missing missing}

      :else
      {:ok? true
       :session (relay-session opts)})))

(defn normalise-step-argv [driver argv]
  (if (= driver (first argv))
    (vec (rest argv))
    argv))

(defn step-result
  "Validate one bootstrap step's argv through the normal command validator."
  [driver step]
  (let [result (command-result (parse-argv (normalise-step-argv driver (:argv step))))]
    (assoc step
           :result result
           :ok? (boolean (:ok? result)))))

(defn bootstrap-session
  "Validate a cloud.murakumo.bootstrap manifest into ordered executable steps."
  [manifest]
  {:type "murakumo.overlay.bootstrap"
   :overlay (:overlay manifest)
   :driver (:driver manifest)
   :phases (mapv (fn [phase]
                   (assoc phase :steps (mapv #(step-result (:driver manifest) %)
                                              (:steps phase))))
                 (:phases manifest))})

(defn bootstrap-ok? [session]
  (every? (fn [phase] (every? :ok? (:steps phase)))
          (:phases session)))

(defn valid-bootstrap-manifest? [manifest]
  (and (map? manifest)
       (= "cloud.murakumo.bootstrap" (:$type manifest))
       (seq (:phases manifest))))

(defn bootstrap-result
  "Validate parsed bootstrap options and return an executable bootstrap result."
  [opts read-edn]
  (let [missing (missing-options required-bootstrap-options opts)]
    (cond
      (seq missing)
      {:ok? false
       :reason :missing-options
       :missing missing}

      :else
      (let [manifest (read-edn (:manifest-file opts))]
        (if-not (valid-bootstrap-manifest? manifest)
          {:ok? false
           :reason :invalid-manifest}
          (let [session (bootstrap-session manifest)]
            {:ok? (bootstrap-ok? session)
             :reason (if (bootstrap-ok? session) :ready :invalid-steps)
             :session session}))))))

(defn run-step
  "Dry-run execution decision for one already-validated bootstrap step."
  [phase-name step]
  {:phase (or (:phase step) phase-name)
   :phase-group phase-name
   :target (:target step)
   :action (if (:ok? step) :run :blocked)
   :reason (if (:ok? step) :ready (get-in step [:result :reason]))
   :argv (:argv step)
   :session (get-in step [:result :session])})

(defn runtime-kind [session]
  (case (:type session)
    "murakumo.overlay.relay" :relay-runtime
    "murakumo.overlay.session" (or (get-in session [:direct :kind]) :relay)
    :unknown))

(defn adapter-name [kind]
  (case kind
    :relay-runtime "murakumo.runtime.relay"
    :quic "murakumo.runtime.quic"
    :webrtc "murakumo.runtime.webrtc"
    :webtransport "murakumo.runtime.webtransport"
    :relay "murakumo.runtime.relay-client"
    "murakumo.runtime.unknown"))

(defn dispatch-step
  "Attach runtime adapter information to a run-plan step."
  [step]
  (let [kind (runtime-kind (:session step))]
    (assoc step
           :runtime kind
           :adapter (adapter-name kind))))

(defn dispatch-plan
  "Attach runtime adapter decisions to every run-plan step."
  [run-plan]
  (assoc run-plan
         :type "murakumo.overlay.dispatch-plan"
         :phases (mapv (fn [phase]
                         (assoc phase :steps (mapv dispatch-step (:steps phase))))
                       (:phases run-plan))))

(defn run-plan
  "Build an ordered dry-run plan from a validated bootstrap session."
  [bootstrap-session]
  {:type "murakumo.overlay.run-plan"
   :overlay (:overlay bootstrap-session)
   :driver (:driver bootstrap-session)
   :mode :dry-run
   :phases (mapv (fn [phase]
                   {:name (:name phase)
                    :steps (mapv #(run-step (:name phase) %) (:steps phase))})
                 (:phases bootstrap-session))})

(defn execute-step
  "Execute or skip one run-plan step with a caller-supplied executor."
  [execute-argv step]
  (if (= :run (:action step))
    (assoc step
           :execution (execute-argv step)
           :executed? true)
    (assoc step
           :execution {:ok? false :reason (:reason step)}
           :executed? false)))

(defn execute-plan
  "Execute a run-plan while preserving phase order.
   The executor is injected so tests and future socket runtimes share one contract."
  [run-plan execute-argv]
  (let [phases (mapv (fn [phase]
                       (assoc phase :steps (mapv #(execute-step execute-argv %)
                                                 (:steps phase))))
                     (:phases run-plan))]
    {:type "murakumo.overlay.execution-report"
     :overlay (:overlay run-plan)
     :driver (:driver run-plan)
     :mode :execute
     :phases phases
     :ok? (every? (fn [phase]
                    (every? #(get-in % [:execution :ok?]) (:steps phase)))
                  phases)}))

(defn run-result
  "Validate a bootstrap manifest and return the ordered dry-run runner plan."
  [opts read-edn]
  (let [bootstrap (bootstrap-result opts read-edn)]
    (if-not (:ok? bootstrap)
      bootstrap
      {:ok? true
       :reason :ready
       :session (run-plan (:session bootstrap))})))

(defn dispatch-result
  "Validate a bootstrap manifest and return runtime adapter dispatch decisions."
  [opts read-edn]
  (let [run (run-result opts read-edn)]
    (if-not (:ok? run)
      run
      {:ok? true
       :reason :ready
       :session (dispatch-plan (:session run))})))

(defn execute-result
  "Validate a bootstrap manifest and return an execution report."
  [opts read-edn execute-argv]
  (let [dispatch (dispatch-result opts read-edn)]
    (if-not (:ok? dispatch)
      dispatch
      (let [report (execute-plan (:session dispatch) execute-argv)]
        {:ok? (:ok? report)
         :reason (if (:ok? report) :executed :execution-failed)
         :session report}))))

(defn command-result
  ([opts] (command-result opts nil nil))
  ([opts read-edn] (command-result opts read-edn nil))
  ([opts read-edn execute-argv]
   (case (:command opts)
     :dial (dial-result opts)
     :relay (relay-result opts)
     :bootstrap (bootstrap-result opts read-edn)
     :run (run-result opts read-edn)
     :dispatch (dispatch-result opts read-edn)
     :execute (execute-result opts read-edn execute-argv)
     {:ok? false
      :reason :unknown-command
      :command (:command opts)})))

(defn result-lines [result]
  (if (:ok? result)
    [(pr-str (:session result))]
    [(str "murakumo-overlay error: " (name (:reason result)))
     (when-let [missing (seq (:missing result))]
       (str "  missing: " (str/join "," (map name missing))))]))

(defn command-lines
  ([args] (command-lines args nil nil))
  ([args read-edn] (command-lines args read-edn nil))
  ([args read-edn execute-argv]
   (-> args
       parse-argv
       (command-result read-edn execute-argv)
       result-lines
       (->> (remove nil?) vec))))
