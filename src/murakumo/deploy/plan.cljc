;; murakumo.deploy.plan — portable deploy planning helpers.
;;
;; The CLI shell still performs filesystem reads, component builds, SSH
;; forwarding, artifact distribution, and sleeps. This namespace owns the pure
;; manifest parsing and command argv shapes used by that shell.
;;
;; W6 product-shell authority (ADR-260728-w6-deploy-argv-pure-oracle):
;; constants + path/url + probe + argv flag/gate pure helpers DELEGATE to
;; precompiled kotoba/deploy_plan_core when oracle is loadable (JVM classpath
;; or cljs/nbb — ADR-260728-w6-cljs-oracle-load). Regex extract, argv vector
;; assembly, node folds stay host. cljs mirrors remain fallback when oracle
;; is not ready.

(ns murakumo.deploy.plan
  "Portable deploy planning helpers.
   W6 product-shell: path/url + probe + argv pure helpers via deploy_plan_core."
  (:require [clojure.string :as str]
            [murakumo.config :as config]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :deploy-plan)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  "Run oracle body; on failure use mirror."
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(defn- oracle-str-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(defn- oracle-i64-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/i64->host (oracle/call oid export []))
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

;; ── host-mirror pure helpers ───────────────────────────────────────────

(def ^:private mirror-default-wasm "/tmp/murakumo-deploy.wasm")
(def ^:private mirror-default-publish-node "asher")
(def ^:private mirror-artifact-forward-port 18900)
(def ^:private mirror-publish-forward-port 18077)
(def ^:private mirror-forward-settle-ms 1300)
(def ^:private mirror-placement-wait-ms 75000)

(defn- mirror-manifest-dir [manifest]
  (let [d (str/replace (str manifest) #"/[^/]+$" "")]
    (if (= d (str manifest)) "." d)))

(defn- mirror-app-manifest-path [manifest-dir app-manifest]
  (str manifest-dir "/" app-manifest))

(defn- mirror-publish-selector [selector]
  (or selector mirror-default-publish-node))

(defn- mirror-localhost-url [port]
  (str "http://localhost:" port))

(defn- mirror-command-output [out]
  (str/trim (str out)))

(defn- parse-int [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn- mirror-execution-observed? [grep-count-out]
  (try
    (pos? (parse-int (str/trim (str grep-count-out))))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn- mirror-execution-count-command [cid]
  (str "grep -c 'trigger: executed.*" cid "' ~/.murakumo/mesh.log 2>/dev/null"))

(defn- mirror-release-wit-path [release-dir]
  (str release-dir "/../../../crates/kotoba-runtime/wit"))

(defn- mirror-stop-forward-command [local-port]
  (str "pkill -f '" local-port ":localhost' 2>/dev/null"))

(defn- mirror-absolute-git-bin? [p]
  (and (string? p)
       (not (str/blank? p))
       (or (str/starts-with? p "/")
           (boolean (re-matches #"[A-Za-z]:[\\/].*" p)))
       (not= p "git")))

(def ^:private mirror-cp-bin "cp")
(def ^:private mirror-rm-bin "rm")
(def ^:private mirror-rm-rf-flag "-rf")
(def ^:private mirror-cp-recursive-flag "-R")
(def ^:private mirror-git-c-flag "-C")
(def ^:private mirror-git-rev-parse "rev-parse")
(def ^:private mirror-git-short-flag "--short")
(def ^:private mirror-git-head-ref "HEAD")
(def ^:private mirror-version-flag "--version")
(def ^:private mirror-build-features "p2p,realtime-wasm,webrtc")

(defn- mirror-version-bin-path [dest]
  (str dest "/kotoba"))

(defn- mirror-missing-manifest? [manifest]
  (str/blank? (str manifest)))

(defn- mirror-missing-operator-seed? [operator-seed]
  (str/blank? (str operator-seed)))

;; ── dual-source constants ──────────────────────────────────────────────

(def default-wasm
  (oracle-str-const 'default-wasm mirror-default-wasm))

(def default-publish-node
  (oracle-str-const 'default-publish-node mirror-default-publish-node))

(def pinned-binaries ["kotoba" "kotoba-server"])

(def artifact-forward-port
  (oracle-i64-const 'artifact-forward-port mirror-artifact-forward-port))

(def publish-forward-port
  (oracle-i64-const 'publish-forward-port mirror-publish-forward-port))

(def forward-settle-ms
  (oracle-i64-const 'forward-settle-ms mirror-forward-settle-ms))

(def placement-wait-ms
  (oracle-i64-const 'placement-wait-ms mirror-placement-wait-ms))

(def cp-bin
  "cp binary name for pin/copy argvs. Kotoba `cp-bin` when ready."
  (oracle-str-const 'cp-bin mirror-cp-bin))

(def rm-bin
  "rm binary name for tree remove. Kotoba `rm-bin` when ready."
  (oracle-str-const 'rm-bin mirror-rm-bin))

(def rm-rf-flag
  "rm force-recursive flag. Kotoba `rm-rf-flag` when ready."
  (oracle-str-const 'rm-rf-flag mirror-rm-rf-flag))

(def cp-recursive-flag
  "cp recursive flag. Kotoba `cp-recursive-flag` when ready."
  (oracle-str-const 'cp-recursive-flag mirror-cp-recursive-flag))

(def git-c-flag
  "git -C flag. Kotoba `git-c-flag` when ready."
  (oracle-str-const 'git-c-flag mirror-git-c-flag))

(def git-rev-parse
  "git rev-parse subcommand. Kotoba `git-rev-parse` when ready."
  (oracle-str-const 'git-rev-parse mirror-git-rev-parse))

(def git-short-flag
  "git --short flag. Kotoba `git-short-flag` when ready."
  (oracle-str-const 'git-short-flag mirror-git-short-flag))

(def git-head-ref
  "git HEAD ref. Kotoba `git-head-ref` when ready."
  (oracle-str-const 'git-head-ref mirror-git-head-ref))

(def version-flag
  "kotoba --version flag. Kotoba `version-flag` when ready."
  (oracle-str-const 'version-flag mirror-version-flag))

(def build-features
  "BUILD.edn :features string. Kotoba `build-features` when ready."
  (oracle-str-const 'build-features mirror-build-features))

(defn version-bin-path
  "Pinned kotoba binary path under dest. Kotoba `version-bin-path` when ready."
  [dest]
  (try-oracle
   #(o 'version-bin-path [(str dest)])
   #(mirror-version-bin-path dest)))

(defn manifest-dir
  "Directory portion of a manifest path. Bare filename → \".\".
   Kotoba `manifest-dir` when oracle ready."
  [manifest]
  (try-oracle
   #(o 'manifest-dir [(str manifest)])
   #(mirror-manifest-dir manifest)))

(defn manifest-src
  "Extract the first `:src \"...\"` value from a kotoba app manifest string."
  [manifest-text]
  (some-> (re-find #":src\s+\"([^\"]+)\"" manifest-text) second))

(defn manifest-cid
  "Extract the first explicit `:cid \"...\"` value from a kotoba app manifest string."
  [manifest-text]
  (some-> (re-find #":cid\s+\"([^\"]+)\"" manifest-text) second))

(defn app-manifest-path
  "Resolve an app manifest file relative to a desired-state manifest directory.
   Kotoba `app-manifest-path` when oracle ready."
  [manifest-dir app]
  (try-oracle
   #(o 'app-manifest-path [(str manifest-dir) (str (:manifest app))])
   #(mirror-app-manifest-path manifest-dir (:manifest app))))

(defn publish-selector
  "Resolve the publish-node selector, defaulting to the fleet canary.
   Kotoba `publish-selector` when oracle ready (empty ≡ nil)."
  [selector]
  (try-oracle
   #(o 'publish-selector [(str (or selector ""))])
   #(mirror-publish-selector selector)))

(defn resolve-app-input
  "Pure deploy input summary from manifest path/text."
  [manifest manifest-text]
  (let [dir (manifest-dir manifest)
        src (manifest-src manifest-text)]
    {:manifest manifest
     :manifest-dir dir
     :src src
     :src-path (when src (str dir "/" src))
     :explicit-cid (manifest-cid manifest-text)
     :wasm default-wasm}))

(defn component-build-argv
  "argv for `kotoba component build`."
  [kotoba src-path wit wasm]
  [kotoba "component" "build" src-path "--wit-dir" wit "-o" wasm])

(defn app-deploy-argv
  "argv for `kotoba app deploy --publish` through a local port-forward.
   Localhost URL via kotoba `localhost-url` when oracle ready."
  [kotoba manifest wit local-port]
  [kotoba "app" "deploy" manifest "--wit-dir" wit "--publish" "--url"
   (try-oracle
    #(o 'localhost-url [(oracle/as-i64 local-port)])
    #(mirror-localhost-url local-port))])

(defn block-put-argv
  "argv for putting a WASM artifact into a node-local forwarded kotoba server."
  [kotoba token wasm local-port]
  [kotoba "--url" (try-oracle
                   #(o 'localhost-url [(oracle/as-i64 local-port)])
                   #(mirror-localhost-url local-port))
   "--token" token "block" "put" "--file" wasm])

(defn artifact-node-plan
  "Pure per-node plan for distributing an artifact through a local forward."
  [fleet node]
  {:node node
   :host (:host node)
   :remote-port (or (:port node) (:fleet/port fleet) 8077)
   :local-port artifact-forward-port})

(defn artifact-distribution-plan
  "Pure distribution plan over a set of nodes."
  [fleet nodes]
  (mapv #(artifact-node-plan fleet %) nodes))

(defn reachable-artifact-distribution-plan
  "Distribution plans whose host is reachable according to caller-supplied predicate."
  [fleet nodes reachable-host?]
  (filterv #(reachable-host? (:host %))
           (artifact-distribution-plan fleet nodes)))

(defn last-output-line
  "Last non-empty line from a command stdout string, used as component CID."
  [out]
  (last (str/split-lines (str/trim (str out)))))

(defn command-output
  "Trim stdout from command output used as a scalar value.
   Kotoba `command-output` when oracle ready."
  [out]
  (try-oracle
   #(o 'command-output [(str out)])
   #(mirror-command-output out)))

(defn execution-observed?
  "True when a node log grep count indicates the component has executed there.
   Kotoba `execution-observed?` when oracle ready."
  [grep-count-out]
  (try-oracle
   #(= 1 (oracle/i64->host
          (o 'execution-observed? [(str grep-count-out)])))
   #(mirror-execution-observed? grep-count-out)))

(defn execution-count-command
  "Remote shell command that counts execution log lines for a component CID.
   Kotoba `execution-count-command` when ready."
  [cid]
  (try-oracle
   #(o 'execution-count-command [(str cid)])
   #(mirror-execution-count-command cid)))
(defn observed-node
  "Return the node name when its execution count output proves placement."
  [node grep-count-out]
  (when (execution-observed? grep-count-out)
    (:name node)))

(defn observed-nodes
  "Return node names whose placement probe outputs prove execution."
  [node-output-pairs]
  (vec
   (keep (fn [[node grep-count-out]]
           (observed-node node grep-count-out))
         node-output-pairs)))

(defn placement-probe-plan
  "Pure per-node plan for checking whether a component executed there."
  [cid node]
  {:node node
   :host (:host node)
   :command (execution-count-command cid)})

(defn placement-probe-plans
  "Pure placement probe plan over a set of nodes."
  [cid nodes]
  (mapv #(placement-probe-plan cid %) nodes))

(defn placement-probe-results
  "Probe placement outputs for every node using a caller-supplied host command runner."
  [cid nodes probe-host-command]
  (mapv (fn [{:keys [node host command]}]
          [node (probe-host-command host command)])
        (placement-probe-plans cid nodes)))

(defn stop-forward-command
  "Shell command that stops forwards bound to a local port.
   Kotoba `stop-forward-command` when ready."
  [local-port]
  (try-oracle
   #(o 'stop-forward-command [(oracle/as-i64 local-port)])
   #(mirror-stop-forward-command local-port)))

(defn release-wit-path
  "WIT path paired with a release dir (`target/<triple>/release`).
   Kotoba `release-wit-path` when ready."
  [release-dir]
  (try-oracle
   #(o 'release-wit-path [(str release-dir)])
   #(mirror-release-wit-path release-dir)))
(defn pin-copy-plan
  "Pure copy plan for pinning a kotoba release into murakumo's owned ./bin dir."
  [src dest]
  {:src src
   :dest dest
   :binaries (mapv (fn [bin]
                     {:name bin
                      :src (str src "/" bin)
                      :dest (str dest "/" bin)})
                   pinned-binaries)
   :wit {:src (release-wit-path src)
         :dest (str dest "/wit")}})

(defn pin-source
  "Resolve the source release directory for `murakumo pin`."
  [src kotoba-dir]
  (or src (config/release-bin-dir kotoba-dir)))

(defn missing-pin-binaries
  "Pinned binary copy specs whose source does not exist."
  [pin exists?]
  (filterv #(not (exists? (:src %))) (:binaries pin)))

(defn copy-argv
  "argv for copying one file. Bin name dual-sourced via `cp-bin`."
  [src dest]
  [cp-bin src dest])

(defn pin-binary-copy-argvs
  "argvs for copying every pinned binary."
  [pin]
  (mapv (fn [{:keys [src dest]}] (copy-argv src dest))
        (:binaries pin)))

(defn remove-tree-argv
  "argv for removing a directory tree. Bin/flags dual-sourced."
  [path]
  [rm-bin rm-rf-flag path])

(defn copy-tree-argv
  "argv for copying one directory tree. Bin/flags dual-sourced."
  [src dest]
  [cp-bin cp-recursive-flag src dest])

(defn pin-wit-argvs
  "argvs for replacing the pinned WIT dir when a source WIT dir exists."
  [pin wit-exists?]
  (let [{wit-src :src wit-dest :dest} (:wit pin)]
    (if wit-exists?
      [(remove-tree-argv wit-dest)
       (copy-tree-argv wit-src wit-dest)]
      [])))

(def default-git-bin-candidates
  "Absolute git paths only — never bare \"git\" (no PATH scan)."
  ["/opt/homebrew/bin/git" "/usr/bin/git" "/bin/git"])

(defn resolve-git-bin
  "Pick the first absolute candidate that exists and is executable.

  opts:
    :candidates  vector of absolute paths (default default-git-bin-candidates)
    :exists?     (fn [path] bool) — default java.io.File exists+canExecute
    :git-bin     explicit absolute override (must pass absolute-path? policy)

  Returns absolute path string or nil. Never searches PATH."
  ([] (resolve-git-bin {}))
  ([{:keys [candidates exists? git-bin]
     :or {candidates default-git-bin-candidates}}]
   (let [exists? (or exists?
                     #?(:clj (fn [p]
                               (let [f (java.io.File. (str p))]
                                 (and (.isAbsolute f) (.canExecute f))))
                        :cljs (fn [p]
                                (try
                                  (let [fs (js/require "fs")
                                        path (js/require "path")]
                                    (and (.isAbsolute path p) (.existsSync fs p)))
                                  (catch :default _ false)))))]
     (cond
       (and (string? git-bin) (not (str/blank? git-bin)))
       (when (and (or (str/starts-with? git-bin "/")
                      (boolean (re-matches #"[A-Za-z]:[\\/].*" git-bin)))
                  (exists? git-bin))
         git-bin)

       :else
       (some (fn [p] (when (exists? p) p)) candidates)))))

(defn absolute-git-bin?
  "True when `p` looks like an absolute filesystem path to git.
   Kotoba `absolute-unix-git-bin?` for Unix `/…` paths when ready;
   Windows drive letters remain host mirror."
  [p]
  (if (and (string? p)
           (not (str/blank? p))
           (boolean (re-matches #"[A-Za-z]:[\\/].*" p)))
    true
    (try-oracle
     #(= 1 (oracle/i64->host
            (o 'absolute-unix-git-bin? [(str (or p ""))])))
     #(mirror-absolute-git-bin? p))))
(defn git-short-sha-argv
  "argv for reading the pinned source git sha.

  **Requires absolute `git-bin`** (no PATH, no bare \"git\"). Ops hosts
  must call `resolve-git-bin` first. The 1-arity form is removed from the
  ops path — tests pass an explicit absolute path.
  Flag fragments dual-sourced via kotoba git-* exports."
  [src git-bin]
  (when-not (absolute-git-bin? git-bin)
    (throw (ex-info "git-short-sha-argv requires absolute git-bin (no PATH)"
                    {:phase :deploy-plan :git-bin git-bin})))
  [git-bin git-c-flag src git-rev-parse git-short-flag git-head-ref])

(defn version-argv
  "argv for reading the pinned kotoba CLI version.
   Path + flag dual-sourced via version-bin-path / version-flag."
  [dest]
  [(version-bin-path dest) version-flag])

(defn build-manifest
  "Tracked BUILD.edn content for a pinned kotoba binary set.
   :features dual-sourced via `build-features`."
  [source git-sha version]
  {:source source
   :git-sha git-sha
   :version version
   :features build-features})

(defn missing-pinned-binaries?
  "True when a BUILD.edn pins a rollout but the owned pinned server binary is absent."
  [build-manifest pinned-server-exists?]
  (boolean (and build-manifest (not pinned-server-exists?))))

(defn deploy-command-error
  "Validation error keyword for deploy, or nil.
   Kotoba `missing-manifest?` / `missing-operator-seed?` when ready."
  [manifest operator-seed]
  (cond
    (try-oracle
     #(= 1 (oracle/i64->host
            (o 'missing-manifest? [(str (or manifest ""))])))
     #(mirror-missing-manifest? manifest))
    :missing-manifest

    (try-oracle
     #(= 1 (oracle/i64->host
            (o 'missing-operator-seed? [(str (or operator-seed ""))])))
     #(mirror-missing-operator-seed? operator-seed))
    :missing-operator-seed

    :else nil))

(defn deployment-plan
  "Summarise what deploy must do once the manifest has been read.
   If :src exists, caller must build and distribute the artifact; otherwise it
   can publish using the explicit CID already present in the manifest."
  [manifest manifest-text]
  (let [{:keys [src explicit-cid] :as input} (resolve-app-input manifest manifest-text)]
    (assoc input
           :needs-build? (boolean src)
           :cid explicit-cid
           :publish-node default-publish-node)))

(defn deployment-cid
  "Resolve the component CID after any required build output is available."
  [deployment-plan build-output]
  (if (:needs-build? deployment-plan)
    (last-output-line build-output)
    (:explicit-cid deployment-plan)))

(defn deployment-input
  "Shape raw manifest inputs before building a deployment-plan."
  [manifest manifest-text]
  {:manifest manifest
   :manifest-text manifest-text})
