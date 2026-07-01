;; murakumo.config-test — offline tests for portable config/path resolution.

(ns murakumo.config-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.config :as config]))

(deftest kotoba-dir-resolution-is-stable
  (is (= "fleet.edn" config/default-fleet-path))
  (is (= "connect.edn" config/default-connect-path))
  (is (= "cloud.edn" config/default-cloud-path))
  (is (= {:a 1} (config/parse-edn "{:a 1}")))
  (is (= "{:a 1}" (config/edn-string {:a 1})))
  (is (= ::fallback (config/read-edn-file-or "/definitely/missing.edn" ::fallback)))
  (is (= "/Users/ops/github/com-junkawasaki/orgs/com-junkawasaki/kotoba"
         (config/default-kotoba-dir "/Users/ops")))
  (is (= "/custom/kotoba"
         (config/kotoba-dir {"MURAKUMO_KOTOBA_DIR" "/custom/kotoba"
                             "HOME" "/Users/ops"})))
  (is (= "/Users/ops/github/com-junkawasaki/orgs/com-junkawasaki/kotoba"
         (config/kotoba-dir {"HOME" "/Users/ops"}))))

(deftest operator-seed-resolution-is-stable
  (let [fleet {:fleet/operator-seed-env "FLEET_SEED"}]
    (is (= ["FLEET_SEED" "MURAKUMO_OPERATOR_SEED"]
           (config/operator-seed-env-keys fleet)))
    (is (= {"FLEET_SEED" "fleet"}
           (config/env-values {"FLEET_SEED" "fleet"} ["FLEET_SEED"])))
    (is (= {"FLEET_SEED" "fleet"
            "MURAKUMO_OPERATOR_SEED" "default"}
           (config/operator-seed-env {"FLEET_SEED" "fleet"
                                      "MURAKUMO_OPERATOR_SEED" "default"
                                      "IGNORED" "x"}
                                     fleet)))
    (is (= ["MURAKUMO_OPERATOR_SEED"]
           (config/operator-seed-env-keys {})))
    (is (= "fleet"
           (config/operator-seed {"FLEET_SEED" "fleet"
                                  "MURAKUMO_OPERATOR_SEED" "default"}
                                 fleet)))
    (is (= "default"
           (config/operator-seed {"MURAKUMO_OPERATOR_SEED" "default"} fleet)))
    (is (= "fleet"
           (config/operator-seed-from-getenv {"FLEET_SEED" "fleet"
                                              "MURAKUMO_OPERATOR_SEED" "default"}
                                             fleet)))
    (is (nil? (config/operator-seed {} fleet)))))

(deftest binary-path-resolution-is-stable
  (is (= "/work/murakumo/bin" (config/pinned-bin-dir "/work/murakumo")))
  (is (= "/kotoba/target/aarch64-apple-darwin/release"
         (config/release-bin-dir "/kotoba")))
  (is (= "/work/murakumo/bin"
         (config/resolve-local-bin {} "/work/murakumo" "/kotoba" true)))
  (is (= "/custom/bin"
         (config/resolve-local-bin {"MURAKUMO_BIN" "/custom/bin"} "/work/murakumo" "/kotoba" false)))
  (is (= "/kotoba/target/aarch64-apple-darwin/release"
         (config/resolve-local-bin {} "/work/murakumo" "/kotoba" false)))
  (is (= "/work/murakumo/bin/kotoba"
         (config/kotoba-bin "/work/murakumo" true)))
  (is (= "kotoba" (config/kotoba-bin "/work/murakumo" false)))
  (is (= "/bin/kotoba-server" (config/kotoba-server-bin "/bin")))
  (is (= "/bin/kotoba" (config/local-kotoba-bin "/bin"))))

(deftest wit-and-build-manifest-paths-are-stable
  (is (= "/work/murakumo/bin/wit" (config/pinned-wit-dir "/work/murakumo")))
  (is (= "/kotoba/crates/kotoba-runtime/wit" (config/runtime-wit-dir "/kotoba")))
  (is (= "/work/murakumo/bin/wit"
         (config/resolve-wit-dir "/work/murakumo" "/kotoba" true)))
  (is (= "/kotoba/crates/kotoba-runtime/wit"
         (config/resolve-wit-dir "/work/murakumo" "/kotoba" false)))
  (is (= "/work/murakumo/bin/BUILD.edn"
         (config/build-manifest-path "/work/murakumo")))
  (is (= ".murakumo-peers.edn"
         (config/peers-path "/work/murakumo")))
  (is (= "deploy/com.murakumo.kotoba-mesh.plist.tmpl"
         (config/launchd-template-path "/work/murakumo"))))

