(ns murakumo.oracle-call-record-test
  "T5.2: structural host map → guest arg projection + call-record."
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.kotoba.oracle :as oracle]
            [murakumo.config :as config]))

(deftest map->args-projects-kinds
  (is (= ["a" "b"]
         (oracle/map->args {:x "a" :y "b"} [[:x :string] [:y :string]])))
  (is (= ["" "/home"]
         (oracle/map->args {"HOME" "/home"}
                           [["MURAKUMO_KOTOBA_DIR" :string]
                            ["HOME" :string]])))
  (is (= 7 (oracle/i64->host
            (first (oracle/map->args {:n 7} [[:n :i64]])))))
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
