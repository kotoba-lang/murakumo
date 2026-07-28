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
;; W6 product-shell authority (ADR-260728-w6-kekkai-denial-tokens-pure-oracle +
;; ADR-260728-w6-product-shell-oracle-authority):
;; pure string helpers + status/denial/dir tokens DELEGATE to the precompiled
;; kotoba oracle when loadable (JVM classpath or cljs/nbb —
;; ADR-260728-w6-cljs-oracle-load). Kotoba is SSoT; host mirrors remain fallback.

(ns murakumo.kekkai.gate
  (:require [clojure.string :as str]
            [murakumo.config :as config]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :kekkai-gate)

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- o [export args]
  (oracle/call oid export args))

;; ── host-mirror pure helpers (cljs fallback + semantic documentation) ──

(defn- oracle-str-const [export mirror]
  (try
    (if (oracle/ready? oid)
      (oracle/call oid export [])
      mirror)
    (catch #?(:clj Exception :cljs :default) _
      mirror)))

(def ^:private mirror-default-ledger-path "kekkai-tailnet.edn")
(def ^:private mirror-status-authorized "authorized")
(def ^:private mirror-status-unknown "unknown")
(def ^:private mirror-denial-prefix "[kekkai] ")
(def ^:private mirror-denial-mid ": not authorized (")
(def ^:private mirror-denial-suffix ") — excluded from fleet ops")
(def ^:private mirror-kekkai-dir-suffix
  "/github/com-junkawasaki/orgs/kotoba-lang/kekkai")
(def ^:private mirror-cli-bin "clojure")
(def ^:private mirror-cli-alias-flag "-M")
(def ^:private mirror-cli-main-flag "-m")
(def ^:private mirror-cli-main-ns "kekkai.cli")

;; ── residual status / denial / dir / cli tokens ──────────────────────

(def status-authorized
  "Admitted membership status token. Kotoba when ready."
  (oracle-str-const 'status-authorized mirror-status-authorized))

(def status-unknown
  "Absent/empty status token. Kotoba when ready."
  (oracle-str-const 'status-unknown mirror-status-unknown))

(def denial-prefix
  (oracle-str-const 'denial-prefix mirror-denial-prefix))

(def denial-mid
  (oracle-str-const 'denial-mid mirror-denial-mid))

(def denial-suffix
  (oracle-str-const 'denial-suffix mirror-denial-suffix))

(def kekkai-dir-suffix
  (oracle-str-const 'kekkai-dir-suffix mirror-kekkai-dir-suffix))

(def cli-bin
  (oracle-str-const 'cli-bin mirror-cli-bin))

(def cli-alias-flag
  (oracle-str-const 'cli-alias-flag mirror-cli-alias-flag))

(def cli-main-flag
  (oracle-str-const 'cli-main-flag mirror-cli-main-flag))

(def cli-main-ns
  (oracle-str-const 'cli-main-ns mirror-cli-main-ns))

(defn- mirror-default-kekkai-dir [home]
  (str home kekkai-dir-suffix))

(defn- mirror-parse-status-out [out]
  (let [s (str/trim (str out))]
    (if (seq s) s status-unknown)))

(defn- mirror-denial-line [node-name status]
  (str denial-prefix node-name denial-mid status denial-suffix))

(defn- mirror-authorized? [status]
  (= status-authorized status))

;; ── public API ────────────────────────────────────────────────────────

(def default-ledger-path
  "Constant default ledger path (oracle authority when ready at load)."
  (try
    (if (oracle/ready? oid)
      (oracle/call oid 'default-ledger-path [])
      mirror-default-ledger-path)
    (catch #?(:clj Exception :cljs :default) _
      mirror-default-ledger-path)))

(defn default-kekkai-dir
  "Default sibling kekkai checkout location under a user home."
  [home]
  (if (oracle-ready?)
    (o 'default-kekkai-dir-under [(str home)])
    (mirror-default-kekkai-dir home)))

(defn ledger-path
  "Ledger file path: exact MURAKUMO_KEKKAI_LEDGER via config inject, else default.
   0-arity uses process getenv (exact name only)."
  ([]
   #?(:clj (ledger-path #(System/getenv %))
      :cljs default-ledger-path))
  ([getenv]
   (or (config/kekkai-ledger getenv) default-ledger-path)))

(defn kekkai-dir
  "Kekkai checkout dir: exact MURAKUMO_KEKKAI_DIR via config inject, else
   default under HOME. 0-arity uses process getenv (exact names only)."
  ([]
   #?(:clj (kekkai-dir #(System/getenv %))
      :cljs (default-kekkai-dir "")))
  ([getenv]
   (or (config/kekkai-dir getenv)
       (default-kekkai-dir (or (config/home-dir getenv) "")))))

(defn- mirror-cli-argv [ledger-path node-name]
  [cli-bin cli-alias-flag cli-main-flag cli-main-ns ledger-path node-name])

(defn cli-argv
  "The `kekkai.cli` subprocess argv for one node's status query, run with
   :dir = kekkai-dir so its own deps.edn resolves.
   Binary/flag/ns fragments dual-source from kotoba when oracle ready."
  [ledger-path node-name]
  [cli-bin cli-alias-flag cli-main-flag cli-main-ns
   (str ledger-path) (str node-name)])

(defn parse-status
  "Normalise a kekkai.cli process result ({:exit :out}) into a status string.
   kekkai.cli prints the real status (\"authorized\"/\"pending\"/\"expired\"/
   \"revoked\"/\"unknown\") on stdout even when it exits non-zero (its exit
   code just signals authorized?, per its own contract) — so this reads :out
   regardless of :exit, and only falls back to \"unknown\" when the
   subprocess produced no output at all (a hard failure: bad ledger path,
   missing `clojure` binary, uncaught exception).

   Kotoba oracle when ready; host mirror otherwise."
  [{:keys [out]}]
  (if (oracle-ready?)
    (o 'parse-status-out [(str (or out ""))])
    (mirror-parse-status-out out)))

(defn partition-nodes
  "Split `nodes` into {:admitted [...] :denied [...]} using an injected
   node-name -> status map (already resolved by the host shell). A node
   absent from `status-by-name` is treated as \"unknown\" — deny-by-default,
   same as an unregistered node in kekkai itself.

   List/map reduce remains host (not yet in guest map-fold oracle)."
  [nodes status-by-name]
  (reduce (fn [acc n]
            (let [status (get status-by-name (:name n) status-unknown)
                  ok? (if (oracle-ready?)
                        (= 1 (oracle/i64->host
                              (o 'authorized? [(str status)])))
                        (mirror-authorized? status))]
              (if ok?
                (update acc :admitted conj n)
                (update acc :denied conj (assoc n :kekkai/status status)))))
          {:admitted [] :denied []}
          nodes))

(defn denial-line [node]
  (if (oracle-ready?)
    (o 'denial-line-of
       [(str (:name node)) (str (:kekkai/status node))])
    (mirror-denial-line (:name node) (:kekkai/status node))))
