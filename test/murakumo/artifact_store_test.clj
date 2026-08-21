(ns murakumo.artifact-store-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.artifact-store :as store]))

(deftest verified-filesystem-cas-roundtrip
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-cas"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        bytes (.getBytes "artifact")
        cid (second (object/write-blob (repo/empty-repo) bytes))
        cas (store/adapter root)]
    (is (= cid ((:put! cas) cid bytes)))
    (is (= "artifact" (String. ^bytes ((:get cas) cid))))
    (is (= cid ((:put! cas) cid bytes)))
    (is (thrown? clojure.lang.ExceptionInfo ((:put! cas) cid (.getBytes "tampered"))))
    (is (thrown? clojure.lang.ExceptionInfo (store/path root "../../escape")))))
