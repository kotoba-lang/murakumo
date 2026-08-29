(ns murakumo.infer-edge-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.infer.edge :as edge]))

(deftest renders-admitted-resident-plists
  (let [server (edge/server-plan
                {:home "/Users/asher" :llama-server "/opt/llama-server"
                 :memory-bytes (* 16 1073741824)})
        join (edge/join-plan
              {:home "/Users/asher" :nbb "/opt/homebrew/bin/nbb"
               :node-name "asher"})]
    (is (:admitted? server))
    (is (re-find #"--ctx-size</string><string>65536" (:plist server)))
    (is (re-find #"murakumo-edge" (:plist server)))
    (is (re-find #"source /Users/asher/.murakumo/edge/join.env" (:plist join)))
    (is (not (re-find #"MURAKUMO_SERVICE_TOKEN=" (:plist join))))))
