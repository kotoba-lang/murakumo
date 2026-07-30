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
;; W6 product-shell authority + T6.4 remainder (oracle-required on JVM):
;; pure string helpers + status/denial/dir tokens DELEGATE to the precompiled
;; kotoba oracle. On :clj the oracle resource is required (T6.2 shipped KIR on
;; the prod classpath) — host pure mirrors are cljs-only fail-closed fallback
;; when register-kir! / resource load is unavailable (ADR-260731-w6-t64-kekkai-oracle-required).

(ns murakumo.kekkai.gate
  (:require [clojure.string :as str]
            [murakumo.config :as config]
            [murakumo.kotoba.oracle :as oracle]))

(def ^:private oid :kekkai-gate)

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- o
  "Call a pure export. JVM requires the oracle artifact; cljs may fall back."
  [export args]
  #?(:clj
     (do
       (when-not (oracle-ready?)
         (throw (ex-info "kekkai-gate oracle not ready (JVM requires shipped KIR)"
                         {:oracle-id oid :export export})))
       (oracle/call oid export args))
     :cljs
     (if (oracle-ready?)
       (try
         (oracle/call oid export args)
         (catch :default _
           ::oracle-failed))
       ::oracle-failed)))

#?(:cljs
   (do
     ;; ── cljs-only host-mirror pure helpers (fail-closed without oracle) ──
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

     (defn- cljs-or-mirror [export mirror]
       (let [v (o export [])]
         (if (= v ::oracle-failed) mirror v)))

     (defn- mirror-default-kekkai-dir [home]
       (str home mirror-kekkai-dir-suffix))

     (defn- mirror-parse-status-out [out]
       (let [s (str/trim (str out))]
         (if (seq s) s mirror-status-unknown)))

     (defn- mirror-denial-line [node-name status]
       (str mirror-denial-prefix node-name mirror-denial-mid status mirror-denial-suffix))

     (defn- mirror-authorized? [status]
       (= mirror-status-authorized status))))

;; ── residual status / denial / dir / cli tokens ──────────────────────

(def status-authorized
  "Admitted membership status token. Kotoba SSoT (JVM requires oracle)."
  #?(:clj (o 'status-authorized [])
     :cljs (cljs-or-mirror 'status-authorized mirror-status-authorized)))

(def status-unknown
  "Absent/empty status token. Kotoba SSoT (JVM requires oracle)."
  #?(:clj (o 'status-unknown [])
     :cljs (cljs-or-mirror 'status-unknown mirror-status-unknown)))

(def denial-prefix
  #?(:clj (o 'denial-prefix [])
     :cljs (cljs-or-mirror 'denial-prefix mirror-denial-prefix)))

(def denial-mid
  #?(:clj (o 'denial-mid [])
     :cljs (cljs-or-mirror 'denial-mid mirror-denial-mid)))

(def denial-suffix
  #?(:clj (o 'denial-suffix [])
     :cljs (cljs-or-mirror 'denial-suffix mirror-denial-suffix)))

(def kekkai-dir-suffix
  #?(:clj (o 'kekkai-dir-suffix [])
     :cljs (cljs-or-mirror 'kekkai-dir-suffix mirror-kekkai-dir-suffix)))

(def cli-bin
  #?(:clj (o 'cli-bin [])
     :cljs (cljs-or-mirror 'cli-bin mirror-cli-bin)))

(def cli-alias-flag
  #?(:clj (o 'cli-alias-flag [])
     :cljs (cljs-or-mirror 'cli-alias-flag mirror-cli-alias-flag)))

(def cli-main-flag
  #?(:clj (o 'cli-main-flag [])
     :cljs (cljs-or-mirror 'cli-main-flag mirror-cli-main-flag)))

(def cli-main-ns
  #?(:clj (o 'cli-main-ns [])
     :cljs (cljs-or-mirror 'cli-main-ns mirror-cli-main-ns)))

;; ── public API ────────────────────────────────────────────────────────

(def default-ledger-path
  "Constant default ledger path (oracle authority; JVM requires shipped KIR)."
  #?(:clj (o 'default-ledger-path [])
     :cljs (cljs-or-mirror 'default-ledger-path mirror-default-ledger-path)))

(defn default-kekkai-dir
  "Default sibling kekkai checkout location under a user home."
  [home]
  #?(:clj (o 'default-kekkai-dir-under [(str home)])
     :cljs (let [v (o 'default-kekkai-dir-under [(str home)])]
             (if (= v ::oracle-failed)
               (mirror-default-kekkai-dir home)
               v))))

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

(defn cli-argv
  "The `kekkai.cli` subprocess argv for one node's status query, run with
   :dir = kekkai-dir so its own deps.edn resolves.
   Binary/flag/ns fragments dual-source from kotoba when oracle ready."
  [ledger-path node-name]
  [cli-bin cli-alias-flag cli-main-flag cli-main-ns
   (str ledger-path) (str node-name)])

(defn parse-status
  "Normalise a kekkai.cli process result ({:exit :out}) into a status string.
   kekkai.cli prints the real status on stdout even when it exits non-zero;
   only falls back to \"unknown\" when the subprocess produced no output.

   JVM: kotoba oracle required. cljs: oracle when ready, else mirror."
  [{:keys [out]}]
  #?(:clj (o 'parse-status-out [(str (or out ""))])
     :cljs (let [v (o 'parse-status-out [(str (or out ""))])]
             (if (= v ::oracle-failed)
               (mirror-parse-status-out out)
               v))))

(defn partition-nodes
  "Split `nodes` into {:admitted [...] :denied [...]} using an injected
   node-name -> status map (already resolved by the host shell). A node
   absent from `status-by-name` is treated as \"unknown\" — deny-by-default.

   List/map reduce remains host (not yet in guest map-fold oracle)."
  [nodes status-by-name]
  (reduce (fn [acc n]
            (let [status (get status-by-name (:name n) status-unknown)
                  ok? #?(:clj (oracle/bool->host
                               (o 'authorized? [(str status)]))
                         :cljs (let [v (o 'authorized? [(str status)])]
                                 (if (= v ::oracle-failed)
                                   (mirror-authorized? status)
                                   (oracle/bool->host v))))]
              (if ok?
                (update acc :admitted conj n)
                (update acc :denied conj (assoc n :kekkai/status status)))))
          {:admitted [] :denied []}
          nodes))

(defn denial-line [node]
  #?(:clj (o 'denial-line-of
             [(str (:name node)) (str (:kekkai/status node))])
     :cljs (let [v (o 'denial-line-of
                      [(str (:name node)) (str (:kekkai/status node))])]
             (if (= v ::oracle-failed)
               (mirror-denial-line (:name node) (:kekkai/status node))
               v))))
