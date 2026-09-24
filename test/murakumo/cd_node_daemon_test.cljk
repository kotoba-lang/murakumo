(ns murakumo.cd-node-daemon-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [murakumo.cd.node-daemon :as daemon]))

(defn config [root]
  {:cd.node/version 1 :cd.node/node-id "node-a" :cd.node/rid "rid"
   :cd.node/issuers #{"did:key:zIssuer"}
   :cd.node/overlay-request
   {:type "murakumo.overlay.adapter-request" :version 1 :transport :quic
    :session {:overlay "prod" :node "node-a"}
    :connect {:host "0.0.0.0" :port 4433}}
   :cd.node/paths {:artifact-root (str root "/artifacts")
                   :transfer-temp (str root "/transfers")
                   :releases-root (str root "/releases")
                   :active-state (str root "/active.edn")}
   :cd.node/kotoba {:binary "/opt/kotoba" :token-env "TOKEN"
                    :url "http://127.0.0.1:8077" :wit-dir "/opt/wit"
                    :health-url "http://127.0.0.1:8077/health"}})

(deftest production-config-builds-one-multiplexed-handler
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-node-daemon"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        built (daemon/build (config root) {"TOKEN" "secret"})]
    (is (= {:host "0.0.0.0" :port 4433}
           (get-in built [:overlay-request :connect])))
    (is (fn? (:handler built)))))

(deftest config-never-accepts-inline-token-and-requires-env-value
  (let [root "/tmp/murakumo-node"
        cfg (config root)]
    (is (false? (daemon/valid-config? (assoc-in cfg [:cd.node/kotoba :token] "leak"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token is unavailable"
                          (daemon/build cfg (constantly nil))))))

(deftest checked-in-example-is-structurally-valid
  (let [example (edn/read-string (slurp "examples/cd-node.edn"))]
    (is (daemon/valid-config? example))))
