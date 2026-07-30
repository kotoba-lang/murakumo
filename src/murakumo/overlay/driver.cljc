;; murakumo.overlay.driver — portable native overlay driver planning.
;;
;; This is the first executable boundary behind `murakumo.cloud connect`: it
;; validates a canonical `dial` argv and emits the session record a real stream or
;; packet driver will later use to open QUIC/WebRTC/WebTransport/relay paths.
;;
;; W6 product-shell authority (ADR-260728-w6-overlay-driver-tokens-pure-oracle):
;; endpoint-kind / option-name / dial-ok-reason / blank? / command-is-dial?
;; + scheme/kind/cmd/reason tokens DELEGATE to precompiled
;; kotoba/overlay_driver_core when oracle is loadable
;; (JVM classpath or cljs/nbb — ADR-260728-w6-cljs-oracle-load).
;; Host remains: parse-argv loops + session maps. cljs mirrors as fallback.

(ns murakumo.overlay.driver
  (:require [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :overlay-driver)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "JVM: require shipped KIR (T6.4). cljs: oracle when ready, else mirror."
  [thunk mirror-thunk]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid})))
       (thunk))
     :cljs
     (if (oracle-ready?)
       (try
         (thunk)
         (catch :default _
           (mirror-thunk)))
       (mirror-thunk))))

(defn- oracle-str-const [export mirror]
  "JVM: require oracle. cljs: mirror fallback."
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export []))
     :cljs
     (try
       (if (oracle-ready?)
         (oracle/call oid export [])
         mirror)
       (catch :default _
         mirror))))

;; ── host-mirror pure helpers + dual-source tokens ────────────────────

(def ^:private mirror-scheme-quic "quic://")
(def ^:private mirror-scheme-webrtc "webrtc://")
(def ^:private mirror-scheme-https "https://")
(def ^:private mirror-scheme-relay "relay://")
(def ^:private mirror-kind-quic "quic")
(def ^:private mirror-kind-webrtc "webrtc")
(def ^:private mirror-kind-webtransport "webtransport")
(def ^:private mirror-kind-relay "relay")
(def ^:private mirror-kind-unknown "unknown")
(def ^:private mirror-flag-dash-prefix "--")
(def ^:private mirror-cmd-dial "dial")
(def ^:private mirror-reason-ok "ok")
(def ^:private mirror-reason-unknown-command "unknown-command")
(def ^:private mirror-reason-missing-options "missing-options")

(def scheme-quic
  (oracle-str-const 'scheme-quic mirror-scheme-quic))
(def scheme-webrtc
  (oracle-str-const 'scheme-webrtc mirror-scheme-webrtc))
(def scheme-https
  (oracle-str-const 'scheme-https mirror-scheme-https))
(def scheme-relay
  (oracle-str-const 'scheme-relay mirror-scheme-relay))
(def kind-quic
  (oracle-str-const 'kind-quic mirror-kind-quic))
(def kind-webrtc
  (oracle-str-const 'kind-webrtc mirror-kind-webrtc))
(def kind-webtransport
  (oracle-str-const 'kind-webtransport mirror-kind-webtransport))
(def kind-relay
  (oracle-str-const 'kind-relay mirror-kind-relay))
(def kind-unknown
  (oracle-str-const 'kind-unknown mirror-kind-unknown))
(def flag-dash-prefix
  (oracle-str-const 'flag-dash-prefix mirror-flag-dash-prefix))
(def cmd-dial
  (oracle-str-const 'cmd-dial mirror-cmd-dial))
(def reason-ok
  (oracle-str-const 'reason-ok mirror-reason-ok))
(def reason-unknown-command
  (oracle-str-const 'reason-unknown-command mirror-reason-unknown-command))
(def reason-missing-options
  (oracle-str-const 'reason-missing-options mirror-reason-missing-options))

(defn- mirror-option-name [flag]
  (let [s (str flag)]
    (if (str/starts-with? s flag-dash-prefix)
      (subs s (count flag-dash-prefix))
      s)))

(defn- mirror-blank? [s]
  (str/blank? (str s)))

(defn- mirror-endpoint-kind [endpoint]
  (let [endpoint (str endpoint)]
    (cond
      (str/starts-with? endpoint scheme-quic) kind-quic
      (str/starts-with? endpoint scheme-webrtc) kind-webrtc
      (str/starts-with? endpoint scheme-https) kind-webtransport
      (str/starts-with? endpoint scheme-relay) kind-relay
      :else kind-unknown)))

(defn- mirror-command-is-dial? [command]
  (= cmd-dial (str command)))

(defn- mirror-dial-ok-reason [is-dial missing-count]
  (cond
    (not is-dial) reason-unknown-command
    (pos? missing-count) reason-missing-options
    :else reason-ok))

(def required-dial-options
  [:overlay :node :name :from :to :capability :direct :transport])

(def required-relay-options
  [:overlay :name :region :url :transports])

(def required-bootstrap-options
  [:manifest-file])

(declare command-result)

(defn keyword-option
  "Strip leading `--` from a flag and keywordize.
   Kotoba `option-name` when oracle ready."
  [flag]
  (keyword
   (try-oracle
    #(o 'option-name [(str flag)])
    #(mirror-option-name flag))))

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
            (and (str/starts-with? (str flag) flag-dash-prefix)
                 (str/includes? (str flag) "="))
            (let [[option inline-value] (split-option flag)]
              (recur (assoc opts option inline-value)
                     (if value (cons value more) more)))

            (and (str/starts-with? (str flag) flag-dash-prefix) value)
            (recur (assoc opts (keyword-option flag) value) more)

            :else
            (recur (update opts :extra (fnil conj []) flag) (cons value more))))))))

(defn missing-options
  "Options whose string form is blank.
   Kotoba `blank?` when ready (empty string only); mirror uses str/blank?."
  [required opts]
  (filterv (fn [k]
             (let [v (str (get opts k))]
               (try-oracle
                #(oracle/bool->host (o 'blank? [v]))
                #(mirror-blank? v))))
           required))

(defn endpoint-kind
  "Classify a dial endpoint scheme.
   Kotoba `endpoint-kind` → keyword (quic|webrtc|webtransport|relay|unknown)."
  [endpoint]
  (keyword
   (try-oracle
    #(o 'endpoint-kind [(str endpoint)])
    #(mirror-endpoint-kind endpoint))))

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
  "Validate parsed driver options and return an executable driver result.
   Reason via kotoba `dial-ok-reason` with host-projected flags when ready."
  [opts]
  (let [missing (missing-options required-dial-options opts)
        cmd-name (name (or (:command opts) :unknown))
        is-dial (try-oracle
                 #(oracle/bool->host (o 'command-is-dial? [cmd-name]))
                 #(mirror-command-is-dial? cmd-name))
        reason (keyword
                (try-oracle
                 #(o 'dial-ok-reason
                     [(boolean is-dial) (oracle/as-i64 (count missing))])
                 #(mirror-dial-ok-reason is-dial (count missing))))]
    (case reason
      :unknown-command
      {:ok? false
       :reason :unknown-command
       :command (:command opts)}

      :missing-options
      {:ok? false
       :reason :missing-options
       :missing missing}

      ;; :ok
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
