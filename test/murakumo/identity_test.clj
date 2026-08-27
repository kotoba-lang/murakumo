;; murakumo.identity-test — offline tests for portable identity formatting.

(ns murakumo.identity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ed25519.core :as ed25519]
            [murakumo.config :as config]
            [murakumo.identity :as id]
            [murakumo.provision.plan :as plan]
            [murakumo.report :as report]))

(deftest sha256-hex-is-stable
  (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
         (id/sha256-hex "hello"))))

(deftest derived-seeds-are-stable
  (let [seed "operator"
        node {:name "asher"}]
    (is (= (id/sha256-hex "operator:asher")
           (id/node-seed seed node)))
    (is (= (id/sha256-hex "operator:asher:p2p")
           (id/node-p2p-seed seed node)))
    (is (= (id/sha256-hex "operator:x25519")
           (id/x25519-seed seed)))
    (is (= (id/sha256-hex "operator:bafyOverlay:murakumo-overlay-auth")
           (id/overlay-auth-key seed "bafyOverlay")))
    (is (= ["/bin/kotoba" "did-derive" seed]
           (id/did-derive-argv "/bin/kotoba" seed)))
    (is (= "did:key:z-test"
           (id/did-from-output " did:key:z-test\n")))
    (is (= "did:key:z-test"
           (id/did-from-command-result {:out " did:key:z-test\n"})))))

(def ^:private kotoba-seed
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private other-seed
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(deftest operator-did-matches-kotoba-seed-derivation
  (testing "file present + env unset → same DID kotoba derives from that seed"
    (let [path "/tmp/home/.local/share/kotoba/operator.seed"
          seed kotoba-seed
          getenv {"HOME" "/tmp/home"}
          io {:exists? #{path}
              :slurp {path (str seed "\n")}}
          resolved (config/operator-seed-from-getenv getenv {} io)
          expected (ed25519/did-key-from-seed-hex seed)
          result (id/identity-command-result resolved)]
      (is (= seed resolved))
      (is (= expected (id/did-from-seed-hex resolved)))
      (is (= {:ok? true :did expected} result))
      (is (str/starts-with? (:did result) "did:key:"))
      (is (not (str/includes? (str result) seed))
          "identity output never echoes the seed")))
  (testing "env set → env wins, DID follows the env seed"
    (let [path "/tmp/home/.local/share/kotoba/operator.seed"
          getenv {"HOME" "/tmp/home"
                  "MURAKUMO_OPERATOR_SEED" other-seed}
          io {:exists? #{path}
              :slurp {path (str kotoba-seed "\n")}}
          resolved (config/operator-seed-from-getenv getenv {} io)
          expected (ed25519/did-key-from-seed-hex other-seed)]
      (is (= other-seed resolved))
      (is (= expected (id/did-from-seed-hex resolved)))
      (is (not= expected (ed25519/did-key-from-seed-hex kotoba-seed)))))
  (testing "missing both → existing missing-seed error, no invented seed"
    (let [resolved (config/operator-seed-from-getenv
                    {} {}
                    {:exists? (constantly false)
                     :slurp (constantly nil)})
          result (id/identity-command-result resolved)]
      (is (nil? resolved))
      (is (= :missing-operator-seed-hex (plan/provision-command-error resolved)))
      (is (= :missing-operator-seed (plan/mesh-command-error resolved)))
      (is (= {:ok? false :error :missing-operator-seed-hex} result))
      (is (= "set MURAKUMO_OPERATOR_SEED (32-byte hex) first"
             report/operator-seed-hex-required-line))
      (is (= "set MURAKUMO_OPERATOR_SEED first"
             report/operator-seed-required-line)))))

(deftest graph-cid-matches-existing-kotoba-shape
  (is (= "bafyreiawk4q375adm6eibq2ut6dhamgfw4syd2cphmw4juhbmn5g4rytmy"
         (id/graph-cid "murakumo-fleet")))
  (is (= "bafyreifhy47wgetwxc5i52wpipblztazoiun465pyjbbb6fcefmsjz6ake"
         (id/graph-cid "rec-1"))))

(deftest op-token-shape-is-stable
  (let [token (id/op-token "did:key:z-test")
        parts (clojure.string/split token #"\.")]
    (is (= 3 (count parts)))
    (is (= "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" (first parts)))
    (is (= "kotoba-cli-media" (last parts)))
    (is (not (re-find #"=" token)) "base64url padding is stripped")))
