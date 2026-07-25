;; murakumo.task.exec — nbb execution shell for the fleet task plane.
;;
;; The impure half of murakumo.task.plan: it turns a pure assignment
;; ({:task :node :host}) into a real remote process over Tailscale SSH, bounds
;; concurrency per node to that node's slot count, and times every task.
;;
;; Script host is nbb (ADR-2607173000: bb is retired, no new .mjs/.sh) — the
;; pure planner it drives stays .cljc so the same decisions also run on the JVM,
;; in the CF Worker, and inside a kotoba WASM component.

(ns murakumo.task.exec
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]
            [murakumo.task.plan :as plan]))

(defn ssh-argv
  "Canonical non-interactive SSH argv for a fleet node. BatchMode so an
   unreachable node fails fast instead of prompting; accept-new so a freshly
   reflashed node doesn't wedge the batch on a host-key prompt."
  [host cmd connect-timeout-s]
  ["ssh" "-o" "BatchMode=yes"
   "-o" (str "ConnectTimeout=" connect-timeout-s)
   "-o" "StrictHostKeyChecking=accept-new"
   host cmd])

(def rc-marker "__murakumo_rc=")

(defn wrap-cmd
  "Wrap a remote command so it reports its OWN exit status in-band.

   MEASURED 2026-07-25: Tailscale SSH on the macOS fleet nodes does NOT
   propagate the remote exit status — `ssh asher 'exit 7'` and `ssh asher false`
   both return 0, while the same commands on the Linux node (gad) correctly
   return 7 and 1. Trusting ssh's exit code would make this task plane report
   silent false successes on 10 of 11 nodes. So the command runs in a subshell
   (so a bare `exit N` inside it doesn't kill the reporting shell) and the real
   status is echoed as a sentinel line that `run-one` parses and strips."
  [cmd]
  (str "( " cmd "\n); __mrc=$?; echo \"" rc-marker "$__mrc\""))

(defn parse-rc
  "Split captured stdout into [clean-stdout rc-or-nil]. rc is nil when the
   sentinel never arrived (connection failure, kill, non-shell remote)."
  [out]
  (let [lines (str/split-lines (str out))
        rc-line (last (filter #(str/starts-with? (str/trim %) rc-marker) lines))
        rc (when rc-line
             (let [v (js/parseInt (str/replace (str/trim rc-line) rc-marker "") 10)]
               (when-not (js/isNaN v) v)))]
    [(str/trim (str/join "\n" (remove #(str/starts-with? (str/trim %) rc-marker) lines)))
     rc]))

(defn run-one
  "Execute one assignment remotely. Returns a Promise of a result map
   {:task :node :host :exit :timeout? :stdout :stderr :started-ms :duration-ms}.
   Never rejects — a spawn error or timeout is reported as data, because a
   partially-failed fan-out is a normal outcome the planner must be able to fold."
  [{:keys [task node host]} opts]
  (js/Promise.
   (fn [resolve _reject]
     (let [t0 (js/Date.now)
           timeout-ms (or (:timeout-ms task) (:timeout-ms opts) 120000)
           argv (ssh-argv host (wrap-cmd (:cmd task)) (or (:connect-timeout-s opts) 8))
           child (.spawn cp (first argv) (to-array (rest argv))
                         #js {:stdio #js ["ignore" "pipe" "pipe"]})
           out (atom "")
           err (atom "")
           state (atom nil)
           finish (fn [m]
                    (let [[clean rc] (parse-rc @out)]
                      (resolve (merge {:task task :node node :host host
                                       :stdout clean
                                       :remote-rc rc
                                       :stderr (str/trim @err)
                                       :started-ms t0
                                       :duration-ms (- (js/Date.now) t0)}
                                      m))))
           timer (js/setTimeout (fn []
                                  (when (nil? @state)
                                    (reset! state :timeout)
                                    (.kill child "SIGKILL")))
                                timeout-ms)]
       (.on (.-stdout child) "data" (fn [d] (swap! out str (.toString d))))
       (.on (.-stderr child) "data" (fn [d] (swap! err str (.toString d))))
       (.on child "error"
            (fn [e]
              (js/clearTimeout timer)
              (when (nil? @state)
                (reset! state :error)
                (finish {:exit nil :timeout? false :error (str (.-message e))}))))
       (.on child "close"
            (fn [code]
              (js/clearTimeout timer)
              (when (not= :error @state)
                ;; The in-band sentinel wins when present: ssh's own exit code
                ;; is unreliable on this fleet (see wrap-cmd).
                (let [[_ rc] (parse-rc @out)]
                  (finish {:exit (when-not (= :timeout @state) (or rc code))
                           :ssh-exit code
                           :timeout? (= :timeout @state)})))))))))

(defn- take-next!
  "Pop the next assignment off a node's queue. Safe without locking: nbb is
   single-threaded, so the read and the swap! happen in one turn of the loop."
  [state]
  (let [[a] (:queue @state)]
    (when a
      (swap! state update :queue #(vec (rest %)))
      a)))

(defn- worker
  "One concurrency slot on one node: drain that node's queue serially."
  [state opts]
  (if-let [a (take-next! state)]
    (.then (run-one a opts)
           (fn [r]
             (swap! state update :results conj r)
             (when-let [cb (:on-result opts)] (cb r))
             (worker state opts)))
    (js/Promise.resolve nil)))

(defn run-node-queue
  "Run one node's assignments with at most `slot-count` concurrent SSH sessions."
  [assignments slot-count opts]
  (let [state (atom {:queue (vec assignments) :results []})
        n (max 1 (min slot-count (count assignments)))]
    (-> (js/Promise.all (to-array (mapv (fn [_] (worker state opts)) (range n))))
        (.then (fn [_] (:results @state))))))

(defn run-plan
  "Execute a whole plan: every node drains its own queue concurrently, each
   bounded by its slot count. Returns a Promise of the result vector."
  [plan nodes opts]
  (let [by-name (into {} (map (juxt :name identity)) nodes)
        groups (group-by :node (:assignments plan))
        ps (mapv (fn [[node-name as]]
                   (run-node-queue as (plan/slots (get by-name node-name) opts) opts))
                 groups)]
    (if (empty? ps)
      (js/Promise.resolve [])
      (-> (js/Promise.all (to-array ps))
          (.then (fn [rss] (vec (apply concat (array-seq rss)))))))))

;; --- node probing -----------------------------------------------------------

(def probe-cmd
  "Portable across macOS (sysctl) and Linux (nproc//proc/meminfo): prints
   `<cores> <mem-bytes> <load1> <hostname>` on one line."
  (str "cores=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 1); "
       "mem=$(sysctl -n hw.memsize 2>/dev/null || awk '/MemTotal/{print $2*1024}' /proc/meminfo 2>/dev/null || echo 0); "
       "load=$(uptime | sed 's/.*averages*: //' | awk '{print $1}' | tr -d ','); "
       "echo \"$cores $mem $load $(hostname)\""))

(defn- parse-probe [s]
  (let [[cores mem load host] (str/split (str/trim (str s)) #"\s+")
        n (fn [x] (let [v (js/parseFloat x)] (when-not (js/isNaN v) v)))]
    ;; NB: `int` is a 32-bit bit-or in ClojureScript, which silently mangles
    ;; byte counts (17179869184 | 0 = 0). Round instead.
    {:cores (some-> (n cores) js/Math.round)
     :mem-bytes (some-> (n mem) js/Math.round)
     :load1 (n load)
     :hostname host}))

(defn probe
  "SSH every node once to learn cores / memory / load / hostname. Returns a
   Promise of nodes enriched with those keys plus :online? (false when the
   probe failed — the planner then refuses to place work there)."
  [nodes opts]
  (let [ps (mapv (fn [node]
                   (-> (run-one {:task {:cmd probe-cmd :id (str "probe-" (:name node))}
                                 :node (:name node) :host (:host node)}
                                (assoc opts :timeout-ms (or (:probe-timeout-ms opts) 15000)))
                       (.then (fn [r]
                                (if (and (= 0 (:exit r)) (seq (:stdout r)))
                                  (merge node (parse-probe (:stdout r))
                                         {:online? true :probe-ms (:duration-ms r)})
                                  (merge node {:online? false
                                               :probe-ms (:duration-ms r)
                                               :probe-error (or (:error r)
                                                                (when (:timeout? r) "timeout")
                                                                (:stderr r)
                                                                (str "exit " (:exit r)))}))))))
                 nodes)]
    (-> (js/Promise.all (to-array ps))
        (.then (fn [rs] (vec (array-seq rs)))))))
