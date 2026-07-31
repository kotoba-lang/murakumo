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
            [murakumo.secret :as secret]
            [murakumo.connect :as connect]
            [murakumo.dash.state :as dash]
            [murakumo.overlay.crypto :as crypto]
            [murakumo.kekkai.gate :as gate]
            [murakumo.overlay.driver :as driver]
            [murakumo.overlay.runtime :as runtime]
            [murakumo.task.plan :as task]
            [murakumo.infer.moe :as moe]
            [murakumo.infer.join :as join]
            [murakumo.infer.gc :as gc]
            [murakumo.infer.engine :as eng]
            [murakumo.infer.schedule :as sched]
            [murakumo.report :as report]
            [murakumo.overlay.stream :as stream]
            [murakumo.infer.credits :as credits]
            [murakumo.infer.rebalance :as rebal]))

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

(deftest call-record-connect-dash-crypto
  "T5.2 wave 6: connect class/reach, dash join/clamp, crypto sealed map."
  (when (oracle/ready? :connect)
    (let [connect {:default-class :native
                   :classes {:native {:read [:http]
                                      :live [:quic]}
                             :browser {:live [:quic]}}}
          node {:class :native :name "a"}]
      (is (= :native (connect/default-class connect)))
      (is (= :native (connect/node-class connect node)))
      (is (true? (connect/serves-reach? connect node :browser/live)))
      (is (true? (connect/serves-reach? connect node :browser/read)))))
  (when (oracle/ready? :dash-state)
    (is (= "a,b" (dash/join-append "a" "," "b")))
    (is (= "b" (dash/join-append "" "," "b")))
    (is (= "a b" (dash/hosted-append "a" "b")))
    (is (= "ok" (dash/health-class {:health "ok"})))
    (is (= 0 (dash/clamp-at 0 5)))
    (is (= 2 (dash/clamp-at 99 3))))
  (when (oracle/ready? :overlay-crypto)
    (is (true? (crypto/sealed-alg-ok? :aes-256-gcm)))
    (is (true? (crypto/sealed-fields-present?
                {:alg :aes-256-gcm :nonce "n" :ciphertext "c"})))
    (is (false? (crypto/sealed-fields-present?
                 {:alg :aes-256-gcm :nonce "n"})))))

(deftest call-record-kekkai-driver-runtime
  "T5.2 wave 7: kekkai/driver/runtime (+ dash residual interval/cid)."
  (when (oracle/ready? :dash-state)
    (is (string? (dash/short-hosted-cid "bafyverylongcid0123456789")))
    (is (number? (dash/interval-sleep-ms 5))))
  (when (oracle/ready? :kekkai-gate)
    (is (string? (gate/default-kekkai-dir "/home/demo")))
    (is (string? (gate/parse-status {:out "authorized\n"})))
    (let [part (gate/partition-nodes
                [{:name "a"} {:name "b"}]
                {"a" "authorized" "b" "pending"})]
      (is (= 1 (count (:admitted part))))
      (is (= 1 (count (:denied part))))
      (is (string? (gate/denial-line (first (:denied part)))))))
  (when (oracle/ready? :overlay-driver)
    (is (= :overlay (driver/keyword-option "--overlay")))
    (is (= :quic (driver/endpoint-kind "quic://host:4001")))
    (is (vector? (driver/missing-options [:overlay] {:overlay ""}))))
  (when (oracle/ready? :overlay-runtime)
    (is (true? (runtime/known-adapter? runtime/adapter-quic)))
    (is (string? (runtime/scheme-host "quic://asher:4001")))
    (is (pos? (get runtime/default-port-by-kind :quic)))))

