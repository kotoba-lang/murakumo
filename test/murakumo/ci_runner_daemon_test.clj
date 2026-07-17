(ns murakumo.ci-runner-daemon-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [murakumo.ci.runner-daemon :as daemon]))

(defn config [root]
  {:ci.runner/version 1 :ci.runner/rid "rid" :ci.runner/seed-env "SEED"
   :ci.runner/environment-digest "bafy-env" :ci.runner/capabilities #{:linux}
   :ci.runner/source-remotes {"o/r" "https://example.invalid/o/r.git"}
   :ci.runner/broker-request
   {:type "murakumo.overlay.adapter-request" :version 1 :transport :quic
    :session {:overlay "prod" :node "runner"}
    :connect {:host "broker" :port 4443}}
   :ci.runner/paths {:workspace-root (str root "/work")
                     :artifact-root (str root "/cas")}})

(deftest config-builds-runner-identity-from-env-seed
  (let [seed (byte-array (repeat 32 (byte 3)))
        root "/tmp/murakumo-runner-test"
        built (daemon/build (config root) {"SEED" (ed/hexify seed)})]
    (is (= (ed/did-key-from-seed seed) (:runner-id built)))
    (is (= #{:linux} (get-in built [:runner :runner/capabilities])))))

(deftest missing-or-malformed-seed-fails-closed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"seed unavailable"
                        (daemon/build (config "/tmp/x") (constantly "abcd")))))

(deftest checked-in-runner-example-is-valid
  (is (daemon/valid-config? (edn/read-string (slurp "examples/ci-runner.edn")))))
