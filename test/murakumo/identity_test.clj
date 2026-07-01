;; murakumo.identity-test — offline tests for portable identity formatting.

(ns murakumo.identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.identity :as id]))

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
