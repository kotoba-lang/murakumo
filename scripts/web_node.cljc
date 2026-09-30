;; murakumo web node — one request per process (ADR-260929).
;;
;;   echo '{:op :whoami}' | nbb -cp <bundle> web_node.cljs
;;
;; stdin: one EDN map {:op kw :job map}; stdout: exactly one EDN line.
;; State lives under $MURAKUMO_WEB_HOME (default ~/.murakumo-web): node.key
;; (0600), store/ (content-addressed), zone.edn, backends.edn (operator config).
;; No daemon, no listening socket: the coordinator reaches a node over its
;; existing SSH path, so a node exposes nothing new to the network.
(ns web-node
  (:require [cljs.reader :as reader]
            [murakumo.web :as web]
            [murakumo.web.crawl :as crawl]
            [murakumo.web.host :as host]
            [murakumo.web.index :as index]
            [murakumo.web.provision :as provision]
            [murakumo.web.search :as search]
            [murakumo.web.worker :as w]))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def os (js/require "os"))

(def version "web-node/1")
(def max-stdin-bytes (* 1024 1024))
(def max-get-bytes (* 8 1024 1024))
(def min-free-bytes (* 512 1024 1024))

(defn home [] (or (.. js/process -env -MURAKUMO_WEB_HOME) (.join path (.homedir os) ".murakumo-web")))

(defn- read-edn-file
  "nil when the file is absent OR unreadable: a bad config file must never break every request
  (make-env runs before any op, including the :configure that would repair it)."
  [f]
  (try (when (.existsSync fs f) (reader/read-string (.readFileSync fs f "utf8")))
       (catch :default _ nil)))

(defn- unreadable-config-files []
  (vec (for [name ["backends.edn" "zone.edn" "yataverse.authn.edn" "yataverse.auth-cmd"]
             :let [f (.join path (home) name)]
             :when (and (.existsSync fs f)
                        (try (reader/read-string (.readFileSync fs f "utf8")) false
                             (catch :default _ true)))]
         name)))

(defn- read-stdin []
  (let [buf (.readFileSync fs 0)]
    (when (> (.-length buf) max-stdin-bytes) (throw (ex-info "request too large" {})))
    (reader/read-string (.toString buf "utf8"))))

