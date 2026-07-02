;; murakumo.kekkai — zero-trust fleet-admission host shell.
;;
;; The gate is OPT-IN: absent a ledger file at murakumo.kekkai.gate/ledger-path
;; (default ./kekkai-tailnet.edn), every fleet.edn node passes through
;; unchanged — today's behaviour. Adopting kekkai is a config change, not a
;; breaking default. See kekkai-tailnet.edn.example.
;;
;; kekkai rides langgraph/JVM; murakumo's own CLI runs on babashka. Rather than
;; requiring kekkai in-process (a sci/babashka compatibility risk), status
;; lookups shell out to `clojure -M -m kekkai.cli` in the sibling kekkai
;; checkout — the same process-boundary shape murakumo already uses for the
;; kotoba/tailscale/ssh/quic-driver binaries (see murakumo.core, murakumo.ssh).

(ns murakumo.kekkai
  (:require [babashka.process :as p]
            [murakumo.kekkai.gate :as gate]))

(defn- getenv [k] (System/getenv k))

(defn- absolute
  "Canonicalize a (possibly relative-to-murakumo's-cwd) path, because the
   kekkai.cli subprocess runs with :dir = the kekkai checkout (so ITS
   deps.edn resolves), not murakumo's cwd — a relative ledger path would
   otherwise be looked up in the wrong directory."
  [path]
  (.getCanonicalPath (java.io.File. path)))

(defn enabled?
  "The gate activates only when its ledger file exists on disk."
  []
  (.exists (java.io.File. (gate/ledger-path getenv))))

(defn status-for
  "One node's kekkai admission status, via the kekkai.cli subprocess.
   \"unknown\" when the sibling kekkai checkout is absent or the subprocess
   fails (fail closed, not fail open)."
  [node-name]
  (let [dir (gate/kekkai-dir getenv)]
    (if-not (.exists (java.io.File. dir))
      "unknown"
      (gate/parse-status
       (p/sh (gate/cli-argv (absolute (gate/ledger-path getenv)) node-name) {:dir dir})))))

(defn apply-gate
  "Filter `nodes` to kekkai-authorized members when the gate is enabled;
   passes them through unchanged otherwise. Denied nodes are reported to
   stderr (fail visible, not silent) and excluded from the result."
  [nodes]
  (if-not (enabled?)
    nodes
    (let [status-by-name (into {} (map (fn [n] [(:name n) (status-for (:name n))])) nodes)
          {:keys [admitted denied]} (gate/partition-nodes nodes status-by-name)]
      (doseq [n denied] (binding [*out* *err*] (println (gate/denial-line n))))
      admitted)))
