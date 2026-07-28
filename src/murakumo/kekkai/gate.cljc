;; murakumo.kekkai.gate — portable core of the zero-trust fleet-admission gate.
;;
;; fleet.edn is the DESIRED inventory; the kekkai ledger is the ADMITTED
;; membership record (kotoba-lang/kekkai's zero-trust, Tailscale-equivalent
;; control plane). A node must be present + status="authorized" in the ledger
;; before murakumo will operate on it — being merely listed in fleet.edn is
;; not enough, or the "zero-trust" governor would be a no-op. Shell execution
;; (the kekkai.cli subprocess) stays in the host-only murakumo.kekkai; this ns
;; holds the env-resolution and node-partitioning logic, tested offline.
;;
;; W6 product-shell authority (ADR-260728-w6-product-shell-oracle-authority):
;; On the JVM, pure string helpers DELEGATE to the precompiled kotoba oracle
;; (kotoba/kekkai_gate_core.kotoba → resources/murakumo/oracle/…kir.edn).
;; Kotoba is SSoT; the cljc bodies below are the cljs/host-mirror fallback and
;; document the expected semantics for readers.

(ns murakumo.kekkai.gate
  (:require [clojure.string :as str]
            #?(:clj [murakumo.kotoba.oracle :as oracle])))

;; ── host-mirror pure helpers (cljs fallback + semantic documentation) ──

(def ^:private mirror-default-ledger-path "kekkai-tailnet.edn")

(defn- mirror-default-kekkai-dir [home]
  (str home "/github/com-junkawasaki/orgs/kotoba-lang/kekkai"))

(defn- mirror-parse-status-out [out]
  (let [s (str/trim (str out))]
    (if (seq s) s "unknown")))

(defn- mirror-denial-line [node-name status]
  (str "[kekkai] " node-name ": not authorized (" status
       ") — excluded from fleet ops"))

(defn- mirror-authorized? [status]
  (= "authorized" status))

;; ── public API ────────────────────────────────────────────────────────

(def default-ledger-path
  "Constant default ledger path (oracle authority on JVM)."
  #?(:clj (oracle/call :kekkai-gate 'default-ledger-path [])
     :cljs mirror-default-ledger-path))

(defn default-kekkai-dir
  "Default sibling kekkai checkout location under a user home."
  [home]
  #?(:clj (oracle/call :kekkai-gate 'default-kekkai-dir-under [(str home)])
     :cljs (mirror-default-kekkai-dir home)))

(defn ledger-path [getenv]
  (or (getenv "MURAKUMO_KEKKAI_LEDGER") default-ledger-path))

(defn kekkai-dir [getenv]
  (or (getenv "MURAKUMO_KEKKAI_DIR") (default-kekkai-dir (getenv "HOME"))))

(defn cli-argv
  "The `kekkai.cli` subprocess argv for one node's status query, run with
   :dir = kekkai-dir so its own deps.edn resolves."
  [ledger-path node-name]
  ["clojure" "-M" "-m" "kekkai.cli" ledger-path node-name])

(defn parse-status
  "Normalise a kekkai.cli process result ({:exit :out}) into a status string.
   kekkai.cli prints the real status (\"authorized\"/\"pending\"/\"expired\"/
   \"revoked\"/\"unknown\") on stdout even when it exits non-zero (its exit
   code just signals authorized?, per its own contract) — so this reads :out
   regardless of :exit, and only falls back to \"unknown\" when the
   subprocess produced no output at all (a hard failure: bad ledger path,
   missing `clojure` binary, uncaught exception).

   JVM: kotoba oracle authority (parse-status-out). cljs: host mirror."
  [{:keys [out]}]
  #?(:clj (oracle/call :kekkai-gate 'parse-status-out [(str (or out ""))])
     :cljs (mirror-parse-status-out out)))

(defn partition-nodes
  "Split `nodes` into {:admitted [...] :denied [...]} using an injected
   node-name -> status map (already resolved by the host shell). A node
   absent from `status-by-name` is treated as \"unknown\" — deny-by-default,
   same as an unregistered node in kekkai itself.

   List/map reduce remains host (not yet in guest map-fold oracle)."
  [nodes status-by-name]
  (reduce (fn [acc n]
            (let [status (get status-by-name (:name n) "unknown")
                  ok? #?(:clj (= 1 (oracle/call :kekkai-gate 'authorized? [(str status)]))
                         :cljs (mirror-authorized? status))]
              (if ok?
                (update acc :admitted conj n)
                (update acc :denied conj (assoc n :kekkai/status status)))))
          {:admitted [] :denied []}
          nodes))

(defn denial-line [node]
  #?(:clj (oracle/call :kekkai-gate 'denial-line-of
                       [(str (:name node)) (str (:kekkai/status node))])
     :cljs (mirror-denial-line (:name node) (:kekkai/status node))))
