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
  (str "default-driver default-cloud-name default-cloud-domain default-cloud-graph "
       "default-auth-key-env overlay-version digit-char nat-str i64-str "
       "node-region relay-score overlay-id-input node-id-input "
       "quic-endpoint webrtc-endpoint relay-endpoint-url "
       "webtransport-endpoint transport-endpoint "
       "dash-placeholder summary-nodes-header routes-header "
       "direct-candidates-label relays-section-label connects-section-label "
       "summary-title routes-title bootstrap-title "
       "unknown-node-line unknown-relay-line "
       "dial-denied-line connect-denied-line "
       "dial-ok-title connect-ok-title relay-ok-title "
       "from-to-cap-reason authorized-line "
       "relay-fallback-line reason-line indent-argv-line "
       "address-family-line policy-line skipped-reason-suffix "
       "starts-with? "
       "is-cmd-plan? is-cmd-records? is-cmd-routes? "
       "is-cmd-dial? is-cmd-connect? is-cmd-relay? is-cmd-bootstrap? "
       "is-flag-cloud? is-flag-fleet? is-flag-target? "
       "is-flag-from? is-flag-to? is-flag-capability? "
       "is-flag-driver? is-flag-format? is-flag-auth-key? "
       "is-flag-dash? is-positional-target? "
       "flag-cloud-value flag-fleet-value flag-target-value "
       "flag-from-value flag-to-value flag-capability-value "
       "flag-driver-value flag-format-value flag-auth-key-value"))

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

(deftest cli-presentation-lines-match
  (let [s (compile-string-cases
           {"dp" "(dash-placeholder)"
            "sh" "(summary-nodes-header)"
            "rh" "(routes-header)"
            "dc" "(direct-candidates-label)"
            "rl" "(relays-section-label)"
            "cl" "(connects-section-label)"
            "st" (str "(summary-title " (kotoba-literal "murakumo.cloud") " "
                      (kotoba-literal "ov1") ")")
            "rt" (str "(routes-title " (kotoba-literal "ov1") ")")
            "bt" (str "(bootstrap-title " (kotoba-literal "ov1") ")")
            "un" (str "(unknown-node-line " (kotoba-literal "asher") ")")
            "ur" (str "(unknown-relay-line " (kotoba-literal "jp-1") ")")
            "dd" (str "(dial-denied-line " (kotoba-literal "asher") ")")
            "cd" (str "(connect-denied-line " (kotoba-literal "asher") ")")
            "do" (str "(dial-ok-title " (kotoba-literal "r1") " "
                      (kotoba-literal "asher") ")")
            "co" (str "(connect-ok-title " (kotoba-literal "asher") ")")
            "ro" (str "(relay-ok-title " (kotoba-literal "jp-1") ")")
            "ft" (str "(from-to-cap-reason " (kotoba-literal "browser") " "
                      (kotoba-literal "wasm") " " (kotoba-literal "read") " "
                      (kotoba-literal "policy-denied") ")")
            "al" (str "(authorized-line " (kotoba-literal "browser") " "
                      (kotoba-literal "wasm") " " (kotoba-literal "read") ")")
            "rf" (str "(relay-fallback-line " (kotoba-literal "relay://jp/n") ")")
            "rs" (str "(reason-line " (kotoba-literal "unknown") ")")
            "ia" (str "(indent-argv-line " (kotoba-literal "murakumo-overlay dial") ")")})]
    (is (= cloud/dash-placeholder (get s "dp")))
    (is (= cloud/summary-nodes-header (get s "sh")))
    (is (= cloud/routes-header (get s "rh")))
    (is (= cloud/direct-candidates-label (get s "dc")))
    (is (= cloud/relays-section-label (get s "rl")))
    (is (= cloud/connects-section-label (get s "cl")))
    (is (= (cloud/summary-title "murakumo.cloud" "ov1") (get s "st")))
    (is (= (cloud/routes-title "ov1") (get s "rt")))
    (is (= (cloud/bootstrap-title "ov1") (get s "bt")))
    (is (= (cloud/unknown-node-line "asher") (get s "un")))
    (is (= (cloud/unknown-relay-line "jp-1") (get s "ur")))
    (is (= (cloud/dial-denied-line "asher") (get s "dd")))
    (is (= (cloud/connect-denied-line "asher") (get s "cd")))
    (is (= (cloud/dial-ok-title "r1" "asher") (get s "do")))
    (is (= (cloud/connect-ok-title "asher") (get s "co")))
    (is (= (cloud/relay-ok-title "jp-1") (get s "ro")))
    (is (= (cloud/from-to-cap-reason "browser" "wasm" "read" "policy-denied")
           (get s "ft")))
    (is (= (cloud/authorized-line "browser" "wasm" "read") (get s "al")))
    (is (= (cloud/relay-fallback-line "relay://jp/n") (get s "rf")))
    (is (= (cloud/reason-line "unknown") (get s "rs")))
    (is (= (cloud/indent-argv-line "murakumo-overlay dial") (get s "ia")))
    (is (str/starts-with? (first (cloud/summary-lines
                                  {:domain "murakumo.cloud" :overlay "x"
                                   :address_family :identity :nodes [] :relays []
                                   :policy {:default :deny :allow []}}))
                          "murakumo.cloud "))))

