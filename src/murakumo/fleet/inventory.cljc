;; murakumo.fleet.inventory — portable fleet inventory helpers.
;;
;; This is the .cljc source of truth for selector/defaulting logic and portable
;; parsing of host inventory command output. Shell execution stays in murakumo.fleet.
;;
;; W6 product-shell authority: pure port/url/selector/offline helpers DELEGATE
;; to precompiled kotoba/fleet_inventory_core.kotoba KIR on JVM. Vector folds
;; stay host.

(ns murakumo.fleet.inventory
  (:require [clojure.string :as str]
            #?(:clj [murakumo.kotoba.oracle :as oracle])))

(def ^:private oid :fleet-inventory)

#?(:clj
   (defn- o [export args]
     (oracle/call oid export args)))

(defn- mirror-node-port [fleet node]
  (or (:port node) (:fleet/port fleet) 8077))

(defn- mirror-health-url [port]
  (str "http://localhost:" port "/health"))

(defn node-port
  "Resolve a node's control HTTP port, defaulting to the fleet port, then 8077.
   JVM: kotoba `resolve-port` (has-node/has-fleet 0/1 sentinels)."
  [fleet node]
  #?(:clj
     (let [has-node (if (some? (:port node)) 1 0)
           has-fleet (if (some? (:fleet/port fleet)) 1 0)]
       (long (o 'resolve-port
                [has-node (long (or (:port node) 0))
                 has-fleet (long (or (:fleet/port fleet) 0))])))
     :cljs (mirror-node-port fleet node)))

(defn node-health-url
  "Node-local health URL for the control HTTP port.
   JVM: kotoba `health-url`."
  [fleet node]
  #?(:clj (o 'health-url [(long (node-port fleet node))])
     :cljs (mirror-health-url (node-port fleet node))))

(defn select
  "Resolve a node selector string to node maps.

   nil or \"all\" selects every node; otherwise accepts a comma-separated list of
   node names. Unknown names are ignored, matching the original CLI behaviour.
   JVM: selector-is-all? / selector-wants-name? via oracle."
  [fleet sel]
  (let [nodes (:nodes fleet)]
    #?(:clj
       (if (= 1 (o 'selector-is-all? [(str (or sel ""))]))
         nodes
         (filter #(= 1 (o 'selector-wants-name?
                          [(str sel) (str (:name %))]))
                 nodes))
       :cljs
       (if (or (nil? sel) (= sel "all"))
         nodes
         (let [want (set (str/split sel #","))]
           (filter #(want (:name %)) nodes))))))

(defn node-named
  "Return the first node with `name`, or nil."
  [fleet name]
  (first (filter #(= name (:name %)) (:nodes fleet))))

(defn parse-tailscale-status
  "Parse `tailscale status` stdout into tailscale-name -> reachability metadata.
   JVM: offline detection via kotoba `line-has-offline?`."
  [out]
  (into {}
        (for [line (str/split-lines (str out))
              :let [cols (str/split (str/trim line) #"\s+")]
              :when (>= (count cols) 4)]
          [(nth cols 1) {:ip (nth cols 0)
                         :online? #?(:clj (not= 1 (o 'line-has-offline? [(str line)]))
                                     :cljs (not (str/includes? line "offline")))}])))

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
