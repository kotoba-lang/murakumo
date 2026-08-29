(ns murakumo.desired-state-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kekkai.cacao :as cacao]
            [kekkai.desired-state :as desired]
            [murakumo.desired-state :as sut]))

(def nodes
  [{:name "a" :roles ["compute"] :labels {:zone "jp"}}
   {:name "b" :roles ["compute"] :labels {:zone "jp"}}
   {:name "c" :roles ["pin"] :labels {:zone "us"}}])

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "murakumo-desired-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- failure-type [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest placement-is-stable-and-fails-closed
  (let [app {:name "hello" :cid "bafyhello" :replicas 2
             :placement {:roles ["compute"] :labels {:zone "jp"}}}]
    (is (= (sut/assignments nodes app) (sut/assignments (reverse nodes) app)))
    (is (= #{"a" "b"} (set (sut/assignments nodes app))))
    (is (= :murakumo/missing-app-cid
           (failure-type #(sut/assignments nodes (dissoc app :cid)))))
    (is (= :murakumo/insufficient-eligible-nodes
           (failure-type #(sut/assignments nodes (assoc app :replicas 3)))))
    (is (= :murakumo/unverified-reach
           (failure-type #(sut/assignments nodes
                                           (assoc-in app [:placement :reach]
                                                     [:browser/live])))))))

(deftest publisher-embeds-manifests-and-refuses-source-builds
  (let [fleet {:fleet/name "test" :nodes nodes}
        reads {"/tmp/spec/murakumo.edn"
               (pr-str {:apps [{:name "hello" :manifest "hello.edn"
                                :cid "bafyhello" :replicas 1
                                :placement {:roles ["compute"]}}]})
               "/tmp/spec/hello.edn" (pr-str {:components [{:cid "bafyhello"}]})}
        payload (sut/prepare-payload fleet "/tmp/spec/murakumo.edn" reads)]
    (is (= sut/schema (:murakumo/schema payload)))
    (is (= "test" (:fleet/name payload)))
    (is (= "{:components [{:cid \"bafyhello\"}]}"
           (get-in payload [:apps 0 :manifest/text])))
    (is (= :murakumo/source-build-not-distributed
           (failure-type
            #(sut/prepare-payload
              fleet "/tmp/spec/murakumo.edn"
              (assoc reads "/tmp/spec/hello.edn"
                     (pr-str {:components [{:src "hello.clj"}]}))))))))

(deftest locally-applied-head-must-be-the-parent
  (is (= :murakumo/desired-chain-mismatch
         (failure-type
          #(sut/assert-extension! {:epoch 1 :desired-cid "bafyold"}
                                  {:desired/cid "bafynew"
                                   :desired/epoch 2
                                   :desired/previous-cid "bafyother"}))))
  (is (= "bafynew"
         (:desired/cid
          (sut/assert-extension! {:epoch 1 :desired-cid "bafyold"}
                                 {:desired/cid "bafynew"
                                  :desired/epoch 2
                                  :desired/previous-cid "bafyold"})))))

(deftest node-pulls-applies-locally-and-publishes-signed-receipt
  (let [operator (cacao/generate-identity)
        authority (desired/authority-spki-b64 operator)
        roots [(temp-dir) (temp-dir)]
        payload {:murakumo/schema sut/schema :fleet/name "test"
                 :apps [{:name "hello" :cid "bafyhello" :replicas 1
                         :assignments ["a"]
                         :manifest/text (pr-str {:components [{:cid "bafyhello"}]})}]}
        env (desired/seal {:kind sut/kind :subject sut/default-subject
                           :epoch 1 :previous-cid nil :payload payload}
                          operator)
        _ (desired/publish! roots 2 env authority)
        state-dir (temp-dir)
        calls (atom [])
        result (sut/reconcile! {:roots roots :authority-spki-b64 authority
                                :node "a" :state-dir (.getPath state-dir)
                                :node-identity-path (.getPath (java.io.File. state-dir "node.edn"))
                                :min-copies 2 :kotoba "/opt/kotoba" :wit-dir "/opt/wit"
                                :url "http://127.0.0.1:8077"
                                :run-fn (fn [argv]
                                          (swap! calls conj argv)
                                          {:exit 0 :out "ok" :err ""})
                                :now (constantly "2026-08-29T00:00:00Z")})
        state (edn/read-string (slurp (java.io.File. state-dir "state.edn")))
        receipt (desired/pull roots (str (:desired/cid env) "@a")
                              (:receipt-authority-spki-b64 result)
                              {:kind desired/receipt-kind})]
    (is (= :accepted (:status result)))
    (is (= 1 (count @calls)))
    (is (= ["/opt/kotoba" "app" "deploy"] (take 3 (first @calls))))
    (is (= (:desired/cid env)
           (get-in state [sut/default-subject :desired-cid])))
    (is (= :accepted (get-in receipt [:desired/payload :receipt/status])))
    (testing "the same desired CID is idempotent"
      (is (= :unchanged
             (:status (sut/reconcile! {:roots roots :authority-spki-b64 authority
                                       :node "a" :state-dir (.getPath state-dir)
                                       :node-identity-path (.getPath (java.io.File. state-dir "node.edn"))
                                       :min-copies 2 :kotoba "/opt/kotoba" :wit-dir "/opt/wit"
                                       :url "http://127.0.0.1:8077"
                                       :run-fn #(throw (ex-info "must not run" {:argv %}))})))))))

(deftest runtime-probe-is-required-for-an-applied-receipt
  (let [operator (cacao/generate-identity)
        authority (desired/authority-spki-b64 operator)
        root (temp-dir)
        payload {:murakumo/schema sut/schema :fleet/name "test"
                 :apps [{:name "hello" :cid "bafyhello" :assignments ["a"]
                         :manifest/text (pr-str {:components [{:cid "bafyhello"}]})
                         :probe {:method "POST" :path "/mesh/http/hello"
                                 :body "{}" :expect-status 200}}]}
        env (desired/seal {:kind sut/kind :subject sut/default-subject
                           :epoch 1 :previous-cid nil :payload payload} operator)
        _ (desired/publish! [root] 1 env authority)
        state-dir (temp-dir)
        calls (atom [])
        run-fn (fn [argv]
                 (swap! calls conj argv)
                 (if (= "curl" (first argv))
                   {:exit 0 :out "200" :err ""}
                   {:exit 0 :out "announced" :err ""}))
        result (sut/reconcile! {:roots [root] :authority-spki-b64 authority
                                :node "a" :state-dir (.getPath state-dir)
                                :node-identity-path (.getPath (java.io.File. state-dir "node.edn"))
                                :min-copies 1 :kotoba "/opt/kotoba" :wit-dir "/opt/wit"
                                :url "http://127.0.0.1:8077" :run-fn run-fn})]
    (is (= :applied (:status result)))
    (is (= true (get-in result [:results 0 :probe :ok?])))
    (is (= "curl" (first (second @calls))))))

(deftest failed-runtime-probe-does-not-write-a-receipt
  (let [operator (cacao/generate-identity)
        authority (desired/authority-spki-b64 operator)
        root (temp-dir)
        payload {:murakumo/schema sut/schema :fleet/name "test"
                 :apps [{:name "hello" :cid "bafyhello" :assignments ["a"]
                         :manifest/text "{}"
                         :probe {:path "/mesh/http/hello" :expect-status 200}}]}
        env (desired/seal {:kind sut/kind :subject sut/default-subject
                           :epoch 1 :previous-cid nil :payload payload} operator)
        _ (desired/publish! [root] 1 env authority)
        state-dir (temp-dir)]
    (is (= :murakumo/local-reconcile-failed
           (failure-type
            #(sut/reconcile! {:roots [root] :authority-spki-b64 authority
                              :node "a" :state-dir (.getPath state-dir)
                              :node-identity-path (.getPath (java.io.File. state-dir "node.edn"))
                              :min-copies 1 :kotoba "/opt/kotoba" :wit-dir "/opt/wit"
                              :url "http://127.0.0.1:8077"
                              :run-fn (fn [argv]
                                        (if (= "curl" (first argv))
                                          {:exit 0 :out "502" :err ""}
                                          {:exit 0 :out "ok" :err ""}))}))))
    (is (not (.exists (java.io.File. state-dir "state.edn"))))))