(deftest call-record-task-infer-wave8
  "T5.2 wave 8: task.plan / infer.moe|join|gc multi-arg + driver dial-ok-reason."
  (when (oracle/ready? :task-plan)
    (is (true? (task/failed? {:exit 1})))
    (is (true? (task/failed? {:error "x"})))
    (is (false? (task/failed? {:exit 0})))
    (is (pos? (task/slots {:name "n" :cores 8 :slots 4} {})))
    (let [rt (task/retry-tasks
              [{:task {:id "t1" :attempt 1} :node "a" :exit 1}]
              {:max-attempts 3})]
      (is (= 1 (count rt)))
      (is (= 2 (:attempt (first rt))))))
  (when (oracle/ready? :infer-moe)
    (let [qwen {:model/experts 512 :model/active-experts 10
                :model/moe-shared-expert? true
                :model/weight-bytes (* 10 1024 1024 1024)}
          v (moe/verdict qwen)]
      (is (= :recommended (:verdict v)))
      (is (number? (moe/expert-ratio qwen)))
      (is (pos? (moe/resident-bytes-estimate qwen 32)))))
  (when (oracle/ready? :infer-join)
    (is (true? (join/can? {:tier :native} :host-large-model)))
    (is (false? (join/can? {:tier :browser} :host-large-model)))
    (is (true? (join/needs-relay? {:tier :browser})))
    (is (false? (join/needs-relay? {:tier :native :inbound-reachable? true})))
    (let [en (join/enrollment {:name "a" :did "did:key:x" :tier :native
                               :mem-bytes (* 16 1024 1024 1024)})]
      (is (map? en))
      (is (pos? (get-in en [:node/caps :max-resident-bytes])))
      (is (true? (join/eligible-for-work?
                  en {:work-kind :host-large-model :resident-bytes 1024})))))
  (when (oracle/ready? :infer-gc)
    (let [p (gc/plan
             [{:path "/tmp/a" :class :rpc-cache :bytes 100 :atime-days 30}
              {:path "/tmp/b" :class :comfy-temp :bytes 50 :atime-days 10}]
             0
             {:target-free-bytes 1000 :comfy-keep-days 3 :hf-keep 1
              :evict-order [:rpc-cache :comfy-temp]})]
      (is (map? p))
      (is (vector? (:evict p)))
      (is (boolean? (:target-met? p)))))
  (when (oracle/ready? :overlay-driver)
    (let [ok (driver/dial-result
              {:command :dial :overlay "o" :node "n" :name "a"
               :from "f" :to "t" :capability "c"
               :direct "quic://h:1" :transport "quic"})
          bad (driver/dial-result {:command :other})]
      (is (true? (:ok? ok)))
      (is (false? (:ok? bad)))
      (is (= :unknown-command (:reason bad))))))

(deftest call-record-engine-schedule-task-residual
  "T5.2 wave 9: engine cmd strings, schedule scores, task residual arith."
  (when (oracle/ready? :infer-engine)
    (let [plan {:assignments [{:node {:name "w" :host "h" :head? false :ip "10.0.0.1"}
                               :span 1}
                              {:node {:name "head" :host "h0" :head? true}
                               :span 1}]}
          ws (eng/rpc-worker-cmds plan {:bin-dir "/bin" :port 50052})
          hc (eng/head-cmd plan {:bin-dir "/bin" :model-path "/m.gguf" :port 8080})]
      (is (seq ws))
      (is (string? (:cmd (first ws))))
      (is (string? hc))
      (is (string? (eng/tensor-split plan)))
      (is (string? (eng/mlx-moe-cmd {:model-repo "org/m" :port 8080 :capacity 2})))
      (is (string? (eng/embed-head-cmd {:bin-dir "/bin" :model-path "/e.gguf"})))))
  (when (oracle/ready? :infer-schedule)
    (is (vector? (sched/score {:queue 1 :free-bytes 1000})))
    (is (= 2 (count (sched/score {:queue 0 :free-bytes 0}))))
    (let [asg (sched/assign [{:name "n" :queue 0 :free-bytes 1e12
                              :engines #{:e} :checkpoints #{}
                              :node/can-fetch? true}]
                            [{:model {:model/engine :e :model/checkpoint nil
                                      :model/min-free-bytes 0}}])]
      (is (vector? asg))))
  (when (oracle/ready? :task-plan)
    (is (vector? (task/expand 2 {:cmd ["echo"]})))
    (is (= 2 (count (task/expand 2 {:cmd ["echo"]}))))
    (let [sm (task/summary [] 1000)]
      (is (map? sm))
      (is (number? (:retried sm))))))

