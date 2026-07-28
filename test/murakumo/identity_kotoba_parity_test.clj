;; W6 pure-planner oracle: murakumo.identity string preimages + JWT templates
;; vs kotoba/identity_core.kotoba (hashing / b64url stay cljc).

(ns murakumo.identity-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.identity :as id]))

(def port-source (slurp "kotoba/identity_core.kotoba"))

(def export-prefix
  (str "ws? trim seed-node seed-p2p seed-x25519 seed-overlay "
       "did-derive-cmd did-from-output "
       "jwt-header-json jwt-payload-json op-token-sig-seg "
       "cid-b-prefix graph-name-fleet "
       "seed-sep seed-p2p-suffix seed-x25519-suffix seed-overlay-suffix "
       "did-derive-subcmd jwt-seg-sep argv-join-sep"))

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

(deftest trim-and-did-from-output-match
  (let [corpus [" did:key:z-test\n"
                "\tdid:key:z\r\n"
                "plain"
                ""
                "  x  "]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "d_" i)
                           (str "(did-from-output " (kotoba-literal s) ")")])
                        corpus))
        actual (compile-string-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (id/did-from-output s) (get actual (str "d_" i))))))))

(deftest seed-preimages-match-sha-inputs
  (let [seed "operator"
        node "asher"
        overlay "bafyOverlay"
        cases {"n" (str "(seed-node " (kotoba-literal seed) " "
                        (kotoba-literal node) ")")
               "p" (str "(seed-p2p " (kotoba-literal seed) " "
                        (kotoba-literal node) ")")
               "x" (str "(seed-x25519 " (kotoba-literal seed) ")")
               "o" (str "(seed-overlay " (kotoba-literal seed) " "
                        (kotoba-literal overlay) ")")
               "cmd" (str "(did-derive-cmd " (kotoba-literal "/bin/kotoba") " "
                          (kotoba-literal seed) ")")}
        actual (compile-string-cases cases)]
    (is (= (str seed ":" node) (get actual "n")))
    (is (= (id/node-seed seed {:name node})
           (id/sha256-hex (get actual "n"))))
    (is (= (id/node-p2p-seed seed {:name node})
           (id/sha256-hex (get actual "p"))))
    (is (= (id/x25519-seed seed)
           (id/sha256-hex (get actual "x"))))
    (is (= (id/overlay-auth-key seed overlay)
           (id/sha256-hex (get actual "o"))))
    (is (= (str/join " " (id/did-derive-argv "/bin/kotoba" seed))
           (get actual "cmd")))))

(deftest op-token-templates-match-host-preimages
  (let [did "did:key:z-test"
        cases {"h" "(jwt-header-json)"
               "p" (str "(jwt-payload-json " (kotoba-literal did) ")")
               "sig" "(op-token-sig-seg)"
               "bp" "(cid-b-prefix)"
               "fn" "(graph-name-fleet)"}
        actual (compile-string-cases cases)
        header (get actual "h")
        payload (get actual "p")
        token (id/op-token did)
        parts (str/split token #"\.")]
    (is (= "{\"alg\":\"HS256\",\"typ\":\"JWT\"}" header))
    (is (= (str "{\"sub\":\"" did "\",\"exp\":9999999999}") payload))
    (is (= "kotoba-cli-media" (get actual "sig")))
    (is (= (id/b64url header) (first parts)))
    (is (= (id/b64url payload) (second parts)))
    (is (= (get actual "sig") (last parts)))
    (is (= "b" (get actual "bp")))
    (is (str/starts-with? (id/graph-cid (get actual "fn")) "b"))
    (is (= "bafyreiawk4q375adm6eibq2ut6dhamgfw4syd2cphmw4juhbmn5g4rytmy"
           (id/graph-cid (get actual "fn"))))))

(deftest seed-jwt-seps-and-subcmd-match
  (let [s (compile-string-cases
           {"ss" "(seed-sep)"
            "p2" "(seed-p2p-suffix)"
            "x2" "(seed-x25519-suffix)"
            "os" "(seed-overlay-suffix)"
            "dd" "(did-derive-subcmd)"
            "js" "(jwt-seg-sep)"
            "aj" "(argv-join-sep)"})]
    (is (= id/seed-sep (get s "ss")))
    (is (= ":" (get s "ss")))
    (is (= id/seed-p2p-suffix (get s "p2")))
    (is (= ":p2p" (get s "p2")))
    (is (= id/seed-x25519-suffix (get s "x2")))
    (is (= id/seed-overlay-suffix (get s "os")))
    (is (= id/did-derive-subcmd (get s "dd")))
    (is (= "did-derive" (get s "dd")))
    (is (= id/jwt-seg-sep (get s "js")))
    (is (= "." (get s "js")))
    (is (= id/argv-join-sep (get s "aj")))
    (is (= " " (get s "aj")))
    (is (= ["/bin/kotoba" "did-derive" "seedhex"]
           (id/did-derive-argv "/bin/kotoba" "seedhex")))
    (let [parts (str/split (id/op-token "did:key:z") #"\.")]
      (is (= 3 (count parts))))))
