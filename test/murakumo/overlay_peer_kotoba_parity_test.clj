(ns murakumo.overlay-peer-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.overlay.peer :as peer]))

(def port-source (slurp "kotoba/overlay_peer_core.kotoba"))
(def export-prefix
  "choose-via health-unknown health-seen health-down via-direct via-relay")

(def route
  {:overlay "bafyOverlay"
   :node "bafyNode"
   :name "asher"
   :direct [{:transport :quic :endpoint "quic://asher:4001"}]
   :relay {:relay "jp-1" :transport :quic :endpoint "relay://jp/bafyNode"}})

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

(defn- project-choose [peer]
  (let [paths (peer/candidate-paths peer)
        has-direct (if (some #(= :direct (:via %)) paths) 1 0)
        has-relay (if (some #(= :relay (:via %)) paths) 1 0)
        health-down (if (= :down (:health peer)) 1 0)
        via (some-> (peer/choose-path peer) :via name)]
    {:has-direct has-direct :has-relay has-relay :health-down health-down
     :expected (or via "")}))

(deftest choose-via-matches-choose-path
  (let [p (peer/peer-record route)
        down (assoc p :health :down)
        no-direct (assoc p :direct [])
        corpus [p down no-direct (assoc no-direct :relay nil)]
        cases (into {}
                    (map-indexed
                     (fn [i peer]
                       (let [x (project-choose peer)]
                         [(str "c_" i)
                          (str "(choose-via " (:has-direct x) " "
                               (:health-down x) " " (:has-relay x) ")")]))
                     corpus))
        actual (compile-string-cases cases)
        labels (compile-string-cases
                {"hu" "(health-unknown)" "hs" "(health-seen)" "hd" "(health-down)"
                 "vd" "(via-direct)" "vr" "(via-relay)"})]
    (is (= "unknown" (get labels "hu")))
    (is (= "seen" (get labels "hs")))
    (is (= "down" (get labels "hd")))
    (is (= "direct" (get labels "vd")))
    (is (= "relay" (get labels "vr")))
    (doseq [[i peer] (map-indexed vector corpus)]
      (let [x (project-choose peer)]
        (testing (str (:health peer) " direct=" (seq (:direct peer)))
          (is (= (:expected x) (get actual (str "c_" i)))))))))
