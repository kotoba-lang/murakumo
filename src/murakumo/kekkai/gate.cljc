;; murakumo.kekkai.gate — portable core of the zero-trust fleet-admission gate.
;;
;; fleet.edn is the DESIRED inventory; the kekkai ledger is the ADMITTED
;; membership record (kotoba-lang/kekkai's zero-trust, Tailscale-equivalent
;; control plane). A node must be present + status="authorized" in the ledger
;; before murakumo will operate on it — being merely listed in fleet.edn is
;; not enough, or the "zero-trust" governor would be a no-op. Shell execution
;; (the kekkai.cli subprocess) stays in the host-only murakumo.kekkai; this ns
;; holds the env-resolution and node-partitioning logic, tested offline.

(ns murakumo.kekkai.gate
  (:require [clojure.string :as str]))

(def default-ledger-path "kekkai-tailnet.edn")

(defn default-kekkai-dir
  "Default sibling kekkai checkout location under a user home."
  [home]
  (str home "/github/com-junkawasaki/orgs/kotoba-lang/kekkai"))

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
   missing `clojure` binary, uncaught exception)."
  [{:keys [out]}]
  (let [s (str/trim (str out))]
    (if (seq s) s "unknown")))

(defn partition-nodes
  "Split `nodes` into {:admitted [...] :denied [...]} using an injected
   node-name -> status map (already resolved by the host shell). A node
   absent from `status-by-name` is treated as \"unknown\" — deny-by-default,
   same as an unregistered node in kekkai itself."
  [nodes status-by-name]
  (reduce (fn [acc n]
            (let [status (get status-by-name (:name n) "unknown")]
              (if (= "authorized" status)
                (update acc :admitted conj n)
                (update acc :denied conj (assoc n :kekkai/status status)))))
          {:admitted [] :denied []}
          nodes))

(defn denial-line [node]
  (str "[kekkai] " (:name node) ": not authorized (" (:kekkai/status node)
       ") — excluded from fleet ops"))
