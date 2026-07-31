(ns murakumo.oracle-call-record-test
  "T5.2: structural host map → guest arg projection + call-record."
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.kotoba.oracle :as oracle]
            [murakumo.config :as config]
            [murakumo.infer.plan :as plan]
            [murakumo.fleet.inventory :as inv]
            [murakumo.provision.plan :as pplan]
            [murakumo.token :as token]
            [murakumo.report :as report]
            [murakumo.tunnel :as tunnel]
            [murakumo.reconcile.plan :as rplan]
            [murakumo.infer.relay :as relay]
            [murakumo.cloud.plan :as cplan]
            [murakumo.overlay.keyring :as keyring]
            [murakumo.persist :as persist]
            [murakumo.overlay.peer :as peer]
            [murakumo.component-authority :as cauth]
            [murakumo.identity :as identity]
            [murakumo.deploy.plan :as dplan]
            [murakumo.secret :as secret]))

(deftest map->args-projects-kinds
  (is (= ["a" "b"]
         (oracle/map->args {:x "a" :y "b"} [[:x :string] [:y :string]])))
  (is (= ["" "/home"]
         (oracle/map->args {"HOME" "/home"}
                           [["MURAKUMO_KOTOBA_DIR" :string]
                            ["HOME" :string]])))
  (is (= 7 (oracle/i64->host
            (first (oracle/map->args {:n 7} [[:n :i64]])))))
  (is (true? (oracle/project-field :bool 1)))
  (is (false? (oracle/project-field :bool nil)))
  (is (false? (second (oracle/project-field :option-string nil))))
  (is (true? (second (oracle/project-field :option-string "x")))))

