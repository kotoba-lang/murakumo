;; W6 pure-planner oracle: murakumo.deploy.plan path/argv/constants
;; vs kotoba/deploy_plan_core.kotoba.

(ns murakumo.deploy-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.deploy.plan :as plan]))

(def port-source (slurp "kotoba/deploy_plan_core.kotoba"))

(def export-prefix
  (str "default-wasm default-publish-node artifact-forward-port publish-forward-port "
       "forward-settle-ms placement-wait-ms digit-char nat-str i64-str "
       "app-manifest-path publish-selector localhost-url last-slash-index manifest-dir "
       "command-output component-build-cmd app-deploy-cmd "
       "digit-val? digit-of parse-digits-go parse-digits trim-ws "
       "execution-observed? execution-count-command release-wit-path stop-forward-command "
       "absolute-unix-git-bin? blank? pin-bin-kotoba pin-bin-server join-path release-wit-suffix "
       "pin-wit-dirname pin-wit-dest "
       "cp-bin rm-bin rm-rf-flag cp-recursive-flag "
       "git-c-flag git-rev-parse git-short-flag git-head-ref "
       "version-flag version-bin-path build-features "
       "missing-manifest? missing-operator-seed? "
       "component-subcmd build-subcmd app-subcmd deploy-subcmd "
       "wit-dir-flag output-flag publish-flag url-flag "
       "token-flag block-subcmd put-subcmd file-flag"))
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

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest constants-match
  (let [s (compile-string-cases
           {"w" "(default-wasm)" "p" "(default-publish-node)"})
        n (compile-i64-cases
           {"a" "(artifact-forward-port)"
            "u" "(publish-forward-port)"
            "f" "(forward-settle-ms)"
            "w" "(placement-wait-ms)"})]
    (is (= plan/default-wasm (get s "w")))
    (is (= plan/default-publish-node (get s "p")))
    (is (= plan/artifact-forward-port (get n "a")))
    (is (= plan/publish-forward-port (get n "u")))
    (is (= plan/forward-settle-ms (get n "f")))
    (is (= plan/placement-wait-ms (get n "w")))))

(deftest manifest-dir-and-paths-match
  (let [cases {"d1" (str "(manifest-dir " (kotoba-literal "apps/bot.edn") ")")
               "d2" (str "(manifest-dir " (kotoba-literal "bot.edn") ")")
               "d3" (str "(manifest-dir " (kotoba-literal "a/b/c.edn") ")")
               "ap" (str "(app-manifest-path " (kotoba-literal "apps") " "
                         (kotoba-literal "heartbeat.edn") ")")
               "ps0" (str "(publish-selector " (kotoba-literal "") ")")
               "ps1" (str "(publish-selector " (kotoba-literal "judah") ")")
               "co" (str "(command-output " (kotoba-literal "  bafyCID\n") ")")}
        actual (compile-string-cases cases)]
    (is (= (plan/manifest-dir "apps/bot.edn") (get actual "d1")))
    (is (= (plan/manifest-dir "bot.edn") (get actual "d2")))
    (is (= (plan/manifest-dir "a/b/c.edn") (get actual "d3")))
    (is (= (plan/app-manifest-path "apps" {:manifest "heartbeat.edn"})
           (get actual "ap")))
    (is (= (plan/publish-selector nil) (get actual "ps0")))
    (is (= (plan/publish-selector "judah") (get actual "ps1")))
    (is (= (plan/command-output "  bafyCID\n") (get actual "co")))))

(deftest argv-joined-cmds-match
  (let [build (plan/component-build-argv "/bin/kotoba" "apps/src/bot.clj" "wit" "/tmp/out.wasm")
        deploy (plan/app-deploy-argv "/bin/kotoba" "apps/bot.edn" "wit" 18077)
        cases {"b" (str "(component-build-cmd "
                        (kotoba-literal "/bin/kotoba") " "
                        (kotoba-literal "apps/src/bot.clj") " "
                        (kotoba-literal "wit") " "
                        (kotoba-literal "/tmp/out.wasm") ")")
               "d" (str "(app-deploy-cmd "
                        (kotoba-literal "/bin/kotoba") " "
                        (kotoba-literal "apps/bot.edn") " "
                        (kotoba-literal "wit") " 18077)")
               "u" "(localhost-url 18077)"}
        actual (compile-string-cases cases)]
    (is (= (str/join " " build) (get actual "b")))
    (is (= (str/join " " deploy) (get actual "d")))
    (is (= "http://localhost:18077" (get actual "u")))))

