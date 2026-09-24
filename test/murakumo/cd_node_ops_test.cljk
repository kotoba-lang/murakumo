(ns murakumo.cd-node-ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [ed25519.core :as ed]
            [murakumo.cd.capability :as capability]
            [murakumo.cd.node-ops :as node-ops]
            [murakumo.cd.protocol :as protocol]
            [murakumo.cd.service :as service]))

(defn temp-state []
  (str (java.nio.file.Files/createTempDirectory
        "murakumo-cd-node-ops"
        (make-array java.nio.file.attribute.FileAttribute 0))
       "/active.edn"))

(defn successful-exec [calls]
  (fn [command]
    (swap! calls conj command)
    {:exit 0 :duration-ms 1 :stdout-digest "out" :stderr-digest "err"}))

(deftest argv-only-activation-health-and-rollback
  (let [state-file (temp-state)
        calls (atom [])
        ops (node-ops/operation-set
             {:state-file state-file :clock-fn (constantly 123)
              :exec-fn (successful-exec calls)
              :deploy-argv-fn (fn [{:keys [artifact-cid revision]}]
                                ["releasectl" "activate" artifact-cid revision])
              :health-argv-fn (fn [{:keys [revision]}]
                                ["releasectl" "health" revision])
              :rollback-argv-fn (fn [{:keys [artifact-cid revision]}]
                                  ["releasectl" "activate" artifact-cid revision])})
        v2 {:node "node-a" :artifact-cid "artifact-v2"
            :environment "prod" :revision "v2"}
        v1 (assoc v2 :artifact-cid "artifact-v1" :revision "v1")]
    (is (:ok? ((:deploy-fn ops) v2)))
    (is (= "artifact-v2" (:cd.active/artifact-cid (node-ops/read-active state-file))))
    (is (:ok? ((:health-fn ops) v2)))
    (is (:ok? ((:rollback-fn ops) v1)))
    (is (= "v1" (:cd.active/revision (node-ops/read-active state-file))))
    (is (= [["releasectl" "activate" "artifact-v2" "v2"]
            ["releasectl" "health" "v2"]
            ["releasectl" "activate" "artifact-v1" "v1"]]
           (mapv :argv @calls)))))

(deftest failures-never-advance-durable-active-state
  (let [state-file (temp-state)
        responses (atom [0 9])
        exec-fn (fn [_]
                  (let [exit (first @responses)]
                    (swap! responses rest)
                    {:exit exit :duration-ms 1 :stdout-digest "out" :stderr-digest "err"}))
        ops (node-ops/operation-set
             {:state-file state-file :exec-fn exec-fn
              :deploy-argv-fn (constantly ["deploy"])
              :health-argv-fn (constantly ["health"])
              :rollback-argv-fn (constantly ["rollback"])})
        v1 {:node "n" :artifact-cid "a1" :environment "prod" :revision "v1"}
        v2 (assoc v1 :artifact-cid "a2" :revision "v2")]
    (is (:ok? ((:deploy-fn ops) v1)))
    (is (false? (:ok? ((:deploy-fn ops) v2))))
    (is (= "v1" (:cd.active/revision (node-ops/read-active state-file))))
    (is (= :active-release-mismatch (:reason ((:health-fn ops) v2))))))

(deftest invalid-local-argv-is-rejected-without-execution
  (let [called? (atom false)
        ops (node-ops/operation-set
             {:state-file (temp-state)
              :exec-fn (fn [_] (reset! called? true) {:exit 0})
              :deploy-argv-fn (constantly ["sh" "-c" ""])
              :health-argv-fn (constantly ["health"])
              :rollback-argv-fn (constantly ["rollback"])})]
    (is (= :invalid-local-argv
           (:reason ((:deploy-fn ops)
                     {:node "n" :artifact-cid "a" :environment "prod" :revision "v"}))))
    (is (false? @called?))))

(deftest signed-remote-request-reaches-direct-node-operation
  (let [seed (byte-array (repeat 32 (byte 4)))
        issued (capability/issue
                seed "rid" {:verdict-cid "verdict" :artifact-cid "a2"
                            :previous-artifact-cid "a1" :environment "prod"
                            :revision "v2" :previous-revision "v1"
                            :now 10 :ttl 10 :nonce "service"})
        commands (atom [])
        handler (service/create-handler
                 {:node-id "node-a" :rid "rid"
                  :issuers #{(ed/did-key-from-seed seed)} :clock-fn (constantly 11)}
                 {:state-file (temp-state) :clock-fn (constantly 12)
                  :exec-fn (successful-exec commands)
                  :deploy-argv-fn #(vector "release" "activate" (:artifact-cid %))
                  :health-argv-fn #(vector "release" "health" (:revision %))
                  :rollback-argv-fn #(vector "release" "activate" (:artifact-cid %))})]
    (is (:ok? (:murakumo.cd/result
               (handler (protocol/request :deploy "node-a" issued "a2"
                                          "prod" "verdict" "v2")))))
    (is (:ok? (:murakumo.cd/result
               (handler (protocol/request :health "node-a" issued "a2"
                                          "prod" "verdict" "v2")))))
    (is (:ok? (:murakumo.cd/result
               (handler (protocol/request :rollback "node-a" issued "a1"
                                          "prod" "verdict" "v1")))))
    (is (= [["release" "activate" "a2"]
            ["release" "health" "v2"]
            ["release" "activate" "a1"]]
           (mapv :argv @commands)))))

(deftest multi-command-plans-are-sequential-and-fail-fast
  (let [calls (atom [])
        state-file (temp-state)
        ops (node-ops/operation-set
             {:state-file state-file
              :exec-fn (fn [{:keys [argv]}]
                         (swap! calls conj argv)
                         {:exit (if (= ["second"] argv) 7 0)})
              :deploy-argv-fn (constantly [["first"] ["second"] ["never"]])
              :health-argv-fn (constantly ["health"])
              :rollback-argv-fn (constantly ["rollback"])})
        result ((:deploy-fn ops)
                {:node "n" :artifact-cid "a" :environment "prod" :revision "v"})]
    (is (false? (:ok? result)))
    (is (= [["first"] ["second"]] @calls))
    (is (nil? (node-ops/read-active state-file)))))
