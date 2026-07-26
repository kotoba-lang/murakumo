(ns murakumo.component-authority-store-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.component-authority :as authority]
            [murakumo.component-authority-store :as store])
  (:import [java.nio.file Files]))

(def signing
  {:seed (byte-array (range 32))
   :key-id "murakumo-2026-01"
   :issuer "did:key:murakumo"
   :audience "did:key:kototama-edge-a"
   :issued-at-ms 1785000000000})

(defn temp-journal []
  (.toFile (Files/createTempFile
            "murakumo-component-authority-" ".edn"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest failed-delivery-remains-durable-and-retries-in-order
  (let [journal (temp-journal)
        state (atom (authority/initial-state))]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/apply-durable-command!
                    state journal
                    #(throw (ex-info "partition" {:envelope %}))
                    {:op :place :component-cid "bafyreicomponent" :node "edge-a"}
                    signing)))
      (is (= 1 (count (store/pending journal))))
      (is (= 1 ((authority/epoch-source state "bafyreicomponent"))))
      (let [delivered (atom [])]
        (is (= 1 (store/deliver-pending!
                  journal #(swap! delivered conj %))))
        (is (= 1 (count @delivered)))
        (is (empty? (store/pending journal)))
        (is (= @state (store/recover-state journal))))
      (finally
        (Files/deleteIfExists (.toPath journal))))))

(deftest journal-corruption-fails-closed
  (let [journal (temp-journal)]
    (try
      (spit journal "{:op :enqueue :id [:forged]}\n")
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/pending journal)))
      (finally
        (Files/deleteIfExists (.toPath journal))))))