(deftest execution-and-pin-probe-pure-match
  (let [i (compile-i64-cases
           {"e1" (str "(execution-observed? " (kotoba-literal "1\n") ")")
            "e0" (str "(execution-observed? " (kotoba-literal "0\n") ")")
            "ee" (str "(execution-observed? " (kotoba-literal "") ")")
            "ex" (str "(execution-observed? " (kotoba-literal "x") ")")
            "ag" (str "(absolute-unix-git-bin? " (kotoba-literal "/usr/bin/git") ")")
            "ab" (str "(absolute-unix-git-bin? " (kotoba-literal "git") ")")
            "ae" (str "(absolute-unix-git-bin? " (kotoba-literal "") ")")})
        s (compile-string-cases
           {"ec" (str "(execution-count-command " (kotoba-literal "bafyCID") ")")
            "rw" (str "(release-wit-path " (kotoba-literal "release") ")")
            "sf" "(stop-forward-command 18900)"
            "pk" "(pin-bin-kotoba)"
            "ps" "(pin-bin-server)"})]
    (is (= 1 (get i "e1")))
    (is (= 0 (get i "e0")))
    (is (= 0 (get i "ee")))
    (is (= 0 (get i "ex")))
    (is (true? (plan/execution-observed? "1\n")))
    (is (false? (plan/execution-observed? "0\n")))
    (is (false? (plan/execution-observed? "")))
    (is (= (plan/execution-count-command "bafyCID") (get s "ec")))
    (is (= (plan/release-wit-path "release") (get s "rw")))
    (is (= (plan/stop-forward-command 18900) (get s "sf")))
    (is (= 1 (get i "ag")))
    (is (= 0 (get i "ab")))
    (is (true? (plan/absolute-git-bin? "/usr/bin/git")))
    (is (false? (plan/absolute-git-bin? "git")))
    (is (= "kotoba" (get s "pk")))
    (is (= "kotoba-server" (get s "ps")))))

(deftest argv-fragments-and-gates-match
  (let [s (compile-string-cases
           {"cp" "(cp-bin)"
            "rm" "(rm-bin)"
            "rf" "(rm-rf-flag)"
            "cr" "(cp-recursive-flag)"
            "gc" "(git-c-flag)"
            "gr" "(git-rev-parse)"
            "gs" "(git-short-flag)"
            "gh" "(git-head-ref)"
            "vf" "(version-flag)"
            "vp" (str "(version-bin-path " (kotoba-literal "bin") ")")
            "bf" "(build-features)"})
        i (compile-i64-cases
           {"m1" (str "(missing-manifest? " (kotoba-literal "") ")")
            "m0" (str "(missing-manifest? " (kotoba-literal "apps/x.edn") ")")
            "s1" (str "(missing-operator-seed? " (kotoba-literal "") ")")
            "s0" (str "(missing-operator-seed? " (kotoba-literal "seed") ")")})]
    (is (= plan/cp-bin (get s "cp")))
    (is (= plan/rm-bin (get s "rm")))
    (is (= plan/rm-rf-flag (get s "rf")))
    (is (= plan/cp-recursive-flag (get s "cr")))
    (is (= plan/git-c-flag (get s "gc")))
    (is (= plan/git-rev-parse (get s "gr")))
    (is (= plan/git-short-flag (get s "gs")))
    (is (= plan/git-head-ref (get s "gh")))
    (is (= plan/version-flag (get s "vf")))
    (is (= (plan/version-bin-path "bin") (get s "vp")))
    (is (= plan/build-features (get s "bf")))
    (is (= 1 (get i "m1")))
    (is (= 0 (get i "m0")))
    (is (= 1 (get i "s1")))
    (is (= 0 (get i "s0")))
    (is (= :missing-manifest (plan/deploy-command-error nil "seed")))
    (is (= :missing-operator-seed (plan/deploy-command-error "m.edn" nil)))
    (is (nil? (plan/deploy-command-error "m.edn" "seed")))
    (is (= ["cp" "a" "b"] (plan/copy-argv "a" "b")))
    (is (= ["rm" "-rf" "bin/wit"] (plan/remove-tree-argv "bin/wit")))
    (is (= ["cp" "-R" "s" "d"] (plan/copy-tree-argv "s" "d")))
    (is (= ["/usr/bin/git" "-C" "release" "rev-parse" "--short" "HEAD"]
           (plan/git-short-sha-argv "release" "/usr/bin/git")))
    (is (= ["bin/kotoba" "--version"] (plan/version-argv "bin")))))

