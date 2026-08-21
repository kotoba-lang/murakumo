(ns murakumo.ci.host
  "JVM host adapter for executing a prepared sandbox plan without a shell."
  (:require [clojure.java.io :as io]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.identity :as identity])
  (:import [java.nio.file Files LinkOption]
           [java.util.concurrent TimeUnit]))

(defn- raw-cid [bytes]
  (second (object/write-blob (repo/empty-repo) bytes)))

(defn collect-artifacts!
  [{:sandbox/keys [output-dir artifacts store-artifact! max-artifact-bytes]}]
  (when (and (seq artifacts) (nil? store-artifact!))
    (throw (ex-info "murakumo-ci: artifact store required"
                    {:reason :artifact-store-required})))
  (if-not (seq artifacts)
    []
    (let [root (.normalize (.toAbsolutePath (.toPath (io/file output-dir))))
          root-real (.toRealPath root (make-array LinkOption 0))]
      (mapv
     (fn [path]
       (let [file (.normalize (.resolve root path))
             real (try (.toRealPath file (make-array LinkOption 0))
                       (catch Exception _ nil))]
         (when-not (and (.startsWith file root)
                        real (.startsWith real root-real)
                        (Files/isRegularFile file (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
           (throw (ex-info "murakumo-ci: declared artifact is missing or unsafe"
                           {:reason :invalid-artifact-output :path path})))
         (let [size (Files/size file)]
           (when (> size (long max-artifact-bytes))
             (throw (ex-info "murakumo-ci: artifact exceeds size limit"
                             {:reason :artifact-too-large :path path :size size})))
           (let [bytes (Files/readAllBytes file)
                 cid (raw-cid bytes)]
             (store-artifact! cid bytes)
             {:path path :cid cid :size size}))))
       artifacts))))

(defn- prepare-output! [output-dir]
  (when output-dir
    (let [path (.toPath (io/file output-dir))]
      (cond
        (Files/isSymbolicLink path)
        (throw (ex-info "murakumo-ci: output directory cannot be a symlink"
                        {:reason :unsafe-output-directory}))

        (Files/exists path (make-array LinkOption 0))
        (when (with-open [entries (Files/list path)] (.isPresent (.findAny entries)))
          (throw (ex-info "murakumo-ci: output directory is not empty"
                          {:reason :dirty-output-directory})))

        :else
        (Files/createDirectories path
                                 (make-array java.nio.file.attribute.FileAttribute 0))))))

(defn execute-command!
  "Execute `:argv` directly with ProcessBuilder. Captures both streams
   concurrently, enforces the plan timeout, and returns only digests plus short
   diagnostic text; callers persist full logs in their artifact store."
  [{:keys [argv timeout-ms dir env]}]
  (let [started (System/nanoTime)
        builder (ProcessBuilder. ^java.util.List argv)
        _ (when dir (.directory builder (java.io.File. (str dir))))
        _ (doseq [[k v] env] (.put (.environment builder) (str k) (str v)))
        process (.start builder)
        stdout-f (future (slurp (.getInputStream process)))
        stderr-f (future (slurp (.getErrorStream process)))
        finished? (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)]
    (when-not finished?
      (.destroyForcibly process)
      (.waitFor process 1000 TimeUnit/MILLISECONDS))
    (let [stdout (deref stdout-f 1000 "")
          stderr (deref stderr-f 1000 "")
          duration (long (/ (- (System/nanoTime) started) 1000000))]
      {:exit (if finished? (.exitValue process) 124)
       :timed-out? (not finished?)
       :duration-ms duration
       :stdout stdout
       :stderr stderr
       :stdout-digest (identity/graph-cid stdout)
       :stderr-digest (identity/graph-cid stderr)})))

(defn execute!
  "Execute a prepared sandbox plan."
  [{:sandbox/keys [argv timeout-ms output-dir] :as plan}]
  (try
    (prepare-output! output-dir)
    (let [result (execute-command! {:argv argv :timeout-ms timeout-ms})]
      (if (zero? (:exit result))
        (try
          (assoc result :verified-artifacts (collect-artifacts! plan))
          (catch Exception e
            (assoc result :exit 125 :artifact-error (ex-data e)
                   :verified-artifacts [])))
        (assoc result :verified-artifacts [])))
    (catch Exception e
      {:exit 125 :timed-out? false :artifact-error (ex-data e)
       :verified-artifacts []})))
