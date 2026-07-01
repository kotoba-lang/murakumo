;; murakumo.fleet.inventory — portable fleet inventory helpers.
;;
;; This is the .cljc source of truth for selector/defaulting logic and portable
;; parsing of host inventory command output. Shell execution stays in murakumo.fleet.

(ns murakumo.fleet.inventory
  (:require [clojure.string :as str]))

(defn node-port
  "Resolve a node's control HTTP port, defaulting to the fleet port, then 8077."
  [fleet node]
  (or (:port node) (:fleet/port fleet) 8077))

(defn node-health-url
  "Node-local health URL for the control HTTP port."
  [fleet node]
  (str "http://localhost:" (node-port fleet node) "/health"))

(defn select
  "Resolve a node selector string to node maps.

   nil or \"all\" selects every node; otherwise accepts a comma-separated list of
   node names. Unknown names are ignored, matching the original CLI behaviour."
  [fleet sel]
  (let [nodes (:nodes fleet)]
    (if (or (nil? sel) (= sel "all"))
      nodes
      (let [want (set (str/split sel #","))]
        (filter #(want (:name %)) nodes)))))

(defn node-named
  "Return the first node with `name`, or nil."
  [fleet name]
  (first (filter #(= name (:name %)) (:nodes fleet))))

(defn parse-tailscale-status
  "Parse `tailscale status` stdout into tailscale-name -> reachability metadata."
  [out]
  (into {}
        (for [line (str/split-lines (str out))
              :let [cols (str/split (str/trim line) #"\s+")]
              :when (>= (count cols) 4)]
          [(nth cols 1) {:ip (nth cols 0)
                         :online? (not (str/includes? line "offline"))}])))

(defn tailscale-status-result
  "Normalise a `tailscale status` process result into inventory metadata."
  [{:keys [exit out]}]
  (if (zero? exit)
    (parse-tailscale-status out)
    {}))

(defn enrich
  "Merge tailscale reachability metadata into fleet nodes."
  [fleet tailscale-by-name]
  (update fleet :nodes
          (fn [nodes]
            (mapv (fn [node] (merge node (get tailscale-by-name (:name node) {})))
                  nodes))))
