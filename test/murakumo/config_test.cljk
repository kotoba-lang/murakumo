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

(def ^:private test-file-seed
  "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")

(def ^:private test-env-seed
  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")

(def ^:private missing-seed-io
  {:exists? (constantly false)
   :slurp (fn [_] (throw (ex-info "seed file must not be read when missing" {})))})

(deftest operator-seed-file-path-matches-shared-kotoba-store
  (is (= "/home/jun/.local/share/kotoba/operator.seed"
         (config/operator-seed-file-path {"HOME" "/home/jun"}))
      "XDG unset → $HOME/.local/share/kotoba/operator.seed (kotoba#493)")
  (is (= ["/xdg/kotoba/operator.seed"]
         (config/operator-seed-file-candidates
          {"HOME" "/home/jun" "XDG_DATA_HOME" "/xdg"})))
  (is (= "/xdg/kotoba/operator.seed"
         (config/operator-seed-file-path
          {"HOME" "/home/jun" "XDG_DATA_HOME" "/xdg"})))
  (is (= "/home/jun/.local/share/kotoba/operator.seed"
         (config/operator-seed-file-path
          {"HOME" "/home/jun" "XDG_DATA_HOME" "  "}))
      "blank XDG_DATA_HOME is unset")
  (is (nil? (config/operator-seed-file-path {})))
  (is (not= "/home/jun/.kotoba/operator.seed"
            (config/operator-seed-file-path {"HOME" "/home/jun"}))
      "no second default at ~/.kotoba/")
  (is (= "abc"
         (config/parse-operator-seed-file "abc\n"))
      "trailing newline from a 0600 seed file is accepted")
  (is (nil? (config/parse-operator-seed-file "  \n"))))

(deftest operator-seed-prefers-env-then-shared-file
  (let [home-path "/tmp/home/.local/share/kotoba/operator.seed"
        xdg-path "/xdg/kotoba/operator.seed"
        slurp (fn [p] (get {home-path (str test-file-seed "\n")
                            xdg-path "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
                           p))
        exists? #{home-path xdg-path}
        io {:slurp slurp :exists? exists?}]
    (is (= test-file-seed
           (config/operator-seed-from-getenv {"HOME" "/tmp/home"}
                                             {}
                                             {:slurp slurp :exists? #{home-path}}))
        "file present + env unset → shared kotoba store")
    (is (= test-env-seed
           (config/operator-seed-from-getenv {"HOME" "/tmp/home"
                                              "MURAKUMO_OPERATOR_SEED" test-env-seed}
                                             {}
                                             io))
        "env set → env wins over the shared file")
    (is (= "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
           (config/operator-seed-from-getenv {"HOME" "/tmp/home"
                                              "XDG_DATA_HOME" "/xdg"}
                                             {}
                                             io))
        "XDG_DATA_HOME store is the only file when that env is set")
    (is (nil? (config/operator-seed-from-getenv
               {"HOME" "/tmp/home"}
               {}
               {:slurp (fn [p] (get {"/tmp/home/.kotoba/operator.seed" test-file-seed} p))
                :exists? #{"/tmp/home/.kotoba/operator.seed"}}))
        "~/.kotoba/operator.seed is not a fallback")
    (is (nil? (config/operator-seed-from-getenv {} {} missing-seed-io))
        "missing both → no invented seed")
    (is (nil? (config/operator-seed-from-getenv {"HOME" "/tmp/empty"
                                                 "MURAKUMO_OPERATOR_SEED" "  "}
                                                {}
                                                missing-seed-io))
        "blank env is unset, and a missing file stays missing")))

(deftest operator-seed-reads-real-0600-shared-file
  (let [home (doto (java.io.File. (str (System/getProperty "java.io.tmpdir")
                                       "/murakumo-seed-" (System/nanoTime)))
               .mkdirs)
        dir (doto (java.io.File. home ".local/share/kotoba") .mkdirs)
        file (java.io.File. dir "operator.seed")]
    (try
      (spit file (str test-file-seed "\n"))
      (java.nio.file.Files/setPosixFilePermissions
       (.toPath file)
       (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
      (is (= test-file-seed
             (config/operator-seed-from-getenv {"HOME" (.getPath home)} {}))
          "0600 shared store is readable; contents are not logged")
      (finally
        (.delete file)
        (.delete dir)
        (.delete (java.io.File. home ".local/share"))
        (.delete (java.io.File. home ".local"))
        (.delete home)))))

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

(deftest ops-config-inject-is-exact-name-only
  (is (= "https://api.murakumo.cloud" config/default-cloud-url))
  (is (= "https://api.murakumo.cloud"
         (config/cloud-url (constantly nil))))
  (is (= "https://custom.example"
         (config/cloud-url {"MURAKUMO_CLOUD" "https://custom.example"})))
  (is (= "https://api.murakumo.cloud"
         (config/api-url (constantly nil))))
  (is (= "https://metrics.example"
         (config/api-url {"MURAKUMO_API_URL" "https://metrics.example"})))
  (is (= "http://localhost:11434"
         (config/text-backend-url (constantly nil))))
  (is (= "http://gpu:11434"
         (config/text-backend-url {"MURAKUMO_TEXT_BACKEND_URL" "http://gpu:11434"})))
  (is (= "animagine-xl-4.0.safetensors"
         (config/image-checkpoint (constantly nil))))
  (is (= "foo.safetensors"
         (config/image-checkpoint {"MURAKUMO_DEFAULT_IMAGE_CKPT" "foo.safetensors"})))
  (is (= "kotoba" (config/kotoba-cli-bin (constantly nil))))
  (is (= "/pin/kotoba"
         (config/kotoba-cli-bin {"MURAKUMO_KOTOBA_BIN" "/pin/kotoba"})))
  (is (= "http://localhost:11434/v1"
         (config/infer-local-url (constantly nil))))
  (is (nil? (config/infer-node-name (constantly nil))))
  (is (= "node-a"
         (config/infer-node-name {"MURAKUMO_INFER_NODE_NAME" "node-a"})))
  (is (nil? (config/git-bin-override (constantly nil))))
  (is (= "/usr/bin/git"
         (config/git-bin-override {"MURAKUMO_GIT_BIN" "/usr/bin/git"})))
  (is (nil? (config/adapter-driver-command "MURAKUMO_QUIC_DRIVER" (constantly nil))))
  (is (= "/opt/quic-driver"
         (config/adapter-driver-command "MURAKUMO_QUIC_DRIVER"
                                        {"MURAKUMO_QUIC_DRIVER" "/opt/quic-driver"})))
  (is (nil? (config/kekkai-ledger (constantly nil))))
  (is (= "/etc/murakumo/ledger.edn"
         (config/kekkai-ledger {"MURAKUMO_KEKKAI_LEDGER" "/etc/murakumo/ledger.edn"})))
  (is (nil? (config/kekkai-dir (constantly nil))))
  (is (= "/opt/kekkai"
         (config/kekkai-dir {"MURAKUMO_KEKKAI_DIR" "/opt/kekkai"})))
  (is (nil? (config/home-dir (constantly nil))))
  (is (= "/home/jun" (config/home-dir {"HOME" "/home/jun"})))
  (is (nil? (config/kagi-dir (constantly nil))))
  (is (= "/var/murakumo/kagi"
         (config/kagi-dir {"MURAKUMO_KAGI_DIR" "/var/murakumo/kagi"})))
  (is (some #{"MURAKUMO_KEKKAI_LEDGER" "MURAKUMO_KEKKAI_DIR" "MURAKUMO_KAGI_DIR" "HOME"}
            config/ops-config-keys))
  (is (nil? (config/config-string-or-nil "MURAKUMO_CLOUD" (constantly nil))))
  (is (nil? (config/config-string-or-nil "MURAKUMO_CLOUD" {"MURAKUMO_CLOUD" "  "}))))
