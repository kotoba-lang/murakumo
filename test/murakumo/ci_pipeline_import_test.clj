(ns murakumo.ci-pipeline-import-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.ci.import :as import]
            [murakumo.ci.pipeline :as pipeline]))

(def image-digest "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def pipeline-data
  {:ci/name "CI" :ci/on {:push {}}
   :ci/jobs {"test" {:ci/id "test" :ci/runs-on "linux" :ci/needs []
                     :ci/steps [{:ci/image "docker.io/library/clojure:temurin-21-tools-deps"
                                 :ci/image-digest image-digest
                                 :ci/argv ["clojure" "-M:test"]}]}}})

(defn temp-dir [] (.toFile (java.nio.file.Files/createTempDirectory "murakumo-ci" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn write! [root path value]
  (let [f (io/file root path)] (.mkdirs (.getParentFile f)) (spit f value) f))

(deftest pipeline-loads-validates-plans-and-digests
  (let [root (temp-dir)]
    (write! root ".murakumo/pipeline.edn" (pr-str pipeline-data))
    (let [loaded (pipeline/load-pipeline root)]
      (is (= [["test"]] (:waves loaded)))
      (is (= pipeline-data (:pipeline loaded)))
      (is (= (pipeline/digest pipeline-data) (:pipeline-digest loaded))))
    (write! root ".murakumo/pipeline.edn"
            (pr-str (assoc-in pipeline-data [:ci/jobs "test" :ci/steps 0]
                              {:ci/run "curl example.com"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid pipeline"
                          (pipeline/load-pipeline root)))))

(deftest worktree-import-is-deterministic-and-restorable
  (let [a (temp-dir) b (temp-dir) revision "0123456789abcdef0123456789abcdef01234567"]
    (write! a "src/z.clj" "(ns z)\n")
    (write! a "README.md" "hello\n")
    (write! b "README.md" "hello\n")
    (write! b "src/z.clj" "(ns z)\n")
    (write! a ".git/config" "must-not-import")
    (let [ia (import/import-worktree a revision)
          ib (import/import-worktree b revision)
          store (atom {})
          snapshot (repo/persist! #(swap! store assoc %1 %2) (:db ia) nil)
          restored (repo/load #(get @store %) snapshot)]
      (is (= (:tree-cid ia) (:tree-cid ib)))
      (is (= (:commit-cid ia) (:commit-cid ib)))
      (is (= (:tree-cid ia) (:tree (object/read-commit restored (:commit-cid ia)))))
      (is (= (:source/kotoba-commit (:source-manifest ia))
             (get-in (import/attach {:ci.run/source {}} ia)
                     [:ci.run/source :source/kotoba-commit]))))))
