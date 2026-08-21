(ns murakumo.ci.import
  "Deterministic filesystem import into the kotoba-git CID object model."
  (:require [clojure.java.io :as io]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo])
  (:import [java.nio.file Files LinkOption Path]))

(defn- symlink? [file]
  (Files/isSymbolicLink (.toPath file)))

(declare import-directory)

(defn- import-entry [db file]
  (when (symlink? file)
    (throw (ex-info "murakumo-ci: symlink rejected from source snapshot"
                    {:reason :symlink :path (.getPath file)})))
  (if (.isDirectory file)
    (let [[db' cid entries] (import-directory db file)]
      (when (pos? entries) [db' {:name (.getName file) :cid cid :kind :tree}]))
    (let [bytes (Files/readAllBytes (.toPath file))
          [db' cid] (object/write-blob db bytes)]
      [db' {:name (.getName file) :cid cid :kind :blob}])))

(defn- import-directory [db directory]
  (let [children (->> (.listFiles directory)
                      (remove #(= ".git" (.getName %)))
                      (sort-by #(.getName %)))
        [db entries]
        (reduce (fn [[db entries] file]
                  (if-let [[db' entry] (import-entry db file)]
                    [db' (conj entries entry)]
                    [db entries]))
                [db []] children)
        [db tree-cid] (object/write-tree db entries)]
    [db tree-cid (count entries)]))

(defn import-worktree
  "Import regular files and non-empty directories, excluding `.git` and all
   symlinks. Returns the live kotoba-git db plus deterministic tree/commit CIDs.
   The wrapper commit uses ts=0; the source Git revision remains explicit in
   author/message instead of importing ambient filesystem timestamps."
  [root git-revision]
  (let [root-file (io/file root)]
    (when-not (.isDirectory root-file)
      (throw (ex-info "murakumo-ci: source root is not a directory"
                      {:reason :invalid-source-root :root root})))
    (let [[db tree-cid _] (import-directory (repo/empty-repo) root-file)
          [db commit-cid] (object/write-commit
                           db {:tree tree-cid :parents []
                               :author (str "git:" git-revision)
                               :message (str "import " git-revision) :ts 0})]
      {:db db :tree-cid tree-cid :commit-cid commit-cid
       :source-manifest {:source/git-revision git-revision
                         :source/kotoba-tree tree-cid
                         :source/kotoba-commit commit-cid}})))

(defn attach [run-request imported]
  (update run-request :ci.run/source merge (:source-manifest imported)))
