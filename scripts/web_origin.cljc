;; murakumo web plane origin — the stateful half of web.murakumo.cloud (ADR-260929).
;;
;;   MURAKUMO_WEB_ORIGIN_HOME=~/.murakumo-web-origin nbb -cp <bundle> <bundle>/web_origin.cljs
;;
;; The Worker at web.murakumo.cloud verifies the caller's token (scope `web`) and
;; forwards over the gad Tunnel (Workers VPC, no public origin hostname) with
;; `Authorization: Bearer <origin token>` and `X-Murakumo-Sub: <token subject>`.
;; This process holds the queue and the per-subject quota on the fleet, and runs
;; one job at a time on the nodes in origin.edn through `web_node.cljs`.
;;
;; Listens on 127.0.0.1 only. State under $MURAKUMO_WEB_ORIGIN_HOME:
;;   origin-token   the shared secret with the Worker (0600, refused otherwise)
;;   origin.edn     {:port 8095 :nodes [...] :quota {...}}  (operator config)
;;   jobs/<id>.edn  one record per job      quota.edn  today's per-subject use
;;
;; A node runs public jobs only when its entry says :public-egress? true — the
;; operator's opt-in for that node's internet egress (most fleet nodes exit from a
;; home line). No node is opted in by default.
(ns web-origin
  (:require [cljs.reader :as reader]
            [murakumo.web.origin :as o]))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def os (js/require "os"))
(def http (js/require "http"))
(def crypto (js/require "crypto"))
(def cp (js/require "child_process"))

(def max-body-bytes (* 64 1024))
(def max-markdown-chars (* 1024 1024))
(def max-links 200)

(defn home [] (or (.. js/process -env -MURAKUMO_WEB_ORIGIN_HOME) (.join path (.homedir os) ".murakumo-web-origin")))
(defn- f [& parts] (apply (.-join path) (home) parts))
(defn- expand [p] (if (and (string? p) (.startsWith p "~/")) (.join path (.homedir os) (subs p 2)) p))

(defn- read-edn-file [file default]
  (try (if (.existsSync fs file) (reader/read-string (.readFileSync fs file "utf8")) default)
       (catch :default _ default)))

