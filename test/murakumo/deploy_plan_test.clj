;; murakumo.deploy-plan-test — offline tests for deploy command planning.

(ns murakumo.deploy-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.deploy.plan :as plan]))

(def manifest-text
  "{:components [{:name \"bot\" :src \"src/bot.clj\" :cid \"bafyOLD\"}]}")

(def component-manifest
  {:format :murakumo.kototama-component/v1
   :name "cloud-itonami-assoc-6419-nga-cibn-canary"
   :source "qualification/resident_canary.kotoba"
   :target :wasm-component-kotoba-v1
   :expected-result 6419002
   :ambient-wasi false
   :budgets {:fuel 512 :memory-pages 16 :deadline-ms 10000}
   :placement {:replicas 1 :labels {:role "canary"}}})

(deftest kototama-component-deployment-is-exact-and-ambient-free
  (let [deployment
        (plan/component-deployment-plan
         "apps/cibn/murakumo.component.edn"
         component-manifest
         "/opt/kotoba/compiler/bin/kotoba")]
    (is (= :murakumo.component-deployment-plan/v1 (:format deployment)))
    (is (= "apps/cibn/qualification/resident_canary.kotoba"
           (:source deployment)))
    (is (false? (:ambient-wasi deployment)))
    (is (= ["/opt/kotoba/compiler/bin/kotoba" "-M" "compile"
            "apps/cibn/qualification/resident_canary.kotoba"
            "--target" "component"
            "--output" "/tmp/murakumo-deploy.component.wasm"
            "--fuel" "512"
            "--memory-pages" "16"]
           (:compile-argv deployment)))
    (is (= {:component "/tmp/murakumo-deploy.component.wasm"
            :wit "/tmp/murakumo-deploy.component.wasm.wit"
            :admission "/tmp/murakumo-deploy.component.wasm.admission.edn"
            :provenance "/tmp/murakumo-deploy.component.wasm.provenance.edn"}
           (:bundle deployment)))
    (is (= {:admission "/tmp/murakumo-deploy.component.wasm.admission.edn"
            :provenance "/tmp/murakumo-deploy.component.wasm.provenance.edn"}
           (plan/missing-component-bundle-files
            deployment
            #{"/tmp/murakumo-deploy.component.wasm"
              "/tmp/murakumo-deploy.component.wasm.wit"})))))

(deftest kototama-component-manifest-fails-closed
  (doseq [invalid [(assoc component-manifest :ambient-wasi true)
                   (assoc component-manifest :target :wasm32-wasi-kotoba-v1)
                   (assoc-in component-manifest [:budgets :memory-pages] 0)
                   (assoc component-manifest :extra :not-admitted)
                   (assoc-in component-manifest [:placement :labels] {"role" "canary"})]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"invalid Kototama Component deployment manifest"
         (plan/validate-component-manifest! invalid)))))

