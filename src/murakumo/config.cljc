;; murakumo.config — portable path/config resolution helpers.

(ns murakumo.config
  #?(:clj (:require [clojure.edn :as edn])
     :cljs (:require [cljs.reader :as edn])))

(def default-fleet-path "fleet.edn")
(def default-connect-path "connect.edn")
(def default-cloud-path "cloud.edn")

(defn default-kotoba-dir
  "Default sibling kotoba checkout location under a user home."
  [home]
  (str home "/github/com-junkawasaki/orgs/com-junkawasaki/kotoba"))

(defn kotoba-dir
  "Resolve the kotoba checkout directory from env."
  [env]
  (or (get env "MURAKUMO_KOTOBA_DIR")
      (default-kotoba-dir (get env "HOME"))))

(defn operator-seed-env-keys
  "Env keys consulted for the fleet operator seed, in preference order."
  [fleet]
  (vec (distinct (remove nil? [(:fleet/operator-seed-env fleet)
                               "MURAKUMO_OPERATOR_SEED"]))))

(defn operator-seed
  "Resolve the operator seed from fleet-specific env key, then default env key."
  [env fleet]
  (some #(get env %) (operator-seed-env-keys fleet)))

(defn operator-seed-env
  "Env subset used for operator seed resolution."
  [env fleet]
  (into {}
        (map (fn [k] [k (get env k)]))
        (operator-seed-env-keys fleet)))

(defn env-values
  "Build an env map for `keys` from an injected getenv-like function."
  [getenv keys]
  (into {} (map (fn [k] [k (getenv k)])) keys))

(defn operator-seed-from-getenv
  "Resolve the fleet operator seed with an injected getenv-like function."
  [getenv fleet]
  (operator-seed (env-values getenv (operator-seed-env-keys fleet)) fleet))

(defn current-operator-seed
  "Resolve the fleet operator seed from the current host process env."
  [fleet]
  #?(:clj (operator-seed-from-getenv #(System/getenv %) fleet)
     :cljs (throw (ex-info "current-operator-seed is host-only" {}))))

(defn parse-edn [text]
  (edn/read-string text))

(defn edn-string [value]
  (pr-str value))

(defn read-edn-file
  "Read EDN from a host file. Available in CLJ/babashka shells."
  [path]
  #?(:clj (parse-edn (slurp path))
     :cljs (throw (ex-info "read-edn-file is host-only" {:path path}))))

