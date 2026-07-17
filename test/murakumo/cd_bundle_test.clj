(ns murakumo.cd-bundle-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.canonical :as canonical]
            [murakumo.cd.bundle :as bundle]))

(defn blob [s]
  (let [bytes (.getBytes s java.nio.charset.StandardCharsets/UTF_8)]
    [(second (object/write-blob (repo/empty-repo) bytes)) bytes]))

(defn manifest [cid]
  (pr-str {:kotoba.app/name "demo"
           :kotoba.app/components [{:name "demo" :cid cid}]}))

(deftest bundle-is-content-addressed-decodable-and-thread-stable
  (let [[component-cid component-bytes] (blob "wasm-component")
        document (bundle/create {:revision "sha-123" :manifest (manifest component-cid)
                                 :components [{:path "components/demo.wasm"
                                               :cid component-cid}]})
        bundle-cid (bundle/cid document)
        bytes (canonical/encode-bytes document)]
    (is (= document (bundle/decode bundle-cid bytes)))
    (is (= bundle-cid @(future (bundle/cid document))))
    (is (= document (bundle/load-bundle {bundle-cid bytes} bundle-cid)))
    (is (= component-bytes component-bytes))))

(deftest materialization-verifies-all-bytes-and-is-immutable
  (let [[component-cid component-bytes] (blob "wasm-component")
        document (bundle/create {:revision "sha-123" :manifest (manifest component-cid)
                                 :components [{:path "components/demo.wasm" :cid component-cid}]})
        bundle-cid (bundle/cid document)
        root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-release"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        first-result (bundle/materialize! {component-cid component-bytes}
                                          root bundle-cid document)
        second-result (bundle/materialize! {component-cid component-bytes}
                                           root bundle-cid document)]
    (is (false? (:reused? first-result)))
    (is (true? (:reused? second-result)))
    (is (= "wasm-component" (slurp (str (:release-dir first-result) "/components/demo.wasm"))))
    (is (= (manifest component-cid) (slurp (:manifest-path first-result))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CID mismatch"
                          (bundle/materialize! {component-cid (.getBytes "tampered")}
                                               (str root "-other") bundle-cid document)))))

(deftest unsafe-and-colliding-paths-are-rejected
  (let [[cid _] (blob "x")]
    (doseq [path ["../escape" "/absolute" "a/../../b" "a\\b" "a//b"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (bundle/create {:revision "v1" :manifest (manifest cid)
                                   :components [{:path path :cid cid}]}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (bundle/create {:revision "v1" :manifest (manifest cid)
                                 :components [{:path "a" :cid cid}
                                              {:path "a" :cid cid}]})))))

(deftest source-manifests-and-unbound-components-are-rejected
  (let [[cid _] (blob "x")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (bundle/create
                  {:revision "v1"
                   :manifest (pr-str {:kotoba.app/components [{:name "x" :src "x.clj"}]})
                   :components [{:path "x.wasm" :cid cid}]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (bundle/create
                  {:revision "v1" :manifest (manifest "another-cid")
                   :components [{:path "x.wasm" :cid cid}]})))))
