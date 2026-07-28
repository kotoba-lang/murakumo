(ns murakumo.cloud-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.cloud.plan :as cloud]
            [murakumo.identity :as identity]
            [murakumo.provision.plan :as provision]
            [murakumo.fleet.inventory :as inv]))

(def port-source (slurp "kotoba/cloud_plan_core.kotoba"))
(def export-prefix
  "default-driver default-cloud-name default-cloud-domain default-cloud-graph default-auth-key-env overlay-version digit-char nat-str i64-str node-region relay-score overlay-id-input node-id-input quic-endpoint webrtc-endpoint relay-endpoint-url webtransport-endpoint transport-endpoint")

(def fleet
  {:fleet/name "test-fleet"
   :fleet/p2p-port 4001
   :nodes [{:name "asher" :roles ["compute"] :labels {:zone "jp"} :host "asher"}
           {:name "judah" :roles ["pin"] :labels {:zone "us"}}]})

(def spec
  {:cloud/name "murakumo.cloud"
   :overlay/id "test-overlay"
   :overlay/direct [:quic :webrtc]
   :relays [{:name "jp-1" :region "jp" :url "relay://jp" :transports [:quic]}
            {:name "us-1" :region "us" :url "relay://us" :transports [:webrtc]}]})

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest constants-and-region-score
  (let [s (compile-string-cases
           {"drv" "(default-driver)"
            "cn" "(default-cloud-name)"
            "cd" "(default-cloud-domain)"
            "cg" "(default-cloud-graph)"
            "ae" "(default-auth-key-env)"
            "r1" (str "(node-region " (kotoba-literal "jp") " "
                      (kotoba-literal "") " " (kotoba-literal "") ")")
            "r2" (str "(node-region " (kotoba-literal "") " "
                      (kotoba-literal "us-west") " " (kotoba-literal "") ")")
            "r3" (str "(node-region " (kotoba-literal "") " "
                      (kotoba-literal "") " " (kotoba-literal "") ")")})
        n (compile-i64-cases
           {"ov" "(overlay-version)"
            "sc0" (str "(relay-score " (kotoba-literal "jp") " "
                       (kotoba-literal "jp") ")")
            "sc1" (str "(relay-score " (kotoba-literal "jp") " "
                       (kotoba-literal "us") ")")})]
    (is (= cloud/default-driver (get s "drv")))
    (is (= (:cloud/name cloud/default-cloud) (get s "cn")))
    (is (= (:cloud/domain cloud/default-cloud) (get s "cd")))
    (is (= (:cloud/graph cloud/default-cloud) (get s "cg")))
    (is (= (:overlay/auth-key-env cloud/default-cloud) (get s "ae")))
    (is (= (cloud/node-region {:labels {:zone "jp"}}) (get s "r1")))
    (is (= (cloud/node-region {:labels {:region "us-west"}}) (get s "r2")))
    (is (= (cloud/node-region {}) (get s "r3")))
    (is (= (:overlay/version cloud/default-cloud) (get n "ov")))
    (is (= (cloud/relay-score {:labels {:zone "jp"}} {:region "jp"}) (get n "sc0")))
    (is (= (cloud/relay-score {:labels {:zone "jp"}} {:region "us"}) (get n "sc1")))))

(deftest id-preimages-and-endpoints
  (let [oid (cloud/overlay-id spec)
        nid (cloud/node-id spec {:name "asher"})
        node (first (:nodes fleet))
        p2p (provision/node-p2p-port fleet node)
        ep (cloud/direct-endpoint spec fleet node :quic)
        we (cloud/direct-endpoint spec fleet node :webrtc)
        re (cloud/relay-endpoint (first (:relays spec)) nid)
        actual (compile-string-cases
                {"oi" (str "(overlay-id-input " (kotoba-literal "test-overlay") " "
                           (kotoba-literal "murakumo.cloud") ")")
                 "oi0" (str "(overlay-id-input " (kotoba-literal "") " "
                            (kotoba-literal "") ")")
                 "ni" (str "(node-id-input " (kotoba-literal oid) " "
                           (kotoba-literal "asher") ")")
                 "qe" (str "(quic-endpoint " (kotoba-literal "asher") " " p2p ")")
                 "we" (str "(webrtc-endpoint " (kotoba-literal "asher") " " p2p ")")
                 "ru" (str "(relay-endpoint-url " (kotoba-literal "relay://jp") " "
                           (kotoba-literal nid) ")")
                 "wt" (str "(webtransport-endpoint " (kotoba-literal "asher") " "
                           (inv/node-port fleet node) ")")
                 "te" (str "(transport-endpoint " (kotoba-literal "custom") " "
                           (kotoba-literal "asher") ")")})]
    (is (= "test-overlay" (get actual "oi")))
    (is (= (identity/graph-cid (get actual "oi")) oid))
    (is (= "murakumo.cloud" (get actual "oi0")))
    (is (= (str oid ":asher") (get actual "ni")))
    (is (= (identity/graph-cid (get actual "ni")) nid))
    (is (= (:endpoint ep) (get actual "qe")))
    (is (= (:endpoint we) (get actual "we")))
    (is (= (:endpoint re) (get actual "ru")))
    (let [wt (cloud/direct-endpoint spec fleet node :webtransport)
          te (cloud/direct-endpoint spec fleet node :custom)]
      (is (= (:endpoint wt) (get actual "wt")))
      (is (= (:endpoint te) (get actual "te"))))))
