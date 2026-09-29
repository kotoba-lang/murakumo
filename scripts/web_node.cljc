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
  "Optional yataverse/kotobase put. URL from the file kotobase.url (or
  $MURAKUMO_KOTOBASE_URL); the pre-minted Authorization value from the 0600 file
  kotobase.auth (or $MURAKUMO_KOTOBASE_AUTH). ssh runs a non-login shell, so the
  files are the reliable path. Absent either -> local store only."
  []
  (let [url-file (.join path (home) "kotobase.url")
        url (or (.. js/process -env -MURAKUMO_KOTOBASE_URL)
                (when (.existsSync fs url-file) (.trim (.readFileSync fs url-file "utf8"))))
        auth-file (.join path (home) "kotobase.auth")
        auth (or (.. js/process -env -MURAKUMO_KOTOBASE_AUTH)
                 (when (.existsSync fs auth-file) (.trim (.readFileSync fs auth-file "utf8"))))]
    (when (and (seq url) (seq auth)) {:base-url url :authorization auth})))

(defn- pending-file [] (.join path (home) "pending-put.txt"))

(defn- make-env [{:keys [seed node-did public-key-hex]}]
  (let [zone (:zone (read-edn-file (.join path (home) "zone.edn")))
        backends (some->> (read-edn-file (.join path (home) "backends.edn"))
                          (mapv host/searxng))
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