(defn- write-atomic! [file s]
  (let [tmp (str file ".tmp-" (.toString (.randomBytes crypto 6) "hex"))]
    (.writeFileSync fs tmp s #js {:mode 384})
    (.renameSync fs tmp file)))

(defn- mode-ok? [file] (zero? (bit-and (.-mode (.statSync fs file)) 63)))

(defn config [] (read-edn-file (f "origin.edn") {}))

(defn origin-token
  "The shared secret, or nil when it is absent or readable by group/other (then every request is refused)."
  []
  (let [file (f "origin-token")]
    (when (and (.existsSync fs file) (mode-ok? file))
      (let [t (.trim (.readFileSync fs file "utf8"))] (when (>= (count t) 32) t)))))

(defn- token-ok? [header]
  (let [want (origin-token)
        got (when (and (string? header) (.startsWith header "Bearer ")) (subs header 7))]
    (boolean (and want got
                  (let [a (js/Buffer.from got) b (js/Buffer.from want)]
                    (and (= (.-length a) (.-length b)) (.timingSafeEqual crypto a b)))))))

;; --- records ----------------------------------------------------------------

(defn- job-file [id] (f "jobs" (str id ".edn")))
(defn- save! [record] (write-atomic! (job-file (:id record)) (pr-str record)) record)
(defn- load-record [id] (when (o/valid-id? id) (read-edn-file (job-file id) nil)))

(defn- all-records []
  (let [dir (f "jobs")]
    (if (.existsSync fs dir)
      (keep (fn [name] (when (.endsWith name ".edn") (read-edn-file (.join path dir name) nil)))
            (js->clj (.readdirSync fs dir)))
      [])))

(def queue (atom []))          ; ids, FIFO
(def running (atom nil))

;; --- running a node op --------------------------------------------------------

(defn- node-argv [{:keys [transport host web-home bundle]}]
  (let [web-home (or web-home "~/.murakumo-web")
        bundle (or bundle (str web-home "/bundle"))]
    (case transport
      :local ["nbb" ["-cp" (expand bundle) (str (expand bundle) "/web_node.cljs")]
              {"MURAKUMO_WEB_HOME" (expand web-home)}]
      :ssh (when (and (string? host) (re-matches #"[A-Za-z0-9._@-]+" host) (not= \- (first host)))
             ["ssh" ["-o" "BatchMode=yes" "-o" "ConnectTimeout=8" "--" host
                     (str "MURAKUMO_WEB_HOME=\"$HOME/.murakumo-web\" nbb -cp \"$HOME/.murakumo-web/bundle\" \"$HOME/.murakumo-web/bundle/web_node.cljs\"")]
              {}])
      nil)))

(defn run-op!
  "Run one node op asynchronously. Calls `done` with the node's EDN reply or {:refused ...}."
  [node op job timeout-ms done]
  (if-let [[cmd args extra-env] (node-argv node)]
    (let [env (js/Object.assign #js {} (.-env js/process) (clj->js extra-env))
          child (.spawn cp cmd (clj->js args) #js {:env env :stdio #js ["pipe" "pipe" "pipe"]})
          out (atom []) err (atom [])
          finished (atom false)
          finish! (fn [r] (when (compare-and-set! finished false true) (done r)))
          timer (js/setTimeout (fn [] (.kill child "SIGKILL") (finish! {:refused :origin/node-timeout})) timeout-ms)]
      (.on (.-stdout child) "data" #(swap! out conj %))
      (.on (.-stderr child) "data" #(when (< (count @err) 20) (swap! err conj %)))
      (.on child "error" (fn [e] (js/clearTimeout timer) (finish! {:refused :origin/spawn-failed :detail (.-message e)})))
      (.on child "close"
           (fn [code]
             (js/clearTimeout timer)
             (if-not (zero? code)
               (finish! {:refused :origin/node-failed :detail {:exit code}})
               (let [s (.toString (js/Buffer.concat (clj->js @out)) "utf8")
                     line (last (remove empty? (.split s "\n")))]
                 (finish! (try (reader/read-string line) (catch :default _ {:refused :origin/bad-node-reply})))))))
      (.end (.-stdin child) (pr-str {:op op :job job})))
    (done {:refused :origin/bad-node-config})))

(defn- public-node []
  (first (filter :public-egress? (:nodes (config)))))

(defn- get-text [node cid timeout k]
  (run-op! node :get {:cid cid} timeout
           (fn [r] (k (when-let [b64 (get-in r [:ok :base64])]
                        (.toString (js/Buffer.from b64 "base64") "utf8"))))))

(defn- receipt-pair [r] {:receipt (get-in r [:ok :receipt]) :signature (get-in r [:ok :signature])})

(defn execute!
  "Run `spec` on `node`; calls (k {:ok result}) or (k {:refused reason :detail ...})."
  [node id spec k]
  (case (:kind spec)
    :scrape
    (run-op! node :fetch {:job-id id :url (:url spec)} 120000
             (fn [fr]
               (if-not (:ok fr)
                 (k fr)
                 (let [ev (get-in fr [:ok :receipt :evidence])
                       fetch-cid (get-in fr [:ok :output-cid])]
                   (run-op! node :extract {:job-id id :fetch-cid fetch-cid :format :edn} 60000
                            (fn [er]
                              (if-not (:ok er)
                                (k er)
                                (get-text node (get-in er [:ok :output-cid]) 60000
                                          (fn [s]
                                            (let [doc (try (reader/read-string s) (catch :default _ nil))
                                                  md (str (:markdown doc))]
                                              (k {:ok {:url (:url spec)
                                                       :final_url (:final-url ev)
                                                       :http_status (:status ev)
                                                       :content_type (:content-type ev)
                                                       :title (:title doc)
                                                       :markdown (subs md 0 (min (count md) max-markdown-chars))
                                                       :markdown_truncated (> (count md) max-markdown-chars)
                                                       :links (vec (take max-links (:links doc)))
                                                       :fetch_cid fetch-cid
                                                       :extract_cid (get-in er [:ok :output-cid])
                                                       :untrusted true
                                                       :receipts [(receipt-pair fr) (receipt-pair er)]}})))))))))))

    :crawl
    (run-op! node :crawl {:job-id id :plan (:plan spec)} 900000
             (fn [r]
               (if-not (:ok r)
                 (k r)
                 (let [m (get-in r [:ok :manifest])]
                   (k {:ok {:manifest_cid (get-in r [:ok :manifest-cid])
                            :pages (mapv #(select-keys % [:url :final-url :status :depth :cid]) (:pages m))
                            :skipped (count (:skipped m))
                            :stats (:stats m)
                            :untrusted true
                            :receipts [(receipt-pair r)]}})))))

    :search
    (run-op! node :search {:job-id id :query (:query spec) :k (:k spec)} 120000
             (fn [r]
               (if-not (:ok r)
                 (k r)
                 (let [doc (get-in r [:ok :results])]
                   (k {:ok {:query (:query doc)
                            :results (:results doc)
                            :backends (:backends doc)
                            :results_cid (get-in r [:ok :results-cid])
                            :untrusted true
                            :receipts [(receipt-pair r)]}})))))

    (k {:refused :origin/unknown-kind})))

(defn- pump!
  "Start the next queued job if nothing is running."
  []
  (when (and (nil? @running) (seq @queue))
    (let [id (first @queue)]
      (swap! queue (comp vec rest))
      (if-let [rec (load-record id)]
        (if-let [node (public-node)]
          (do (reset! running id)
              (save! (o/start rec (js/Date.now)))
              (execute! node id (:spec rec)
                        (fn [r]
                          (let [rec (load-record id)]
                            (save! (if (:ok r)
                                     (o/finish rec (:ok r) (js/Date.now))
                                     (o/fail rec (:refused r) (:detail r) (js/Date.now)))))
                          (reset! running nil)
                          (pump!))))
          (do (save! (o/fail rec :origin/no-public-node nil (js/Date.now))) (pump!)))
        (pump!)))))

;; --- HTTP ---------------------------------------------------------------------

(defn- reply [^js res status body]
  (.writeHead res status #js {"content-type" "application/json; charset=utf-8" "cache-control" "no-store"})
  (.end res (js/JSON.stringify (clj->js body))))

(defn- read-body [^js req k]
  (let [chunks (atom []) size (atom 0) over (atom false)]
    (.on req "data" (fn [c] (swap! size + (.-length c))
                      (if (> @size max-body-bytes) (reset! over true) (swap! chunks conj c))))
    (.on req "end" (fn [] (k (if @over ::too-large (.toString (js/Buffer.concat (clj->js @chunks)) "utf8")))))))

(defn- create! [sub body-str res]
  (let [body (try (js->clj (js/JSON.parse body-str)) (catch :default _ ::bad))
        parsed (if (= ::bad body) {:refused [:request/bad-json]} (o/parse-request body))]
    (cond
      (:refused parsed) (reply res 400 {:error "invalid_request" :reasons (mapv #(subs (str %) 1) (:refused parsed))})
      (>= (+ (count @queue) (if @running 1 0)) (:max-queue o/public-limits)) (reply res 503 {:error "queue_full"})
      (nil? (public-node)) (reply res 503 {:error "no_public_node"})
      :else
      (let [now (js/Date.now)
            q (o/charge-quota (read-edn-file (f "quota.edn") {}) sub (get-in parsed [:ok :kind]) now
                              (merge o/default-quota (:quota (config))))]
        (if (:refused q)
          (reply res 429 {:error (subs (str (:refused q)) 1)})
          (let [id (.toString (.randomBytes crypto 16) "hex")
                rec (o/new-record id sub (:ok parsed) now)]
            (write-atomic! (f "quota.edn") (pr-str (:ok q)))
            (save! rec)
            (swap! queue conj id)
            (pump!)
            (reply res 202 (o/public-view (or (load-record id) rec)))))))))

(defn handler [^js req ^js res]
  (let [url (js/URL. (.-url req) "http://origin.invalid")
        p (.-pathname url)
        sub (some-> (aget (.-headers req) "x-murakumo-sub") str .trim)]
    (cond
      (not (token-ok? (aget (.-headers req) "authorization"))) (reply res 401 {:error "unauthorized"})
      (= p "/health")
      (reply res 200 {:ok true :queued (count @queue) :running (some? @running)
                      :public_nodes (count (filter :public-egress? (:nodes (config))))})
      (not (and (string? sub) (<= 1 (count sub) 256))) (reply res 400 {:error "no_subject"})
      (and (= p "/v1/web/jobs") (= "POST" (.-method req)))
      (read-body req (fn [b] (if (= b ::too-large) (reply res 413 {:error "too_large"}) (create! sub b res))))
      (and (.startsWith p "/v1/web/jobs/") (= "GET" (.-method req)))
      (let [rec (load-record (subs p (count "/v1/web/jobs/")))]
        (if (and rec (o/owner? rec sub)) (reply res 200 (o/public-view rec)) (reply res 404 {:error "not_found"})))
      :else (reply res 404 {:error "not_found"}))))

(defn main []
  (.mkdirSync fs (f "jobs") #js {:recursive true :mode 448})
  (when-not (origin-token)
    (js/console.error "origin-token missing, shorter than 32 chars, or not 0600: every request will be refused"))
  (let [now (js/Date.now)
        recs (sort-by :created-ms (all-records))]
    (doseq [r recs] (let [r2 (o/recover r now)] (when (not= r r2) (save! r2))))
    (reset! queue (vec (keep #(when (= :queued (:status %)) (:id %)) recs))))
  (let [port (or (:port (config)) 8095)
        server (.createServer http handler)]
    (.listen server port "127.0.0.1" (fn [] (js/console.log (str "web-origin listening on 127.0.0.1:" port))))
    (pump!)))

(when-not (.. js/process -env -WEB_ORIGIN_NO_MAIN) (main))