(deftest call-record-config-kotoba-dir-from
  (when (oracle/ready? :config)
    (let [via-call (oracle/call :config 'kotoba-dir-from ["" "/Users/demo"])
          via-record (oracle/call-record
                      :config 'kotoba-dir-from
                      {"MURAKUMO_KOTOBA_DIR" ""
                       "HOME" "/Users/demo"}
                      [["MURAKUMO_KOTOBA_DIR" :string]
                       ["HOME" :string]])]
      (is (= via-call via-record))
      (is (string? via-record))
      (is (pos? (count via-record))))))

(deftest config-kotoba-dir-uses-call-record-path
  (let [env {"HOME" "/Users/demo"}
        d (config/kotoba-dir env)]
    (is (string? d))
    (is (pos? (count d)))
    ;; override wins
    (is (= "/opt/kotoba"
           (config/kotoba-dir {"MURAKUMO_KOTOBA_DIR" "/opt/kotoba"
                               "HOME" "/Users/demo"})))))

(deftest call-record-config-bins-and-wit
  (when (oracle/ready? :config)
    (let [user "/u"
          kdir "/k"
          env {"MURAKUMO_BIN" "/custom/bin"}
          via-call (oracle/call :config 'resolve-local-bin
                                [user kdir true "/custom/bin"])
          via-host (config/resolve-local-bin env user kdir true)]
      (is (= via-call via-host)))
    (let [via-call (oracle/call :config 'kotoba-bin ["/u" true])
          via-host (config/kotoba-bin "/u" true)]
      (is (= via-call via-host)))
    (let [via-call (oracle/call :config 'resolve-wit-dir ["/u" "/k" false])
          via-host (config/resolve-wit-dir "/u" "/k" false)]
      (is (= via-call via-host)))))

(deftest call-record-infer-plan-usable-bytes
  (when (oracle/ready? :infer-plan)
    (let [node {:mem-bytes (* 16 plan/GiB)
                :os-reserve-bytes plan/default-os-reserve
                :headroom-bytes plan/default-headroom
                :wired-limit-bytes nil}
          via-host (plan/usable-bytes node)
          via-call (oracle/i64->host
                    (oracle/call :infer-plan 'usable-bytes
                                 [(oracle/as-i64 (:mem-bytes node))
                                  (oracle/as-i64 plan/default-os-reserve)
                                  (oracle/as-i64 plan/default-headroom)
                                  (oracle/option-i64 nil)]))]
      (is (= via-call via-host))
      (is (pos? via-host)))
    (let [node {:mem-bytes (* 32 plan/GiB)
                :wired-limit-bytes (* 8 plan/GiB)}
          u (plan/usable-bytes node)]
      (is (<= u (* 8 plan/GiB))))))

(deftest call-record-inventory-and-provision-ports
  (when (oracle/ready? :fleet-inventory)
    (let [fleet {:fleet/port 9000
                 :nodes [{:name "a" :port 9010} {:name "b"}]}]
      (is (= 9010 (inv/node-port fleet (first (:nodes fleet)))))
      (is (= 9000 (inv/node-port fleet (second (:nodes fleet)))))))
  (when (oracle/ready? :provision-plan)
    (let [fleet {:fleet/p2p-port 4001
                 :nodes [{:name "a" :p2p-port 5001} {:name "b"}]}]
      (is (= 5001 (pplan/node-p2p-port fleet (first (:nodes fleet)))))
      (is (= 4001 (pplan/node-p2p-port fleet (second (:nodes fleet))))))))

(deftest call-record-token-claims-and-wire
  (when (oracle/ready? :token)
    (let [cl (token/claims {:sub "alice" :scope "chat" :now 1000 :ttl 60})
          json (token/encode-claims-json cl)
          via-call (oracle/call :token 'encode-claims-json
                                [(:sub cl) (:scope cl)
                                 (oracle/as-i64 (:iat cl))
                                 (oracle/as-i64 (:exp cl))])]
      (is (= "alice" (:sub cl)))
      (is (= 1060 (:exp cl)))
      (is (= via-call json))
      (is (string? json))
      (is (re-find #"\"sub\":\"alice\"" json)))
    (is (false? (token/expired? {:exp 2000} 1000)))
    (is (true? (token/expired? {:exp 500} 1000)))
    (is (true? (token/parts-present? "mk1" "pay" "sig")))
    (is (false? (token/parts-present? "mk1" "" "sig")))))

(deftest call-record-report-status-row
  (when (oracle/ready? :report-core)
    (let [node {:name "asher"}
          row (report/status-row node {:subsystems {:wasm_executor "ok"}} 3 4001)
          via-call (oracle/call :report-core 'status-row
                                ["asher" (oracle/option-i64 1) "ok" "3"
                                 (oracle/as-i64 4001)])]
      (is (= via-call row))
      (is (string? row))
      (is (re-find #"asher" row)))
    (let [down (report/status-row {:name "x"} nil nil 0)]
      (is (string? down)))))

(deftest call-record-tunnel-sh-result-pick-exit
  (when (oracle/ready? :tunnel)
    ;; In-band rc wins over ssh-exit (pick-exit via call-record).
    (let [r (tunnel/sh-result {:exit 255 :out "ok\n__murakumo_rc=0\n" :err ""})]
      (is (= 0 (:exit r)))
      (is (= 255 (:ssh-exit r))))
    (let [r (tunnel/sh-result {:exit 7 :out "no sentinel\n" :err "  e  \n"})]
      (is (= 7 (:exit r)))
      (is (= "e" (:err r))))
    (let [cmd (tunnel/ensure-forward-command 18077 8077 "asher")
          via (oracle/call :tunnel 'ensure-forward-command
                           [(oracle/as-i64 18077) (oracle/as-i64 8077) "asher"])]
      (is (= via cmd))
      (is (string? cmd)))
    (let [cmd (tunnel/replace-forward-command 18900 8077 "gad")]
      (is (string? cmd))
      (is (re-find #"18900" cmd)))))

(deftest call-record-reconcile-action-deficit
  (when (oracle/ready? :reconcile-plan)
    (let [fleet {:nodes [{:name "a" :labels {:zone "jp"} :roles #{"compute"}}
                         {:name "b" :labels {:zone "jp"} :roles #{"compute"}}
                         {:name "c" :labels {:zone "jp"} :roles #{"compute"}}]}
          snap {:nodes [{:name "a" :hosted ["bafyHEART"]}
                        {:name "b" :hosted []}
                        {:name "c" :hosted []}]}
          app {:name "heartbeat" :cid "bafyHEART" :replicas 2
               :placement {:labels {:zone "jp"} :roles ["compute"]}}
          r (rplan/reconcile-app fleet snap nil app)]
      (is (map? r))
      (is (= :place (:action r)))
      (is (= 2 (:desired r)))
      (is (= 1 (:deficit r))))))

(deftest call-record-relay-lease-and-cloud-ids
  (when (oracle/ready? :infer-relay)
    (let [st (relay/init)
          [wid st] (relay/on-hello st {:did "did:key:w" :tier :native :caps {:can #{:host-large-model}}})
          [jid st] (relay/enqueue st {:kind :host-large-model :input "x" :price 1})
          [_ st] (relay/on-ready st wid 1000)
          st2 (relay/expire-leases st (+ 1000 60001) 60000)]
      (is (nil? (get-in st2 [:assigned jid])))
      (is (pos? (count (:queue st2))))))
  (when (oracle/ready? :cloud-plan)
    (let [cloud {:cloud/name "murakumo.cloud" :overlay/id "ov1"
                 :relays [] :overlay/direct [] :policy {:default :deny :allow []}}
          node {:name "asher" :labels {:zone "tyo" :region "jp"} :region "asia"}
          oid (cplan/overlay-id cloud)
          nid (cplan/node-id cloud node)
          reg (cplan/node-region node)]
      (is (string? oid))
      (is (pos? (count oid)))
      (is (string? nid))
      (is (not= oid nid))
      (is (= "tyo" reg)))))

(deftest call-record-keyring-persist-peer-cauth
  (when (oracle/ready? :overlay-keyring)
    (let [e (keyring/epoch 1000 300)
          kid (keyring/key-id "ov" e)
          k (keyring/derive-key "seed" "ov" e)]
      (is (number? e))
      (is (string? kid))
      (is (= 16 (count kid)))
      (is (= e (:epoch k)))
      (is (= kid (:kid k)))))
  (when (oracle/ready? :persist)
    (is (string? (persist/snapshot-rkey 1000 3)))
    (is (string? (persist/reconcile-rkey 1000 3)))
    (is (re-find #"at://" (persist/repo-uri "app.bsky.feed.post" "abc")))
    (is (re-find #"18077" (persist/repo-write-url 18077))))
  (when (oracle/ready? :overlay-peer)
    (let [route {:overlay "bafyOverlay" :node "bafyNode" :name "asher"
                 :direct [{:transport :quic :endpoint "quic://asher:4001"}]
                 :relay {:relay "jp-1" :transport :quic :endpoint "relay://jp/bafyNode"}}
          p (get (peer/catalog [route]) "bafyNode")
          path (peer/choose-path p)]
      (is (map? path))
      (is (= :direct (:via path)))))
  (when (oracle/ready? :component-authority)
    (let [cid "bafyreicomponent"
          [st2 ev] (cauth/place (cauth/initial-state) cid "edge-a")]
      (is (= 1 (get-in st2 [:epochs cid])))
      (is (= 1 (:sequence st2)))
      (is (map? ev)))
    (let [cid "bafyreistale"
          [st2 _] (cauth/revoke (cauth/initial-state) cid)]
      (is (= 1 (get-in st2 [:epochs cid])))
      (is (nil? (get-in st2 [:placements cid]))))))

(deftest call-record-identity-deploy-secret
  "T5.2 wave 5: identity seeds, deploy paths, secret classify/reply."
  (when (oracle/ready? :identity)
    (let [node {:name "asher"}
          ns (identity/node-seed "op" node)
          ps (identity/node-p2p-seed "op" node)
          xs (identity/x25519-seed "op")
          ok (identity/overlay-auth-key "op" "ov1")
          argv (identity/did-derive-argv "kotoba" "deadbeef")]
      (is (string? ns))
      (is (= 64 (count ns)))
      (is (string? ps))
      (is (not= ns ps))
      (is (string? xs))
      (is (string? ok))
      (is (vector? argv))
      (is (pos? (count argv)))
      (is (= "ok" (identity/did-from-output "ok\n")))))
  (when (oracle/ready? :deploy-plan)
    (is (= "apps/foo.edn" (dplan/join-path "apps" "foo.edn")))
    (is (string? (dplan/pin-wit-dest "/opt/murakumo")))
    (is (string? (dplan/version-bin-path "/opt/murakumo")))
    (is (= "." (dplan/manifest-dir "heartbeat.edn")))
    (is (= "apps/heartbeat.edn"
           (dplan/app-manifest-path "apps" {:manifest "heartbeat.edn"})))
    (is (string? (dplan/publish-selector nil))))
  (when (oracle/ready? :secret)
    (let [fetch (secret/map-fetch {"murakumo-token" "s3cret"
                                   "blank" ""})
          ok (fetch {:name "murakumo-token"})
          missing (fetch {:name "nope"})
          blank (fetch {:name "blank"})]
      (is (= :value (:tag ok)))
      (is (= "s3cret" (:value ok)))
      (is (= :error (:tag missing)))
      (is (= :error (:tag blank)))
      (is (true? (secret/valid-env-var-name? "MURAKUMO_TOKEN_SECRET")))
      (is (false? (secret/valid-env-var-name? "")))
      (is (true? (secret/valid-path-ref? "/etc/ssl/cert.pem"))))))
