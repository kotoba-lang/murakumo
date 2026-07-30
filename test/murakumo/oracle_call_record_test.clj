(ns murakumo.oracle-call-record-test
  "T5.2: structural host map → guest arg projection + call-record."
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.kotoba.oracle :as oracle]
            [murakumo.config :as config]
            [murakumo.infer.plan :as plan]
            [murakumo.fleet.inventory :as inv]
            [murakumo.provision.plan :as pplan]))

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
