(ns murakumo.artifact-import-test
  (:require [clojure.test :refer [deftest is]]
            [multiformats.core :as multiformats]
            [murakumo.artifact-import :as artifact-import]
            [murakumo.artifact-store :as artifact-store]))

(deftest bootstrap-import-verifies-cid-before-admission
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-import-cas"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        file (java.io.File/createTempFile "murakumo-import" ".bin")
        bytes (.getBytes "bootstrap" "UTF-8")
        cid (multiformats/cidv1-raw bytes)]
    (java.nio.file.Files/write (.toPath file) bytes
                               (make-array java.nio.file.OpenOption 0))
    (is (= cid (:cid (artifact-import/import! root (str cid "=" file)))))
    (is (= (seq bytes) (seq (artifact-store/get-bytes root cid))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CID mismatch"
                          (artifact-import/import! root (str "bafybad=" file))))))