(defn- sleep! [ms]
  (.wait js/Atomics (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 ms))

(defn- identity-info []
  (let [seed (host/load-or-create-seed! (.join path (home) "node.key"))
        {:keys [public-key-hex]} (host/signer seed)]
    {:seed seed :public-key-hex public-key-hex :node-did (host/did-key public-key-hex)}))

(defn- yataverse-cli-config
  "What `yataverse bootstrap` left on THIS machine under ~/.config/yataverse: the node's own
  service-account secret (mode 0600, else ignored) and its tenant/storage. This is the
  decentralized path — every node holds its own identity and its own secret; nothing is
  copied from a central place."
  []
  (let [dir (.join path (.homedir os) ".config" "yataverse")
        sa-file (.join path dir "sa-token")
        cfg-file (.join path dir "config.json")]
    (when (and (.existsSync fs sa-file) (.existsSync fs cfg-file)
               (zero? (bit-and (.-mode (.lstatSync fs sa-file)) 63)))
      (let [c (js->clj (js/JSON.parse (.readFileSync fs cfg-file "utf8")) :keywordize-keys true)]
        (when (:tenantId c)
          {:sa (.trim (.readFileSync fs sa-file "utf8")) :tenant-id (:tenantId c)
           :storage (:storage c) :url (:url c)})))))

(defn- remote-put-config
  "Optional yataverse put (the bytes plane formerly named kotobase). Files under the node home
  (ssh is a non-login shell, so env vars are unreliable); the old kotobase.* names are still
  read as a fallback:
    yataverse.url        gateway base URL
    yataverse.prefix     optional path prefix, default /ipfs/
    yataverse.auth-cmd   EDN argv vector of the operator's MINTER, run per write
    yataverse.auth       static value, only for a gateway that accepts one (0600)
    yataverse.authn.edn  an EDN map with :tenant-id (t_...), :storage, :permissions, plus
    yataverse.sa-token   a tenant service-account secret (kb_sa_..., 0600): exchanged at
                         auth.kotoba.cloud for a 15-minute Biscuit (cached until 60 s before expiry)
  If none of the node-home files carry an authorization, the node's OWN yataverse CLI config
  (~/.config/yataverse, written by `yataverse bootstrap` on this node) is used.
  Absent url or any authorization -> local store only."
  []
  (let [rd (fn [base] (let [try-file (fn [n] (let [p (.join path (home) n)]
                                               (when (.existsSync fs p) (.trim (.readFileSync fs p "utf8")))))]
                        (or (try-file (str "yataverse." base)) (try-file (str "kotobase." base)))))
        env (fn [a b] (or (aget (.-env js/process) a) (aget (.-env js/process) b)))
        own (yataverse-cli-config)
        url (or (env "MURAKUMO_YATAVERSE_URL" "MURAKUMO_KOTOBASE_URL") (rd "url")
                (when own (or (:url own) "https://kotobase.net")))
        prefix (rd "prefix")
        cmd (some-> (rd "auth-cmd") reader/read-string)
        static (or (env "MURAKUMO_YATAVERSE_AUTH" "MURAKUMO_KOTOBASE_AUTH") (rd "auth"))
        authn (or (some-> (rd "authn.edn") reader/read-string)
                  (when own {:tenant-id (:tenant-id own) :storage (:storage own)}))
        sa (or (rd "sa-token") (:sa own))
        biscuit? (and (map? authn) (seq sa))
        cache (atom nil)
        biscuit-fn (fn []
                     (let [{:keys [value at]} @cache]
                       (if (and value (< (- (js/Date.now) at) (* 14 60 1000)))
                         value
                         (when-let [v (host/biscuit-from-service-account (assoc authn :sa-token sa))]
                           (reset! cache {:value v :at (js/Date.now)})
                           v))))]
    (when (and (seq url) (or cmd biscuit? (seq static)))
      (cond-> {:base-url url}
        (seq prefix) (assoc :path-prefix prefix)
        cmd (assoc :authorization-fn #(host/auth-from-command cmd))
        (and (not cmd) biscuit?) (assoc :authorization-fn biscuit-fn)
        (and (not cmd) (not biscuit?) (seq static)) (assoc :authorization static)))))

(defn- pending-file [] (.join path (home) "pending-put.txt"))

(defn- make-env [{:keys [seed node-did public-key-hex]}]
  (let [zone (:zone (read-edn-file (.join path (home) "zone.edn")))
        backends (some->> (read-edn-file (.join path (home) "backends.edn"))
                          (mapv host/backend-from-config))
        base (host/env {:store-dir (.join path (home) "store") :seed-hex seed :node-did node-did})
        tee (host/tee-store (.join path (home) "store") (pending-file) (remote-put-config))]
    (-> base
        (merge tee)
        (assoc :sleep! sleep! :backends backends :zone zone
               ;; true only for receipts THIS node signed (index-crawl! trusts nothing else)
               :receipt-valid? (fn [r sig]
                                 (and (= (:node-did r) node-did)
                                      (boolean (host/verify? public-key-hex (web/receipt-signing-string r) sig))))))))

(defn- b64 [bs] (.toString (js/Buffer.from bs) "base64"))

(defn health [ident env]
  (let [dir (.join path (home) "store")
        usage (host/store-usage dir)
        free (host/free-bytes (home))
        curl? (zero? (.-status (.spawnSync (js/require "child_process") "curl" #js ["--version"])))
        problems (cond-> []
                   (not curl?) (conj :curl/missing)
                   (nil? free) (conj :disk/unmeasured)
                   (and free (< free min-free-bytes)) (conj :disk/low)
                   (seq (unreadable-config-files)) (conj :config/unreadable)
                   (empty? (:backends env)) (conj :search/no-backends-configured)
                   (not (remote-put-config)) (conj :put/no-remote-configured))
        pending (if (.existsSync fs (pending-file))
                  (count (remove empty? (.split (.readFileSync fs (pending-file) "utf8") "\n")))
                  0)
        problems (cond-> problems (pos? pending) (conj :put/pending))]
    {:ok {:version version :node-did (:node-did ident) :zone (:zone env)
          :store usage :free-bytes free :pending-puts pending :problems problems
          ;; no backends is a config note, not an outage for fetch/crawl
          :healthy? (empty? (remove #{:search/no-backends-configured :put/no-remote-configured} problems))}}))

(defn handle [{:keys [op job]}]
  (let [ident (identity-info)
        env (make-env ident)]
    (case op
      :whoami {:ok {:version version :node-did (:node-did ident)
                    :public-key-hex (:public-key-hex ident) :zone (:zone env)}}
      :health (health ident env)
      ;; the address this node's traffic actually exits from (zone declarations are
      ;; checked against this, not asserted); asks a third-party echo service
      :egress (let [r (.spawnSync (js/require "child_process") "curl"
                                  #js ["-sS" "-g" "--noproxy" "*" "-m" "8" "https://ifconfig.me/ip"]
                                  #js {:encoding "utf8"})
                    ip (some-> (.-stdout r) .trim)]
                (if (and ip (re-matches #"[0-9a-fA-F:.]{3,45}" ip))
                  {:ok {:ip ip}}
                  {:refused :egress/unavailable}))
      ;; exercises host primitives that need no network; run under STOCK nbb by
      ;; scripts/web-test-all.sh, because the kbb tests do not run on that engine
      :selftest (let [{:keys [sign! public-key-hex]} (host/signer (:seed ident))
                      sig (sign! "selftest")
                      cid (w/raw-cid (w/utf8-bytes "selftest"))]
                  {:ok {:resolve-localhost (host/resolve! "localhost")
                        :resolve-dash (host/resolve! "--inspect=0.0.0.0")
                        :sign-verify (boolean (host/verify? public-key-hex "selftest" sig))
                        :cid cid
                        :traversal-blocked (nil? ((:get-bytes! env) "../node.key"))
                        :ipv6-private (web/private-address? "::ffff:7f00:1")
                        :markdown (w/html->markdown "<h1>t</h1><p>a &amp;lt; b</p>")}})
      :fetch (w/fetch! env job)
      :extract (w/extract! env job)
      :crawl (crawl/crawl! env job)
      :search (search/search! env job)
      :index (index/index-crawl! env job)
      :index-search (index/search-index! env job)
      :get (let [bs ((:get-bytes! env) (:cid job))]
             (cond (nil? bs) {:refused :get/not-found}
                   (> (w/byte-count bs) max-get-bytes) {:refused :get/too-large}
                   :else {:ok {:cid (:cid job) :bytes (w/byte-count bs) :base64 (b64 bs)}}))
      ;; Write config files (whitelisted names, validated shapes, mode 0600, atomic).
      ;; The result names files, never contents.
      :configure (let [v (provision/validate-files (:files job))]
                   (if (:refused v)
                     {:refused :configure/invalid :detail (:refused v)}
                     (do (.mkdirSync fs (home) #js {:recursive true :mode 0700})
                         (doseq [[name content] (:ok v)]
                           (let [target (.join path (home) name)
                                 tmp (str target ".tmp-" (.toString (.randomBytes (js/require "crypto") 4) "hex"))]
                             (.writeFileSync fs tmp (str content "\n") #js {:mode 0600})
                             (.renameSync fs tmp target)))
                         {:ok {:written (vec (sort (keys (:ok v))))}})))
      :config-status (let [present (vec (filter #(.existsSync fs (.join path (home) %))
                                                (sort provision/allowed-files)))
                           own? (boolean (yataverse-cli-config))]
                       {:ok {:present present :own-yataverse-identity? own?
                             :put-configured? (or own? (provision/put-configured? present))}})
      :sync (if-let [remote (remote-put-config)]
              {:ok (host/sync-pending! (.join path (home) "store") (pending-file) remote)}
              {:refused :sync/no-remote-configured})
      :gc (let [free (host/free-bytes (home))]
            {:ok (host/store-gc! (.join path (home) "store") (or (:max-bytes job) (* 2 1024 1024 1024)))})
      {:refused :op/unknown :detail op})))

(let [out (try (handle (read-stdin))
               (catch :default e {:refused :node/exception :detail (str (.-message e))}))]
  (println (pr-str out)))