(deftest pin-path-fragments-match
  (let [s (compile-string-cases
           {"jp" (str "(join-path " (kotoba-literal "src") " "
                      (kotoba-literal "kotoba") ")")
            "wd" "(pin-wit-dirname)"
            "pw" (str "(pin-wit-dest " (kotoba-literal "bin") ")")
            "pk" "(pin-bin-kotoba)"
            "ps" "(pin-bin-server)"})]
    (is (= (plan/join-path "src" "kotoba") (get s "jp")))
    (is (= "src/kotoba" (get s "jp")))
    (is (= plan/pin-wit-dirname (get s "wd")))
    (is (= "wit" (get s "wd")))
    (is (= (plan/pin-wit-dest "bin") (get s "pw")))
    (is (= "bin/wit" (get s "pw")))
    (is (= plan/pin-bin-kotoba (get s "pk")))
    (is (= plan/pin-bin-server (get s "ps")))
    (is (= ["kotoba" "kotoba-server"] plan/pinned-binaries))
    (let [pin (plan/pin-copy-plan "/rel" "bin")]
      (is (= "/rel/kotoba" (-> pin :binaries first :src)))
      (is (= "bin/kotoba-server" (-> pin :binaries second :dest)))
      (is (= "bin/wit" (get-in pin [:wit :dest]))))))

(deftest deploy-argv-flag-fragments-match
  (let [s (compile-string-cases
           {"cs" "(component-subcmd)"
            "bs" "(build-subcmd)"
            "as" "(app-subcmd)"
            "ds" "(deploy-subcmd)"
            "wf" "(wit-dir-flag)"
            "of" "(output-flag)"
            "pf" "(publish-flag)"
            "uf" "(url-flag)"
            "tf" "(token-flag)"
            "bk" "(block-subcmd)"
            "pt" "(put-subcmd)"
            "ff" "(file-flag)"})]
    (is (= plan/component-subcmd (get s "cs")))
    (is (= "component" (get s "cs")))
    (is (= plan/build-subcmd (get s "bs")))
    (is (= plan/app-subcmd (get s "as")))
    (is (= plan/deploy-subcmd (get s "ds")))
    (is (= plan/wit-dir-flag (get s "wf")))
    (is (= "--wit-dir" (get s "wf")))
    (is (= plan/output-flag (get s "of")))
    (is (= plan/publish-flag (get s "pf")))
    (is (= plan/url-flag (get s "uf")))
    (is (= plan/token-flag (get s "tf")))
    (is (= plan/block-subcmd (get s "bk")))
    (is (= plan/put-subcmd (get s "pt")))
    (is (= plan/file-flag (get s "ff")))
    (is (= ["/bin/kotoba" "component" "build" "apps/src/bot.clj" "--wit-dir" "wit" "-o" "/tmp/out.wasm"]
           (plan/component-build-argv "/bin/kotoba" "apps/src/bot.clj" "wit" "/tmp/out.wasm")))
    (is (= ["/bin/kotoba" "app" "deploy" "apps/bot.edn" "--wit-dir" "wit" "--publish" "--url"
            "http://localhost:18077"]
           (plan/app-deploy-argv "/bin/kotoba" "apps/bot.edn" "wit" 18077)))
    (is (= ["/bin/kotoba" "--url" "http://localhost:18900" "--token" "tok" "block" "put" "--file"
            "/tmp/out.wasm"]
           (plan/block-put-argv "/bin/kotoba" "tok" "/tmp/out.wasm" 18900)))))