(deftest call-record-report-stream-credits-rebalance
  "T5.2 wave 10: report multi-arg + stream/credits/rebalance NO-CR close-out."
  (when (oracle/ready? :report-core)
    (is (string? (report/nodes-row {:name "a" :ip "1.2.3.4" :online? true}
                                   true "ok")))
    (is (string? (report/mesh-status "installed" "running")))
    (is (string? (report/launch-result-line {:name "a"} {:exit 0})))
    (is (string? (report/csv-join ["x" "y"])))
    (is (string? (report/dashboard-start-line 8080 30)))
    (let [lines (report/reconcile-lines
                 {:fleet "f" :ts "t0"
                  :apps [{:app "app1" :cid "bafyabc" :desired 1
                          :running ["n1"] :targets ["n1"]
                          :action :satisfied :reason "ok"}]})]
      (is (vector? lines))
      (is (seq lines))))
  (when (oracle/ready? :overlay-stream)
    (let [s {:type stream/type-stream :id "s1" :next-seq stream/initial-next-seq
             :window stream/default-window-size :closed? false
             :overlay "o" :node "n" :name "a" :service "svc"}
          s2 (stream/advance s)
          fr (stream/frame s "payload")
          a (stream/ack fr true)]
      (is (= (inc (:next-seq s)) (:next-seq s2)))
      (is (true? (:accepted? a)))))
  (when (oracle/ready? :infer-credits)
    (let [r (credits/charge {"alice" 100.0} "alice"
                            {:model {:model/id "m" :credit/per-token 1}
                             :tokens 10})]
      (is (true? (:allow? r)))
      (is (= 10.0 (:cost r))))
    (let [settled (credits/settle
                   {:model {:model/id "m" :credit/per-token 1}
                    :tokens 100
                    :duration-ms 1000
                    :plan {:assignments
                           [{:node {:name "w" :head? false}
                             :est-bytes 1000 :span 1}
                            {:node {:name "head" :head? true}
                             :est-bytes 100 :span 1}]}})]
      (is (map? settled))
      (is (pos? (:run/total settled)))))
  (when (oracle/ready? :infer-rebalance)
    (let [cap (rebal/capacity
               {:nodes [{:id "h" :ram-gb 64 :roles ["relay"] :status "up"
                         :disk-free 100}
                        {:id "w1" :ram-gb 32 :roles [] :status "up"
                         :disk-free 50}
                        {:id "w2" :ram-gb 32 :roles [] :status "up"
                         :disk-free 50}]})
          demand {:text 10 :image 2 :video 0 :audio 0 :postproc 1}
          target (rebal/target-allocation cap demand)]
      (is (map? target))
      (is (some? (:head target)))
      (is (map? (:pool-seats target)))
      (is (pos? (get-in target [:pool-seats :text-pool] 0))))))

