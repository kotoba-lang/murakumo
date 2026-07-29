;; W6 pure-planner oracle: murakumo.config path core vs kotoba/config_core.kotoba.

(ns murakumo.config-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.config :as config]))

(def port-source (slurp "kotoba/config_core.kotoba"))

(def export-prefix
  (str "default-fleet-path default-connect-path default-cloud-path default-kotoba-dir "
       "pinned-bin-dir release-bin-dir kotoba-server-bin local-kotoba-bin pinned-wit-dir "
       "runtime-wit-dir build-manifest-path peers-path launchd-template-path kotoba-bin "
       "resolve-local-bin resolve-wit-dir kotoba-dir-from default-cloud-url default-api-url "
       "default-text-backend-url default-image-checkpoint default-infer-local-url "
       "default-kotoba-cli-bin kotoba-dir-suffix bin-suffix release-bin-suffix "
       "wit-suffix runtime-wit-suffix kotoba-server-suffix kotoba-cli-suffix build-edn-suffix"))

(defn- kotoba-literal [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest default-paths-match
  (let [actual (compile-string-cases
                {"f" "(default-fleet-path)"
                 "c" "(default-connect-path)"
                 "cl" "(default-cloud-path)"
                 "p" "(peers-path)"
                 "l" "(launchd-template-path)"
                 "cu" "(default-cloud-url)"
                 "au" "(default-api-url)"
                 "tb" "(default-text-backend-url)"
                 "ic" "(default-image-checkpoint)"
                 "iu" "(default-infer-local-url)"
                 "kb" "(default-kotoba-cli-bin)"})]
    (is (= config/default-fleet-path (get actual "f")))
    (is (= config/default-connect-path (get actual "c")))
    (is (= config/default-cloud-path (get actual "cl")))
    (is (= (config/peers-path "/x") (get actual "p")))
    (is (= (config/launchd-template-path "/x") (get actual "l")))
    (is (= config/default-cloud-url (get actual "cu")))
    (is (= config/default-api-url (get actual "au")))
    (is (= config/default-text-backend-url (get actual "tb")))
    (is (= config/default-image-checkpoint (get actual "ic")))
    (is (= config/default-infer-local-url (get actual "iu")))
    (is (= config/default-kotoba-cli-bin (get actual "kb")))))

(deftest path-builders-match
  (let [home "/Users/ops"
        user "/work/murakumo"
        kd "/kotoba"
        cases {"dk" (str "(default-kotoba-dir " (kotoba-literal home) ")")
               "kd0" (str "(kotoba-dir-from " (kotoba-literal "") " "
                          (kotoba-literal home) ")")
               "kd1" (str "(kotoba-dir-from " (kotoba-literal "/custom/kotoba") " "
                          (kotoba-literal home) ")")
               "pb" (str "(pinned-bin-dir " (kotoba-literal user) ")")
               "rb" (str "(release-bin-dir " (kotoba-literal kd) ")")
               "ks" (str "(kotoba-server-bin " (kotoba-literal "/bin") ")")
               "lk" (str "(local-kotoba-bin " (kotoba-literal "/bin") ")")
               "pw" (str "(pinned-wit-dir " (kotoba-literal user) ")")
               "rw" (str "(runtime-wit-dir " (kotoba-literal kd) ")")
               "bm" (str "(build-manifest-path " (kotoba-literal user) ")")
               "kb1" (str "(kotoba-bin " (kotoba-literal user) " 1)")
               "kb0" (str "(kotoba-bin " (kotoba-literal user) " 0)")
               "rl1" (str "(resolve-local-bin " (kotoba-literal user) " "
                          (kotoba-literal kd) " 1 " (kotoba-literal "") ")")
               "rl2" (str "(resolve-local-bin " (kotoba-literal user) " "
                          (kotoba-literal kd) " 0 "
                          (kotoba-literal "/custom/bin") ")")
               "rl3" (str "(resolve-local-bin " (kotoba-literal user) " "
                          (kotoba-literal kd) " 0 " (kotoba-literal "") ")")
               "rw1" (str "(resolve-wit-dir " (kotoba-literal user) " "
                          (kotoba-literal kd) " 1)")
               "rw0" (str "(resolve-wit-dir " (kotoba-literal user) " "
                          (kotoba-literal kd) " 0)")}
        actual (compile-string-cases cases)]
    (is (= (config/default-kotoba-dir home) (get actual "dk")))
    (is (= (config/kotoba-dir {"HOME" home}) (get actual "kd0")))
    (is (= (config/kotoba-dir {"MURAKUMO_KOTOBA_DIR" "/custom/kotoba" "HOME" home})
           (get actual "kd1")))
    (is (= (config/pinned-bin-dir user) (get actual "pb")))
    (is (= (config/release-bin-dir kd) (get actual "rb")))
    (is (= (config/kotoba-server-bin "/bin") (get actual "ks")))
    (is (= (config/local-kotoba-bin "/bin") (get actual "lk")))
    (is (= (config/pinned-wit-dir user) (get actual "pw")))
    (is (= (config/runtime-wit-dir kd) (get actual "rw")))
    (is (= (config/build-manifest-path user) (get actual "bm")))
    (is (= (config/kotoba-bin user true) (get actual "kb1")))
    (is (= (config/kotoba-bin user false) (get actual "kb0")))
    (is (= (config/resolve-local-bin {} user kd true) (get actual "rl1")))
    (is (= (config/resolve-local-bin {"MURAKUMO_BIN" "/custom/bin"} user kd false)
           (get actual "rl2")))
    (is (= (config/resolve-local-bin {} user kd false) (get actual "rl3")))
    (is (= (config/resolve-wit-dir user kd true) (get actual "rw1")))
    (is (= (config/resolve-wit-dir user kd false) (get actual "rw0")))))

(deftest config-path-suffixes-match
  (let [s (compile-string-cases
           {"kds" "(kotoba-dir-suffix)"
            "bs" "(bin-suffix)"
            "rbs" "(release-bin-suffix)"
            "ws" "(wit-suffix)"
            "rws" "(runtime-wit-suffix)"
            "kss" "(kotoba-server-suffix)"
            "kcs" "(kotoba-cli-suffix)"
            "bes" "(build-edn-suffix)"
            "dk" (str "(default-kotoba-dir " (kotoba-literal "/h") ")")
            "pb" (str "(pinned-bin-dir " (kotoba-literal "/w") ")")
            "rb" (str "(release-bin-dir " (kotoba-literal "/k") ")")
            "ks" (str "(kotoba-server-bin " (kotoba-literal "/bin") ")")
            "lk" (str "(local-kotoba-bin " (kotoba-literal "/bin") ")")
            "pw" (str "(pinned-wit-dir " (kotoba-literal "/w") ")")
            "rw" (str "(runtime-wit-dir " (kotoba-literal "/k") ")")
            "bm" (str "(build-manifest-path " (kotoba-literal "/w") ")")
            "kb1" (str "(kotoba-bin " (kotoba-literal "/w") " 1)")
            "kb0" (str "(kotoba-bin " (kotoba-literal "/w") " 0)")})]
    (is (= config/kotoba-dir-suffix (get s "kds")))
    (is (= "/github/com-junkawasaki/orgs/com-junkawasaki/kotoba" (get s "kds")))
    (is (= config/bin-suffix (get s "bs")))
    (is (= "/bin" (get s "bs")))
    (is (= config/release-bin-suffix (get s "rbs")))
    (is (= config/wit-suffix (get s "ws")))
    (is (= config/runtime-wit-suffix (get s "rws")))
    (is (= config/kotoba-server-suffix (get s "kss")))
    (is (= config/kotoba-cli-suffix (get s "kcs")))
    (is (= config/build-edn-suffix (get s "bes")))
    (is (= (str "/h" config/kotoba-dir-suffix) (get s "dk")))
    (is (= (config/default-kotoba-dir "/h") (get s "dk")))
    (is (= (str "/w" config/bin-suffix) (get s "pb")))
    (is (= (config/pinned-bin-dir "/w") (get s "pb")))
    (is (= (str "/k" config/release-bin-suffix) (get s "rb")))
    (is (= (str "/bin" config/kotoba-server-suffix) (get s "ks")))
    (is (= (str "/bin" config/kotoba-cli-suffix) (get s "lk")))
    (is (= (str "/w" config/bin-suffix config/wit-suffix) (get s "pw")))
    (is (= (str "/k" config/runtime-wit-suffix) (get s "rw")))
    (is (= (str "/w" config/bin-suffix config/build-edn-suffix) (get s "bm")))
    (is (= (str "/w" config/bin-suffix config/kotoba-cli-suffix) (get s "kb1")))
    (is (= config/default-kotoba-cli-bin (get s "kb0")))))
