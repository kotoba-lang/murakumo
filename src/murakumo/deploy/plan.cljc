;; murakumo.deploy.plan — portable deploy planning helpers.
;;
;; The CLI shell still performs filesystem reads, component builds, SSH
;; forwarding, artifact distribution, and sleeps. This namespace owns the pure
;; manifest parsing and command argv shapes used by that shell.

(ns murakumo.deploy.plan
  (:require [clojure.string :as str]
            [murakumo.config :as config]))

(def default-wasm "/tmp/murakumo-deploy.wasm")
(def default-publish-node "asher")
(def pinned-binaries ["kotoba" "kotoba-server"])
(def artifact-forward-port 18900)
(def publish-forward-port 18077)
(def forward-settle-ms 1300)
(def placement-wait-ms 75000)

(defn manifest-dir
  "Directory portion of a manifest path. A bare filename (no slash) resolves
   to \".\" — the old return-unchanged behaviour made reconcile --apply build
   broken app paths like \"murakumo.app.edn/../kenchi/…\" when invoked with a
   bare relative manifest (found converging kenchi, ADR-2607071500 追記2)."
  [manifest]
  (let [d (str/replace manifest #"/[^/]+$" "")]
    (if (= d manifest) "." d)))

(defn manifest-src
  "Extract the first `:src \"...\"` value from a kotoba app manifest string."
  [manifest-text]
  (some-> (re-find #":src\s+\"([^\"]+)\"" manifest-text) second))

(defn manifest-cid
  "Extract the first explicit `:cid \"...\"` value from a kotoba app manifest string."
  [manifest-text]
  (some-> (re-find #":cid\s+\"([^\"]+)\"" manifest-text) second))

(defn app-manifest-path
  "Resolve an app manifest file relative to a desired-state manifest directory."
  [manifest-dir app]
  (str manifest-dir "/" (:manifest app)))

(defn publish-selector
  "Resolve the publish-node selector, defaulting to the fleet canary."
  [selector]
  (or selector default-publish-node))

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
  "argv for `kotoba app deploy --publish` through a local port-forward."
  [kotoba manifest wit local-port]
  [kotoba "app" "deploy" manifest "--wit-dir" wit "--publish" "--url"
   (str "http://localhost:" local-port)])

(defn block-put-argv
  "argv for putting a WASM artifact into a node-local forwarded kotoba server."
  [kotoba token wasm local-port]
  [kotoba "--url" (str "http://localhost:" local-port)
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
  "Trim stdout from command output used as a scalar value."
  [out]
  (str/trim (str out)))

(defn- parse-int [s]
  #?(:clj (Integer/parseInt s)
     :cljs (js/parseInt s 10)))

(defn execution-observed?
  "True when a node log grep count indicates the component has executed there."
  [grep-count-out]
  (try
    (pos? (parse-int (str/trim (str grep-count-out))))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn execution-count-command
  "Remote shell command that counts execution log lines for a component CID."
  [cid]
  (str "grep -c 'trigger: executed.*" cid "' ~/.murakumo/mesh.log 2>/dev/null"))

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
  "Shell command that stops forwards bound to a local port."
  [local-port]
  (str "pkill -f '" local-port ":localhost' 2>/dev/null"))

(defn release-wit-path
  "WIT path paired with a release dir (`target/<triple>/release`)."
  [release-dir]
  (str release-dir "/../../../crates/kotoba-runtime/wit"))

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
  "argv for copying one file."
  [src dest]
  ["cp" src dest])

(defn pin-binary-copy-argvs
  "argvs for copying every pinned binary."
  [pin]
  (mapv (fn [{:keys [src dest]}] (copy-argv src dest))
        (:binaries pin)))

(defn remove-tree-argv
  "argv for removing a directory tree."
  [path]
  ["rm" "-rf" path])

(defn copy-tree-argv
  "argv for copying one directory tree."
  [src dest]
  ["cp" "-R" src dest])

(defn pin-wit-argvs
  "argvs for replacing the pinned WIT dir when a source WIT dir exists."
  [pin wit-exists?]
  (let [{wit-src :src wit-dest :dest} (:wit pin)]
    (if wit-exists?
      [(remove-tree-argv wit-dest)
       (copy-tree-argv wit-src wit-dest)]
      [])))

(defn git-short-sha-argv
  "argv for reading the pinned source git sha."
  [src]
  ["git" "-C" src "rev-parse" "--short" "HEAD"])

(defn version-argv
  "argv for reading the pinned kotoba CLI version."
  [dest]
  [(str dest "/kotoba") "--version"])

(defn build-manifest
  "Tracked BUILD.edn content for a pinned kotoba binary set."
  [source git-sha version]
  {:source source
   :git-sha git-sha
   :version version
   :features "p2p,realtime-wasm,webrtc"})

(defn missing-pinned-binaries?
  "True when a BUILD.edn pins a rollout but the owned pinned server binary is absent."
  [build-manifest pinned-server-exists?]
  (boolean (and build-manifest (not pinned-server-exists?))))

(defn deploy-command-error
  "Validation error keyword for deploy, or nil."
  [manifest operator-seed]
  (cond
    (str/blank? (str manifest)) :missing-manifest
    (str/blank? (str operator-seed)) :missing-operator-seed
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
