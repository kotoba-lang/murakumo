(ns murakumo.component-authority-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.component-authority :as authority]))

(deftest placement-and-revocation-form-a-monotonic-fence
  (let [state (atom (authority/initial-state))
        events (atom [])
        publish! #(swap! events conj %)
        cid "bafyreicomponent"]
    (authority/apply-command!
     state publish! {:op :place :component-cid cid :node "edge-a"})
    (authority/apply-command!
     state publish! {:op :place :component-cid cid :node "edge-b"})
    (is (= 1 ((authority/epoch-source state cid))))
    (is (= #{"edge-a" "edge-b"} (get-in @state [:placements cid])))
    (let [revoked (authority/apply-command!
                   state publish! {:op :revoke :component-cid cid})]
      (is (= :revoked (:murakumo.component/event revoked)))
      (is (= 2 (:murakumo.component/epoch revoked)))
      (is (= 2 ((authority/epoch-source state cid))))
      (is (nil? (get-in @state [:placements cid]))))
    (is (= [1 2 3] (mapv :murakumo.component/sequence @events)))
    (is (every? authority/valid-event? @events))))

(deftest revocation-fences-partitioned-hosts-without-observed-placement
  (let [[state event] (authority/revoke (authority/initial-state) "bafyreistale")]
    (is (= 1 (authority/current-epoch state "bafyreistale")))
    (is (= :revoked (:murakumo.component/event event)))
    (is (authority/valid-event? event))))

(deftest authority-boundaries-fail-closed
  (testing "missing epochs are not defaulted"
    (is (thrown? clojure.lang.ExceptionInfo
                 ((authority/epoch-source
                   (atom (authority/initial-state)) "bafyreimissing")))))
  (testing "unknown commands do not publish"
    (let [published (atom [])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (authority/apply-command!
                    (atom (authority/initial-state))
                    #(swap! published conj %)
                    {:op :grant-ambient :component-cid "bafyrei"})))
      (is (empty? @published)))))
