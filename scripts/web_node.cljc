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

(defn- make-env [{:keys [seed node-did]}]
  (let [zone (:zone (read-edn-file (.join path (home) "zone.edn")))
        backends (some->> (read-edn-file (.join path (home) "backends.edn"))
                          (mapv host/searxng))]
    (-> (host/env {:store-dir (.join path (home) "store") :seed-hex seed :node-did node-did})
        (assoc :sleep! sleep! :backends backends :zone zone))))

(defn- b64 [bs] (.toString (js/Buffer.from bs) "base64"))

(defn health [ident env]
  (let [dir (.join path (home) "store")
        usage (host/store-usage dir)
        free (host/free-bytes (home))
        curl? (zero? (.-status (.spawnSync (js/require "child_process") "curl" #js ["--version"])))
        problems (cond-> []
                   (not curl?) (conj :curl/missing)
                   (and free (< free min-free-bytes)) (conj :disk/low)
                   (empty? (:backends env)) (conj :search/no-backends-configured))]
    {:ok {:version version :node-did (:node-did ident) :zone (:zone env)
          :store usage :free-bytes free :problems problems
          ;; no backends is a config note, not an outage for fetch/crawl
          :healthy? (empty? (remove #{:search/no-backends-configured} problems))}}))

(defn handle [{:keys [op job]}]
  (let [ident (identity-info)
        env (make-env ident)]
    (case op
      :whoami {:ok {:version version :node-did (:node-did ident)
                    :public-key-hex (:public-key-hex ident) :zone (:zone env)}}
      :health (health ident env)
      :fetch (w/fetch! env job)
      :extract (w/extract! env job)
      :crawl (crawl/crawl! env job)
      :search (search/search! env job)
      :get (let [bs ((:get-bytes! env) (:cid job))]
             (cond (nil? bs) {:refused :get/not-found}
                   (> (w/byte-count bs) max-get-bytes) {:refused :get/too-large}
                   :else {:ok {:cid (:cid job) :bytes (w/byte-count bs) :base64 (b64 bs)}}))
      :gc (let [free (host/free-bytes (home))]
            {:ok (host/store-gc! (.join path (home) "store") (or (:max-bytes job) (* 2 1024 1024 1024)))})
      {:refused :op/unknown :detail op})))

(let [out (try (handle (read-stdin))
               (catch :default e {:refused :node/exception :detail (str (.-message e))}))]
  (println (pr-str out)))