(defn read-edn-file-or
  "Read EDN from a host file, returning `fallback` on read/parse failure."
  [path fallback]
  (try
    (read-edn-file path)
    (catch #?(:clj Exception :cljs :default) _ fallback)))

(defn write-edn-file
  "Write EDN to a host file. Available in CLJ/babashka shells."
  [path value]
  #?(:clj (spit path (edn-string value))
     :cljs (throw (ex-info "write-edn-file is host-only" {:path path}))))

(defn- unblob
  "Undo edn-datomize.cljs's pr-str blobbing of a non-scalar value. Strings that
   don't parse back to a collection (ordinary scalar strings) pass through
   unchanged."
  [v]
  (if (string? v)
    (try
      (let [parsed (parse-edn v)]
        (if (coll? parsed) parsed v))
      (catch #?(:clj Exception :cljs :default) _ v))
    v))

(defn tx-data->map
  "Reconstitute the original plain map from an edn-datomize.cljs
   wrap-map-keep-ns! tx-data vector (`[{:db/id ... attr val ...}]`),
   stripping :db/id, un-namespacing attrs whose namespace is `promote-ns`
   back to bare keys, and unblobbing pr-str'd non-scalar values. Attrs whose
   namespace is something OTHER than `promote-ns` (i.e. genuinely
   pre-existing namespaces the file already used, like :overlay/* in
   cloud.edn) are left namespaced as-is.

   Content that is NOT already in this tx-data shape (a plain map, e.g. a
   file nobody has run edn-datomize.cljs over) passes through unchanged, so
   this is safe to call unconditionally on read."
  [content promote-ns]
  (if (and (vector? content)
           (= 1 (count content))
           (map? (first content))
           (contains? (first content) :db/id))
    (into {}
          (map (fn [[k v]]
                 (let [k' (if (= (namespace k) promote-ns) (keyword (name k)) k)]
                   [k' (unblob v)])))
          (dissoc (first content) :db/id))
    content))

(defn runtime-env
  "Env subset used for local kotoba runtime path resolution."
  [env]
  {"MURAKUMO_BIN" (get env "MURAKUMO_BIN")
   "MURAKUMO_KOTOBA_DIR" (get env "MURAKUMO_KOTOBA_DIR")
   "HOME" (get env "HOME")})

(def runtime-env-keys
  ["MURAKUMO_BIN" "MURAKUMO_KOTOBA_DIR" "HOME"])

(defn runtime-env-from-getenv
  "Runtime env subset from an injected getenv-like function."
  [getenv]
  (env-values getenv runtime-env-keys))

(defn pinned-bin-dir [user-dir]
  (str user-dir "/bin"))

(defn release-bin-dir [kotoba-dir]
  (str kotoba-dir "/target/aarch64-apple-darwin/release"))

(defn resolve-local-bin
  "Resolve the binary dir preference order.

   `pinned-exists?` is supplied by the host shell after checking for the pinned
   kotoba-server binary."
  [env user-dir kotoba-dir pinned-exists?]
  (let [pinned (pinned-bin-dir user-dir)]
    (cond
      pinned-exists? pinned
      (get env "MURAKUMO_BIN") (get env "MURAKUMO_BIN")
      :else (release-bin-dir kotoba-dir))))

(defn kotoba-bin
  "kotoba CLI executable path, falling back to PATH lookup when no pinned binary exists."
  [user-dir pinned-exists?]
  (let [pinned (str (pinned-bin-dir user-dir) "/kotoba")]
    (if pinned-exists? pinned "kotoba")))

(defn kotoba-server-bin [bin-dir]
  (str bin-dir "/kotoba-server"))

(defn local-kotoba-bin [bin-dir]
  (str bin-dir "/kotoba"))

(defn pinned-wit-dir [user-dir]
  (str (pinned-bin-dir user-dir) "/wit"))

(defn runtime-wit-dir [kotoba-dir]
  (str kotoba-dir "/crates/kotoba-runtime/wit"))

(defn resolve-wit-dir
  "Resolve deploy WIT dir from pinned WIT existence."
  [user-dir kotoba-dir pinned-wit-exists?]
  (if pinned-wit-exists?
    (pinned-wit-dir user-dir)
    (runtime-wit-dir kotoba-dir)))

(defn build-manifest-path [user-dir]
  (str (pinned-bin-dir user-dir) "/BUILD.edn"))

(defn peers-path
  "Control-plane peer-id cache path under the repo root."
  [_user-dir]
  ".murakumo-peers.edn")

(defn launchd-template-path
  "Resident LaunchDaemon template path under the repo root."
  [_user-dir]
  "deploy/com.murakumo.kotoba-mesh.plist.tmpl")

(defn runtime-probe-paths
  "Paths the host shell should check before building a runtime-context."
  [user-dir]
  (let [pinned (pinned-bin-dir user-dir)]
    {:pinned-bin pinned
     :pinned-server (kotoba-server-bin pinned)
     :pinned-kotoba (local-kotoba-bin pinned)
     :pinned-wit (pinned-wit-dir user-dir)}))

(defn runtime-probe-results
  "Convert runtime probe paths into booleans using an injected existence predicate."
  [probe-paths exists?]
  {:pinned-server-exists? (boolean (exists? (:pinned-server probe-paths)))
   :pinned-kotoba-exists? (boolean (exists? (:pinned-kotoba probe-paths)))
   :pinned-wit-exists? (boolean (exists? (:pinned-wit probe-paths)))})

(defn runtime-context
  "Resolve all local runtime paths from pure inputs.

   Existence checks are supplied by the host shell so this remains portable and
   deterministic under tests."
  [env user-dir pinned-server-exists? pinned-kotoba-exists? pinned-wit-exists?]
  (let [kotoba-dir (kotoba-dir env)
        local-bin (resolve-local-bin env user-dir kotoba-dir pinned-server-exists?)]
    {:user-dir user-dir
     :kotoba-dir kotoba-dir
     :local-bin local-bin
     :kotoba (local-kotoba-bin local-bin)
     :kotoba-server (kotoba-server-bin local-bin)
     :cli-kotoba (kotoba-bin user-dir pinned-kotoba-exists?)
     :wit (resolve-wit-dir user-dir kotoba-dir pinned-wit-exists?)
     :build-manifest (build-manifest-path user-dir)}))

(defn runtime-context-from-probes
  "Resolve runtime-context from env, user-dir, and a map of probe booleans."
  [env user-dir probes]
  (runtime-context (runtime-env env)
                   user-dir
                   (:pinned-server-exists? probes)
                   (:pinned-kotoba-exists? probes)
                   (:pinned-wit-exists? probes)))

(defn runtime-context-from-env
  "Resolve runtime-context from env, user-dir, and an injected existence predicate."
  [env user-dir exists?]
  (let [probes (runtime-probe-paths user-dir)]
    (runtime-context-from-probes env user-dir (runtime-probe-results probes exists?))))

(defn runtime-context-from-getenv
  "Resolve runtime-context from injected getenv and existence predicates."
  [getenv user-dir exists?]
  (runtime-context-from-env (runtime-env-from-getenv getenv) user-dir exists?))

(defn current-runtime-context
  "Resolve runtime-context from the current host process env and filesystem."
  []
  #?(:clj (let [user-dir (System/getProperty "user.dir")]
            (runtime-context-from-getenv #(System/getenv %)
                                         user-dir
                                         #(.exists (java.io.File. %))))
     :cljs (throw (ex-info "current-runtime-context is host-only" {}))))
