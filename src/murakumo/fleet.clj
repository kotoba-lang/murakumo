;; murakumo.fleet — fleet inventory + Tailscale enrichment.

(ns murakumo.fleet
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn load-fleet
  "Read fleet.edn from the repo root (or an explicit path)."
  ([] (load-fleet "fleet.edn"))
  ([path] (edn/read-string (slurp path))))

(defn node-port [fleet node]
  (or (:port node) (:fleet/port fleet) 8077))

(defn tailscale-status
  "Map of tailscale-name → {:ip :online?} from `tailscale status`.
   Empty map if tailscale is absent (the control plane still works via plain ssh)."
  []
  (let [{:keys [exit out]} (p/sh "tailscale" "status")]
    (if (zero? exit)
      (into {}
            (for [line (str/split-lines (str out))
                  :let [cols (str/split (str/trim line) #"\s+")]
                  :when (>= (count cols) 4)]
              [(nth cols 1) {:ip (nth cols 0)
                             :online? (not (str/includes? line "offline"))}]))
      {})))

(defn enrich
  "Annotate each fleet node with its Tailscale ip/online state."
  [fleet]
  (let [ts (tailscale-status)]
    (update fleet :nodes
            (fn [ns] (mapv (fn [n] (merge n (get ts (:name n) {}))) ns)))))

(defn select
  "Resolve a node selector arg → seq of node maps. nil/\"all\" → every node;
   otherwise a comma list of names."
  [fleet sel]
  (let [ns (:nodes fleet)]
    (if (or (nil? sel) (= sel "all"))
      ns
      (let [want (set (str/split sel #","))]
        (filter #(want (:name %)) ns)))))
