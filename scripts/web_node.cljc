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

(defn- read-edn-file [f]
  (when (.existsSync fs f) (reader/read-string (.readFileSync fs f "utf8"))))

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

(defn- remote-put-config
  "Optional yataverse put (the bytes plane formerly named kotobase). Files under
  the node home (ssh is a non-login shell, so env vars are unreliable); the old
  kotobase.* names are still read as a fallback:
    yataverse.url        gateway base URL
    yataverse.prefix     optional path prefix, default /ipfs/
    yataverse.auth-cmd   EDN argv vector of the operator's MINTER, run per write
                         (write authorizations are short-lived CACAOs with
                         single-use nonces — a stored value works once at best)
    yataverse.auth       static value, only for a gateway that accepts one (0600)
  Absent url or any authorization -> local store only."
  []
  (let [rd (fn [base] (let [try-file (fn [n] (let [p (.join path (home) n)]
                                               (when (.existsSync fs p) (.trim (.readFileSync fs p "utf8")))))]
                        (or (try-file (str "yataverse." base)) (try-file (str "kotobase." base)))))
        env (fn [a b] (or (aget (.-env js/process) a) (aget (.-env js/process) b)))
        url (or (env "MURAKUMO_YATAVERSE_URL" "MURAKUMO_KOTOBASE_URL") (rd "url"))
        prefix (rd "prefix")
        cmd (some-> (rd "auth-cmd") reader/read-string)
        static (or (env "MURAKUMO_YATAVERSE_AUTH" "MURAKUMO_KOTOBASE_AUTH") (rd "auth"))]
    (when (and (seq url) (or cmd (seq static)))
      (cond-> {:base-url url}
        (seq prefix) (assoc :path-prefix prefix)
        cmd (assoc :authorization-fn #(host/auth-from-command cmd))
        (and (not cmd) (seq static)) (assoc :authorization static)))))

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
                   (and free (< free min-free-bytes)) (conj :disk/low)
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
      :sync (if-let [remote (remote-put-config)]
              {:ok (host/sync-pending! (.join path (home) "store") (pending-file) remote)}
              {:refused :sync/no-remote-configured})
      :gc (let [free (host/free-bytes (home))]
            {:ok (host/store-gc! (.join path (home) "store") (or (:max-bytes job) (* 2 1024 1024 1024)))})
      {:refused :op/unknown :detail op})))

(let [out (try (handle (read-stdin))
               (catch :default e {:refused :node/exception :detail (str (.-message e))}))]
  (println (pr-str out)))