(deftest manifest-input-parsing-preserves-current-behaviour
  (is (= "apps" (plan/manifest-dir "apps/bot.edn")))
  (is (= "." (plan/manifest-dir "bot.edn"))
      "a bare filename resolves to \".\" so app paths join as ./<manifest> —
       the old return-unchanged behaviour broke reconcile --apply
       (murakumo.app.edn/../kenchi/… , ADR-2607071500 追記2)")
  (is (= "apps/heartbeat.edn"
         (plan/app-manifest-path "apps" {:manifest "heartbeat.edn"})))
  (is (= "asher" (plan/publish-selector nil)))
  (is (= "judah" (plan/publish-selector "judah")))
  (is (= "src/bot.clj" (plan/manifest-src manifest-text)))
  (is (= "bafyOLD" (plan/manifest-cid manifest-text)))
  (is (= {:manifest "apps/bot.edn"
          :manifest-text manifest-text}
         (plan/deployment-input "apps/bot.edn" manifest-text)))
  (is (= {:manifest "apps/bot.edn"
          :manifest-dir "apps"
          :src "src/bot.clj"
          :src-path "apps/src/bot.clj"
          :explicit-cid "bafyOLD"
          :wasm "/tmp/murakumo-deploy.wasm"
          :needs-build? true
          :cid "bafyOLD"
          :publish-node "asher"}
         (plan/deployment-plan "apps/bot.edn" manifest-text))))

(deftest explicit-cid-manifest-does-not-build
  (let [p (plan/deployment-plan "apps/prebuilt.edn" "{:cid \"bafyREADY\"}")]
    (is (= :missing-manifest (plan/deploy-command-error nil "seed")))
    (is (= :missing-operator-seed (plan/deploy-command-error "apps/prebuilt.edn" nil)))
    (is (nil? (plan/deploy-command-error "apps/prebuilt.edn" "seed")))
    (is (false? (:needs-build? p)))
    (is (= "bafyREADY" (:cid p)))
    (is (nil? (:src-path p)))
    (is (= "bafyREADY" (plan/deployment-cid p nil)))))

(deftest argv-shapes-are-stable
  (is (= 18900 plan/artifact-forward-port))
  (is (= 18077 plan/publish-forward-port))
  (is (= 1300 plan/forward-settle-ms))
  (is (= 75000 plan/placement-wait-ms))
  (is (= ["/bin/kotoba" "component" "build" "apps/src/bot.clj" "--wit-dir" "wit" "-o" "/tmp/out.wasm"]
         (plan/component-build-argv "/bin/kotoba" "apps/src/bot.clj" "wit" "/tmp/out.wasm")))
  (is (= ["/bin/kotoba" "app" "deploy" "apps/bot.edn" "--wit-dir" "wit" "--publish" "--url" "http://localhost:18077"]
         (plan/app-deploy-argv "/bin/kotoba" "apps/bot.edn" "wit" 18077)))
  (is (= ["/bin/kotoba" "--url" "http://localhost:18900" "--token" "tok" "block" "put" "--file" "/tmp/out.wasm"]
         (plan/block-put-argv "/bin/kotoba" "tok" "/tmp/out.wasm" 18900))))

(deftest artifact-distribution-plan-is-stable
  (let [fleet {:fleet/port 8077}
        nodes [{:name "asher" :host "asher"}
               {:name "judah" :host "judah" :port 9000}]]
    (is (= {:node {:name "asher" :host "asher"}
            :host "asher"
            :remote-port 8077
            :local-port 18900}
           (plan/artifact-node-plan fleet (first nodes))))
    (is (= [{:node {:name "asher" :host "asher"}
             :host "asher"
             :remote-port 8077
             :local-port 18900}
            {:node {:name "judah" :host "judah" :port 9000}
             :host "judah"
             :remote-port 9000
             :local-port 18900}]
           (plan/artifact-distribution-plan fleet nodes)))
    (is (= [{:node {:name "asher" :host "asher"}
             :host "asher"
             :remote-port 8077
             :local-port 18900}]
           (plan/reachable-artifact-distribution-plan fleet nodes #{"asher"})))))

(deftest component-cid-comes-from-last-output-line
  (is (= "bafyCID" (plan/last-output-line "building\nbafyCID\n")))
  (is (= "abc123" (plan/command-output " abc123\n")))
  (is (= "bafyCID"
         (plan/deployment-cid
          (plan/deployment-plan "apps/bot.edn" manifest-text)
          "building\nbafyCID\n"))))

(deftest execution-count-parsing-is-stable
  (is (true? (plan/execution-observed? "1\n")))
  (is (false? (plan/execution-observed? "0\n")))
  (is (false? (plan/execution-observed? "")))
  (is (= "asher" (plan/observed-node {:name "asher"} "2\n")))
  (is (nil? (plan/observed-node {:name "asher"} "0\n")))
  (is (= ["asher" "levi"]
         (plan/observed-nodes [[{:name "asher"} "2\n"]
                               [{:name "judah"} "0\n"]
                               [{:name "levi"} "1\n"]])))
  (is (= "grep -c 'trigger: executed.*bafyCID' ~/.murakumo/mesh.log 2>/dev/null"
         (plan/execution-count-command "bafyCID")))
  (is (= {:node {:name "asher" :host "asher"}
          :host "asher"
          :command "grep -c 'trigger: executed.*bafyCID' ~/.murakumo/mesh.log 2>/dev/null"}
         (plan/placement-probe-plan "bafyCID" {:name "asher" :host "asher"})))
  (is (= [{:node {:name "asher" :host "asher"}
           :host "asher"
           :command "grep -c 'trigger: executed.*bafyCID' ~/.murakumo/mesh.log 2>/dev/null"}]
         (plan/placement-probe-plans "bafyCID" [{:name "asher" :host "asher"}])))
  (is (= [[{:name "asher" :host "asher"} "1\n"]]
         (plan/placement-probe-results
          "bafyCID"
          [{:name "asher" :host "asher"}]
          (fn [host command]
            (when (and (= "asher" host)
                       (= "grep -c 'trigger: executed.*bafyCID' ~/.murakumo/mesh.log 2>/dev/null" command))
              "1\n")))))
  (is (= "pkill -f '18077:localhost' 2>/dev/null"
         (plan/stop-forward-command 18077))))

(deftest pin-build-manifest-is-data
  (is (= "release/../../../crates/kotoba-runtime/wit"
         (plan/release-wit-path "release")))
  (is (= "release" (plan/pin-source "release" "/kotoba")))
  (is (= "/kotoba/target/aarch64-apple-darwin/release"
         (plan/pin-source nil "/kotoba")))
  (is (= {:src "release"
          :dest "bin"
          :binaries [{:name "kotoba"
                      :src "release/kotoba"
                      :dest "bin/kotoba"}
                     {:name "kotoba-server"
                      :src "release/kotoba-server"
                      :dest "bin/kotoba-server"}]
          :wit {:src "release/../../../crates/kotoba-runtime/wit"
                :dest "bin/wit"}}
         (plan/pin-copy-plan "release" "bin")))
  (is (= ["cp" "release/kotoba" "bin/kotoba"]
         (plan/copy-argv "release/kotoba" "bin/kotoba")))
  (is (= [["cp" "release/kotoba" "bin/kotoba"]
          ["cp" "release/kotoba-server" "bin/kotoba-server"]]
         (plan/pin-binary-copy-argvs (plan/pin-copy-plan "release" "bin"))))
  (is (= ["rm" "-rf" "bin/wit"]
         (plan/remove-tree-argv "bin/wit")))
  (is (= ["cp" "-R" "release/wit" "bin/wit"]
         (plan/copy-tree-argv "release/wit" "bin/wit")))
  (is (= [["rm" "-rf" "bin/wit"]
          ["cp" "-R" "release/../../../crates/kotoba-runtime/wit" "bin/wit"]]
         (plan/pin-wit-argvs (plan/pin-copy-plan "release" "bin") true)))
  (is (= [] (plan/pin-wit-argvs (plan/pin-copy-plan "release" "bin") false)))
  (is (= [{:name "kotoba-server"
           :src "release/kotoba-server"
           :dest "bin/kotoba-server"}]
         (plan/missing-pin-binaries
          (plan/pin-copy-plan "release" "bin")
          #{"release/kotoba"})))
  (is (= ["git" "-C" "release" "rev-parse" "--short" "HEAD"]
         (plan/git-short-sha-argv "release")))
  (is (= ["bin/kotoba" "--version"]
         (plan/version-argv "bin")))
  (is (= {:source "release"
          :git-sha "abc123"
          :version "kotoba 0.1.0"
          :features "p2p,realtime-wasm,webrtc"}
         (plan/build-manifest "release" "abc123" "kotoba 0.1.0")))
  (is (true? (plan/missing-pinned-binaries?
              (plan/build-manifest "release" "abc123" "kotoba 0.1.0")
              false)))
  (is (false? (plan/missing-pinned-binaries?
               (plan/build-manifest "release" "abc123" "kotoba 0.1.0")
               true)))
  (is (false? (plan/missing-pinned-binaries? nil false))))
