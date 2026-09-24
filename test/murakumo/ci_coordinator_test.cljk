(ns murakumo.ci-coordinator-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [murakumo.ci.coordinator :as coordinator]))

(defn config [root]
  {:ci.coordinator/version 1 :ci.coordinator/store-root root
   :ci.coordinator/pipeline-digest "bafy-pipeline"
   :ci.coordinator/rid "rid" :ci.coordinator/replicas 2 :ci.coordinator/threshold 2
   :ci.coordinator/runner-signers #{"did:key:zRunnerA" "did:key:zRunnerB"}
   :ci.coordinator/github-secret-env "GITHUB_SECRET"
   :ci.coordinator/artifact-root (str root "/artifacts")
   :ci.coordinator/artifact-transfer-temp (str root "/transfers")
   :ci.coordinator/http-port 8791
   :ci.coordinator/radicle-signers {"rad:zRepo" #{"did:key:zSigner"}}
   :ci.coordinator/overlay-request
   {:type "murakumo.overlay.adapter-request" :version 1 :transport :quic
    :session {:overlay "prod" :node "coordinator"}
    :connect {:host "0.0.0.0" :port 4443}}})

(deftest config-composes-durable-broker-and-both-ingress-verifiers
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-coordinator"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        built (coordinator/build (config root) {"GITHUB_SECRET" "secret"})]
    (is (fn? (get-in built [:broker :handler])))
    (is (fn? (get-in built [:http-options :submit!])))
    (is (fn? (get-in built [:http-options :verify-radicle])))
    (is (= 4443 (get-in built [:overlay-request :connect :port])))))

(deftest coordinator-requires-secret-from-environment
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"secret unavailable"
                        (coordinator/build (config "/tmp/store") (constantly nil)))))

(deftest checked-in-coordinator-example-is-valid
  (is (coordinator/valid-config?
       (edn/read-string (slurp "examples/ci-coordinator.edn")))))

(deftest github-status-configuration-is-atomic
  (is (false? (coordinator/valid-config?
               (assoc (config "/tmp/store")
                      :ci.coordinator/github-status-token-env "TOKEN"))))
  (is (coordinator/valid-config?
       (assoc (config "/tmp/store")
              :ci.coordinator/github-status-token-env "TOKEN"
              :ci.coordinator/public-base-url "https://murakumo.cloud"))))

(deftest deployment-policy-requires-valid-shape-and-environment-seed
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-coordinator-cd"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        policy {:environment "prod" :deploy-refs #{"refs/heads/main"}
                :targets ["canary"]
                :nodes {"canary" {:host "canary.internal" :port 4433}}
                :previous-artifact-cid "bafyabc" :previous-revision "rev1"
                :issuer-seed-env "CD_SEED" :capability-ttl-seconds 60
                :session {:overlay "prod" :node "coordinator"}}
        configured (assoc (config root) :ci.coordinator/deployments {"o/r" policy})
        seed-hex (apply str (repeat 32 "01"))]
    (is (coordinator/valid-config? configured))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"issuer seed unavailable"
                          (coordinator/build configured {"GITHUB_SECRET" "secret"})))
    (is (map? (coordinator/build configured
                                 {"GITHUB_SECRET" "secret" "CD_SEED" seed-hex})))))