(deftest runtime-context-is-stable
  (is (= {"MURAKUMO_BIN" "/env/bin"
          "MURAKUMO_KOTOBA_DIR" "/custom/kotoba"
          "HOME" "/Users/ops"}
         (config/runtime-env {"MURAKUMO_BIN" "/env/bin"
                              "MURAKUMO_KOTOBA_DIR" "/custom/kotoba"
                              "HOME" "/Users/ops"
                              "IGNORED" "x"})))
  (is (= config/runtime-env-keys
         ["MURAKUMO_BIN" "MURAKUMO_KOTOBA_DIR" "HOME"]))
  (is (= {"MURAKUMO_BIN" "/env/bin"
          "MURAKUMO_KOTOBA_DIR" "/custom/kotoba"
          "HOME" "/Users/ops"}
         (config/runtime-env-from-getenv {"MURAKUMO_BIN" "/env/bin"
                                          "MURAKUMO_KOTOBA_DIR" "/custom/kotoba"
                                          "HOME" "/Users/ops"})))
  (is (= {:pinned-bin "/work/murakumo/bin"
          :pinned-server "/work/murakumo/bin/kotoba-server"
          :pinned-kotoba "/work/murakumo/bin/kotoba"
          :pinned-wit "/work/murakumo/bin/wit"}
         (config/runtime-probe-paths "/work/murakumo")))
  (is (= {:pinned-server-exists? true
          :pinned-kotoba-exists? false
          :pinned-wit-exists? true}
         (config/runtime-probe-results
          (config/runtime-probe-paths "/work/murakumo")
          #{"/work/murakumo/bin/kotoba-server"
            "/work/murakumo/bin/wit"})))
  (is (= {:user-dir "/work/murakumo"
          :kotoba-dir "/custom/kotoba"
          :local-bin "/work/murakumo/bin"
          :kotoba "/work/murakumo/bin/kotoba"
          :kotoba-server "/work/murakumo/bin/kotoba-server"
          :cli-kotoba "/work/murakumo/bin/kotoba"
          :wit "/work/murakumo/bin/wit"
          :build-manifest "/work/murakumo/bin/BUILD.edn"}
         (config/runtime-context {"MURAKUMO_KOTOBA_DIR" "/custom/kotoba"}
                                 "/work/murakumo"
                                 true
                                 true
                                 true)))
  (is (= {:user-dir "/work/murakumo"
          :kotoba-dir "/custom/kotoba"
          :local-bin "/env/bin"
          :kotoba "/env/bin/kotoba"
          :kotoba-server "/env/bin/kotoba-server"
          :cli-kotoba "kotoba"
          :wit "/custom/kotoba/crates/kotoba-runtime/wit"
          :build-manifest "/work/murakumo/bin/BUILD.edn"}
         (config/runtime-context {"MURAKUMO_KOTOBA_DIR" "/custom/kotoba"
                                  "MURAKUMO_BIN" "/env/bin"}
                                 "/work/murakumo"
                                 false
                                 false
                                 false)))
  (is (= {:user-dir "/work/murakumo"
          :kotoba-dir "/custom/kotoba"
          :local-bin "/work/murakumo/bin"
          :kotoba "/work/murakumo/bin/kotoba"
          :kotoba-server "/work/murakumo/bin/kotoba-server"
          :cli-kotoba "kotoba"
          :wit "/custom/kotoba/crates/kotoba-runtime/wit"
          :build-manifest "/work/murakumo/bin/BUILD.edn"}
         (config/runtime-context-from-probes
          {"MURAKUMO_KOTOBA_DIR" "/custom/kotoba"}
          "/work/murakumo"
          {:pinned-server-exists? true
           :pinned-kotoba-exists? false
           :pinned-wit-exists? false})))
  (is (= {:user-dir "/work/murakumo"
          :kotoba-dir "/custom/kotoba"
          :local-bin "/work/murakumo/bin"
          :kotoba "/work/murakumo/bin/kotoba"
          :kotoba-server "/work/murakumo/bin/kotoba-server"
          :cli-kotoba "kotoba"
          :wit "/work/murakumo/bin/wit"
          :build-manifest "/work/murakumo/bin/BUILD.edn"}
         (config/runtime-context-from-env
          {"MURAKUMO_KOTOBA_DIR" "/custom/kotoba"}
          "/work/murakumo"
          #{"/work/murakumo/bin/kotoba-server"
            "/work/murakumo/bin/wit"})))
  (is (= {:user-dir "/work/murakumo"
          :kotoba-dir "/custom/kotoba"
          :local-bin "/env/bin"
          :kotoba "/env/bin/kotoba"
          :kotoba-server "/env/bin/kotoba-server"
          :cli-kotoba "/work/murakumo/bin/kotoba"
          :wit "/custom/kotoba/crates/kotoba-runtime/wit"
          :build-manifest "/work/murakumo/bin/BUILD.edn"}
         (config/runtime-context-from-getenv
          {"MURAKUMO_BIN" "/env/bin"
           "MURAKUMO_KOTOBA_DIR" "/custom/kotoba"}
          "/work/murakumo"
          #{"/work/murakumo/bin/kotoba"}))))