(deftest call-record-cloud-provision-token-residual
  "T5.2 wave 11: cloud lines/endpoints, provision folds, token residual,
   plan strategy, relay ids, tunnel scp, dash/fleet residual, cauth id-len."
  (when (oracle/ready? :cloud-plan)
    (is (string? (cplan/summary-title "murakumo.cloud" "ov1")))
    (is (string? (cplan/dial-ok-title "r1" "asher")))
    (is (string? (cplan/from-to-cap-reason "a" "b" "deploy" "ok")))
    (is (string? (cplan/authorized-line "a" "b" "deploy")))
    (is (string? (cplan/address-family-line "ip4" 3 1)))
    (is (string? (cplan/policy-line "deny" 2)))
    (let [cloud {:cloud/name "murakumo.cloud" :overlay/id "ov1"
                 :relays [{:name "jp" :region "asia" :url "https://relay/"}]
                 :overlay/direct [:quic] :policy {:default :deny :allow []}}
          fleet {:fleet/name "f" :fleet/p2p-port 4001 :fleet/port 8080
                 :nodes [{:name "asher" :host "asher" :p2p-port 4001}]}
          node {:name "asher" :host "asher" :labels {:zone "tyo" :region "jp"}
                :region "asia" :roles #{"compute"}}
          ep (cplan/direct-endpoint cloud fleet node :quic)]
      (is (map? ep))
      (is (string? (:endpoint ep)))
      (is (number? (cplan/relay-score node (first (:relays cloud)))))))
  (when (oracle/ready? :provision-plan)
    (is (string? (pplan/local-bin-path "/opt/bin" "kotoba")))
    (is (string? (pplan/remote-bin-dest "host" "kotoba")))
    (is (string? (pplan/label-kv "zone" "tyo")))
    (is (string? (pplan/peer-entry "pid" "/ip4/1.2.3.4/udp/4001/quic")))
    (is (= "a,b" (pplan/join-append "a" "," "b")))
    (is (= "b" (pplan/join-append "" "," "b")))
    (is (string? (pplan/multiaddr "1.2.3.4" 4001)))
    (is (string? (pplan/write-plist-shell "com.x" "<plist/>"))))
  (when (oracle/ready? :token)
    (is (string? (token/wire-token "pay" "sig")))
    (is (true? (token/constant-time= "ab" "ab")))
    (is (false? (token/constant-time= "ab" "ac")))
    (is (true? (token/scope-allows? "chat" "chat")))
    (is (false? (token/scope-allows? "chat" "admin"))))
  (when (oracle/ready? :infer-plan)
    (let [s (plan/choose-strategy
             {:link-gbps 100 :ranks 4
              :model {:model/experts 0 :model/kv-heads 8}})]
      (is (map? s))
      (is (keyword? (:strategy s)))))
  (when (oracle/ready? :infer-relay)
    (let [st (relay/init)
          [jid st2] (relay/enqueue st {:kind :host-large-model :input "x" :price 1})]
      (is (string? jid))
      (is (map? st2))))
  (when (oracle/ready? :tunnel)
    (let [argv (tunnel/scp-argv "host" "/local" "/remote")]
      (is (vector? argv))
      (is (some #(= "host:/remote" %) argv))))
  (when (oracle/ready? :dash-state)
    (is (= 3 (count (dash/recent-alerts [{:a 1} {:a 2} {:a 3}] 10))))
    (is (= [2 3] (dash/append-capped [1 2] 2 3))))
  (when (oracle/ready? :fleet-inventory)
    (let [fleet {:nodes [{:name "a"} {:name "b"} {:name "c"}]}]
      (is (= 1 (count (inv/select fleet "a"))))
      (is (= 3 (count (inv/select fleet "all"))))))
  (when (oracle/ready? :component-authority)
    ;; identifier-len-ok? is private; place uses it on placement target.
    (let [[st2 ev] (cauth/place (cauth/initial-state) "bafyedge" "edge-a")]
      (is (= 1 (get-in st2 [:epochs "bafyedge"])))
      (is (map? ev)))
    (is (thrown? Exception
                 (cauth/place (cauth/initial-state) "bafyedge" "")))))

(deftest call-record-wave12-closeout
  "T5.2 wave 12: remaining single-arg residual + eligibility :raw close-out."
  (when (oracle/ready? :cloud-plan)
    (is (string? (cplan/routes-title "ov1")))
    (is (string? (cplan/unknown-node-line "missing")))
    (is (string? (cplan/connect-ok-title "asher")))
    (is (string? (cplan/reason-line "denied"))))
  (when (oracle/ready? :reconcile-plan)
    (is (pos? (rplan/watch-sleep-ms 2))))
  (when (oracle/ready? :tunnel)
    (is (string? (tunnel/wrap-cmd "echo hi")))
    (is (string? (tunnel/remote-curl-command "http://x"))))
  (when (oracle/ready? :deploy-plan)
    (is (string? (dplan/release-wit-path "/opt/release")))
    (is (string? (dplan/command-output "ok"))))
  (when (oracle/ready? :config)
    (is (string? (config/default-kotoba-dir "/Users/demo")))
    (is (string? (config/pinned-bin-dir "/Users/demo/.murakumo"))))
  (when (oracle/ready? :provision-plan)
    (is (string? (pplan/launchd-daemon-path "com.murakumo.node")))
    (is (string? (pplan/home-bin-path "/Users/demo"))))
  (when (oracle/ready? :dash-state)
    (is (string? (dash/health-url 8080)))
    (is (string? (dash/probe-command 8080))))
  (when (oracle/ready? :infer-schedule)
    (is (true? (sched/eligible?
                {:engines #{:e} :checkpoints #{} :free-bytes 1e12
                 :node/can-fetch? true}
                {:model/engine :e :model/checkpoint nil :model/min-free-bytes 0})))
    (is (false? (sched/eligible?
                 {:engines #{:other} :checkpoints #{} :free-bytes 1e12
                  :node/can-fetch? true}
                 {:model/engine :e :model/checkpoint nil :model/min-free-bytes 0}))))
  (when (oracle/ready? :task-plan)
    (is (true? (task/eligible?
                {:name "n" :roles #{} :mem-bytes 1e12}
                {:roles #{} :min-mem-bytes 0})))
    (is (false? (task/eligible?
                 {:name "n" :roles #{} :mem-bytes 10}
                 {:roles #{} :min-mem-bytes 1000})))))
