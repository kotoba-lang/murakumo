(ns murakumo.cd-overlay-test
  (:require [clojure.test :refer [deftest is testing]]
            [ed25519.core :as ed]
            [murakumo.cd.capability :as capability]
            [murakumo.cd.executor :as executor]
            [murakumo.cd.fleet-adapter :as fleet-adapter]
            [murakumo.cd.protocol :as protocol]
            [murakumo.cd.remote :as remote]
            [murakumo.cd.rollout :as rollout]))

(def seed (byte-array (repeat 32 (byte 7))))
(def issuer (ed/did-key-from-seed seed))
(def issued
  (capability/issue seed "rid-ci"
                    {:verdict-cid "verdict" :artifact-cid "artifact"
                     :previous-artifact-cid "artifact-v1"
                     :environment "production" :revision "v2"
                     :previous-revision "v1" :now 100 :ttl 100 :nonce "overlay"}))
(def policy {:rid "rid-ci" :issuers #{issuer} :environment "production"
             :artifact-cid "artifact" :verdict-cid "verdict"
             :previous-artifact-cid "artifact-v1"
             :revision "v2" :previous-revision "v1"})

(defn node-handler [node calls health-ok?]
  (remote/handler
   {:node-id node :rid "rid-ci" :issuers #{issuer} :clock-fn (constantly 110)
    :deploy-fn (fn [x] (swap! calls conj [:deploy node (:revision x) (:artifact-cid x)]) {:ok? true})
    :health-fn (fn [x] (swap! calls conj [:health node (:revision x) (:artifact-cid x)])
                 {:ok? (health-ok? node)})
    :rollback-fn (fn [x] (swap! calls conj [:rollback node (:revision x) (:artifact-cid x)]) {:ok? true})}))

(defn loopback-adapter [handlers]
  (fleet-adapter/create-overlay-adapter
   {:node-lookup (fn [node] {:host node :port 4433})
    :session {:overlay "prod" :node "controller"}
    :issued-capability issued
    :request-fn (fn [overlay-request payload _timeout]
                  {:ok? true
                   :response ((get handlers (get-in overlay-request [:connect :host])) payload)})}))

(deftest remote-node-enforces-local-capability-scope
  (let [calls (atom [])
        handler (node-handler "node-a" calls (constantly true))
        base (protocol/request :deploy "node-a" issued "artifact"
                               "production" "verdict" "v2")]
    (is (= {:ok? true}
           (:murakumo.cd/result (handler base))))
    (testing "controller cannot substitute a target revision"
      (is (= :revision-out-of-scope
             (get-in (handler (assoc base :murakumo.cd/revision "evil"))
                     [:murakumo.cd/result :reason]))))
    (testing "controller cannot substitute an operation artifact"
      (is (= :artifact-out-of-scope
             (get-in (handler (assoc base :murakumo.cd/artifact-cid "evil"))
                     [:murakumo.cd/result :reason]))))
    (testing "request cannot be replayed against another node identity"
      (is (= :wrong-node
             (get-in (handler (assoc base :murakumo.cd/node "node-b"))
                     [:murakumo.cd/result :reason]))))
    (testing "expired capability is rejected at the remote boundary"
      (let [expired-handler (remote/handler
                             {:node-id "node-a" :rid "rid-ci" :issuers #{issuer}
                              :clock-fn (constantly 201)
                              :deploy-fn (constantly {:ok? true})
                              :health-fn (constantly {:ok? true})
                              :rollback-fn (constantly {:ok? true})})]
        (is (= :unauthorized
               (get-in (expired-handler base) [:murakumo.cd/result :reason])))))
    (is (= [[:deploy "node-a" "v2" "artifact"]] @calls))))

(deftest executor-crosses-overlay-boundary-and-rolls-back-remotely
  (let [calls (atom [])
        handlers (into {} (map (fn [node]
                                 [node (node-handler node calls #(not= % "node-b"))])
                               ["canary" "node-b" "node-c"]))
        adapter (loopback-adapter handlers)
        plan (rollout/plan {:capability-cid (:capability-cid issued)
                            :artifact-cid "artifact" :environment "production"
                            :previous-artifact-cid "artifact-v1"
                            :revision "v2" :previous-revision "v1"
                            :targets ["canary" "node-b" "node-c"] :batch-size 2})
        result (executor/execute!
                (merge adapter
                       {:issued-capability issued :verification-policy policy
                        :clock-fn (constantly 110) :rollout-plan plan}))]
    (is (= :rolled-back (get-in result [:rollout :cd.rollout/state])))
    (is (= [[:deploy "canary" "v2" "artifact"]
            [:health "canary" "v2" "artifact"]
            [:deploy "node-b" "v2" "artifact"]
            [:deploy "node-c" "v2" "artifact"]
            [:health "node-b" "v2" "artifact"]
            [:health "node-c" "v2" "artifact"]
            [:rollback "node-c" "v1" "artifact-v1"]
            [:rollback "node-b" "v1" "artifact-v1"]
            [:rollback "canary" "v1" "artifact-v1"]]
           @calls))))

(deftest adapter-fails-closed-on-routing-and-wire-errors
  (let [missing (fleet-adapter/create-overlay-adapter
                 {:node-lookup (constantly nil) :issued-capability issued})
        invalid (fleet-adapter/create-overlay-adapter
                 {:node-lookup (constantly {:host "x" :port 1})
                  :issued-capability issued
                  :request-fn (fn [& _] {:ok? true :response {:unexpected true}})})]
    (is (= :node-not-found (:reason ((:deploy-fn missing) {:node "x" :revision "v2"}))))
    (is (= :transport-failed (:reason ((:health-fn invalid) {:node "x" :revision "v2"}))))))

(deftest deploy-rpc-never-runs-when-artifact-staging-fails
  (let [rpc-called? (atom false)
        adapter (fleet-adapter/create-overlay-adapter
                 {:node-lookup (constantly {:host "node" :port 1})
                  :issued-capability issued
                  :stage-fn (fn [request]
                              (is (= {:node "node" :artifact-cid "artifact"
                                      :revision "v2" :action :deploy}
                                     request))
                              {:ok? false :reason :missing-object})
                  :request-fn (fn [& _] (reset! rpc-called? true) {:ok? true})})
        result ((:deploy-fn adapter)
                {:node "node" :artifact-cid "artifact" :revision "v2"})]
    (is (= :artifact-staging-failed (:reason result)))
    (is (= :missing-object (:staging-reason result)))
    (is (false? @rpc-called?))))
