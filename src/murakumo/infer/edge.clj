(ns murakumo.infer.edge
  "Resident launchd plans for the Murakumo edge replica and queue worker."
  (:require [clojure.string :as str]
            [kotodama.inference.edge :as inference-edge]))

(def model-id "murakumo-edge")
(def server-label "com.murakumo.edge-server")
(def join-label "com.murakumo.edge-join")
(def port 8092)

(defn- xml [value]
  (-> (str value) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- plist [label argv stdout stderr]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\"><dict>\n"
       "<key>Label</key><string>" (xml label) "</string>\n"
       "<key>ProgramArguments</key><array>"
       (apply str (map #(str "<string>" (xml %) "</string>") argv))
       "</array>\n<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>\n"
       "<key>ThrottleInterval</key><integer>5</integer>\n"
       "<key>StandardOutPath</key><string>" (xml stdout) "</string>\n"
       "<key>StandardErrorPath</key><string>" (xml stderr) "</string>\n"
       "</dict></plist>\n"))

(defn server-plan
  [{:keys [home llama-server memory-bytes]}]
  (let [plan (inference-edge/replica-plan
              {:home home :llama-server llama-server :port port
               :memory-bytes memory-bytes})]
    (when-not (:admitted? plan)
      (throw (ex-info "murakumo-edge does not fit this node" plan)))
    (assoc plan :label server-label
           :plist (plist server-label (:argv plan)
                         (str home "/.murakumo/edge/server.log")
                         (str home "/.murakumo/edge/server.err.log")))))

(defn join-plan
  [{:keys [home nbb node-name]}]
  (let [root (str home "/.murakumo/edge/murakumo")
        command (str "set -a; source " home "/.murakumo/edge/join.env; set +a; exec "
                     nbb " --classpath " root "/src " root
                     "/scripts/infer-join.cljs --model " model-id
                     " --base https://api.murakumo.cloud --name " node-name
                     " --local-url http://127.0.0.1:" port "/v1 --slots 1 --poll-ms 1000")]
    {:label join-label
     :plist (plist join-label ["/bin/zsh" "-lc" command]
                   (str home "/.murakumo/edge/join.log")
                   (str home "/.murakumo/edge/join.err.log"))}))