(deftest summary-address-policy-lines-match
  (let [s (compile-string-cases
           {"af" (str "(address-family-line " (kotoba-literal "identity") " 2 1)")
            "pl" (str "(policy-line " (kotoba-literal "deny") " 3)")
            "sk" (str "(skipped-reason-suffix " (kotoba-literal "unknown") ")")})
        lines (cloud/summary-lines
               {:domain "murakumo.cloud" :overlay "ov"
                :address_family :identity
                :nodes [{:name "a" :region "jp" :relay "r" :direct [:quic]}]
                :relays [{:name "r"}]
                :policy {:default :deny :allow [{} {}]}})]
    (is (= (cloud/address-family-line "identity" 2 1) (get s "af")))
    (is (= (cloud/policy-line "deny" 3) (get s "pl")))
    (is (= (cloud/skipped-reason-suffix "unknown") (get s "sk")))
    (is (= "  address-family identity ; nodes 2 ; relays 1" (get s "af")))
    (is (= "  policy default=deny allow=3" (get s "pl")))
    (is (= " skipped reason=unknown" (get s "sk")))
    (is (= (cloud/address-family-line "identity" 1 1) (nth lines 1)))
    (is (= (cloud/policy-line "deny" 2) (last lines)))))

(deftest parse-flags-classifiers-match
  (let [i (compile-i64-cases
           {"p1" (str "(is-cmd-plan? " (kotoba-literal "plan") ")")
            "p0" (str "(is-cmd-plan? " (kotoba-literal "dial") ")")
            "r1" (str "(is-cmd-records? " (kotoba-literal "records") ")")
            "d1" (str "(is-cmd-dial? " (kotoba-literal "dial") ")")
            "c1" (str "(is-cmd-connect? " (kotoba-literal "connect") ")")
            "y1" (str "(is-cmd-relay? " (kotoba-literal "relay") ")")
            "b1" (str "(is-cmd-bootstrap? " (kotoba-literal "bootstrap") ")")
            "fc" (str "(is-flag-cloud? " (kotoba-literal "--cloud=x.edn") ")")
            "ff" (str "(is-flag-fleet? " (kotoba-literal "--fleet=f.edn") ")")
            "ft" (str "(is-flag-target? " (kotoba-literal "--target=n") ")")
            "fr" (str "(is-flag-from? " (kotoba-literal "--from=browser") ")")
            "to" (str "(is-flag-to? " (kotoba-literal "--to=wasm") ")")
            "ca" (str "(is-flag-capability? " (kotoba-literal "--capability=live") ")")
            "dr" (str "(is-flag-driver? " (kotoba-literal "--driver=x") ")")
            "fm" (str "(is-flag-format? " (kotoba-literal "--format=edn") ")")
            "ak" (str "(is-flag-auth-key? " (kotoba-literal "--auth-key=s") ")")
            "da" (str "(is-flag-dash? " (kotoba-literal "--unknown") ")")
            "po" (str "(is-positional-target? " (kotoba-literal "asher") ")")
            "pn" (str "(is-positional-target? " (kotoba-literal "--x") ")")
            "sw" (str "(starts-with? " (kotoba-literal "--cloud=x") " "
                      (kotoba-literal "--cloud=") ")")})
        s (compile-string-cases
           {"vc" (str "(flag-cloud-value " (kotoba-literal "--cloud=prod.edn") ")")
            "vf" (str "(flag-fleet-value " (kotoba-literal "--fleet=fleet.edn") ")")
            "vt" (str "(flag-target-value " (kotoba-literal "--target=asher") ")")
            "vfrom" (str "(flag-from-value " (kotoba-literal "--from=browser") ")")
            "vto" (str "(flag-to-value " (kotoba-literal "--to=wasm") ")")
            "vcap" (str "(flag-capability-value " (kotoba-literal "--capability=live") ")")
            "vdr" (str "(flag-driver-value " (kotoba-literal "--driver=net") ")")
            "vfm" (str "(flag-format-value " (kotoba-literal "--format=edn") ")")
            "vak" (str "(flag-auth-key-value " (kotoba-literal "--auth-key=secret") ")")})]
    (is (= 1 (get i "p1")))
    (is (= 0 (get i "p0")))
    (is (= 1 (get i "r1")))
    (is (= 1 (get i "d1")))
    (is (= 1 (get i "c1")))
    (is (= 1 (get i "y1")))
    (is (= 1 (get i "b1")))
    (is (= 1 (get i "fc")))
    (is (= 1 (get i "ff")))
    (is (= 1 (get i "ft")))
    (is (= 1 (get i "fr")))
    (is (= 1 (get i "to")))
    (is (= 1 (get i "ca")))
    (is (= 1 (get i "dr")))
    (is (= 1 (get i "fm")))
    (is (= 1 (get i "ak")))
    (is (= 1 (get i "da")))
    (is (= 1 (get i "po")))
    (is (= 0 (get i "pn")))
    (is (= 1 (get i "sw")))
    (is (= "prod.edn" (get s "vc")))
    (is (= "fleet.edn" (get s "vf")))
    (is (= "asher" (get s "vt")))
    (is (= "browser" (get s "vfrom")))
    (is (= "wasm" (get s "vto")))
    (is (= "live" (get s "vcap")))
    (is (= "net" (get s "vdr")))
    (is (= "edn" (get s "vfm")))
    (is (= "secret" (get s "vak")))
    (is (= {:command :records
            :cloud-path "prod.edn"
            :fleet-path "fleet-prod.edn"}
           (select-keys
            (cloud/parse-flags ["records" "--cloud=prod.edn" "--fleet=fleet-prod.edn"])
            [:command :cloud-path :fleet-path])))
    (is (= {:command :dial :target "asher" :from :browser :capability :live}
           (select-keys
            (cloud/parse-flags ["dial" "asher" "--from=browser" "--capability=live"])
            [:command :target :from :capability])))))
