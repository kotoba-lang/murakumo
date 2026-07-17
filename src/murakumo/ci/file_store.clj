(ns murakumo.ci.file-store
  "Crash-safe local IStore adapter for broker snapshots and event streams."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption StandardOpenOption]))

(defn- safe-name [value]
  (when-not (and (string? value) (re-matches #"[A-Za-z0-9._-]+" value))
    (throw (ex-info "murakumo-ci: unsafe store name"
                    {:reason :unsafe-store-name :value value})))
  value)

(defn- read-edn [file]
  (when (.isFile ^java.io.File file)
    (edn/read-string (slurp file))))

(defn- atomic-write! [file value]
  (let [target (.toPath ^java.io.File file)
        parent (.getParent target)
        _ (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
        tmp (Files/createTempFile parent ".store-" ".tmp"
                                  (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [channel (FileChannel/open
                           tmp (into-array StandardOpenOption
                                           [StandardOpenOption/WRITE
                                            StandardOpenOption/TRUNCATE_EXISTING]))]
        (let [buffer (ByteBuffer/wrap
                      (.getBytes (pr-str value) StandardCharsets/UTF_8))]
          (while (.hasRemaining buffer) (.write channel buffer)))
        (.force channel true))
      (try
        (Files/move tmp target (into-array StandardCopyOption
                                           [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move tmp target (into-array StandardCopyOption
                                             [StandardCopyOption/REPLACE_EXISTING]))))
      ;; Persist the directory entry as well as the file contents. Some
      ;; platforms do not permit opening a directory channel; the atomic file
      ;; remains valid there, while Unix filesystems get the stronger boundary.
      (try
        (with-open [directory (FileChannel/open
                               parent (into-array StandardOpenOption
                                                  [StandardOpenOption/READ]))]
          (.force directory true))
        (catch Exception _))
      value
      (finally (Files/deleteIfExists tmp)))))

(defn create [root]
  (let [root (io/file root)
        lock (Object.)
        doc-file (fn [bucket key]
                   (io/file root "docs" (safe-name bucket) (str (safe-name key) ".edn")))
        stream-file (fn [stream]
                      (io/file root "streams" (str (safe-name stream) ".edn")))
        read-stream (fn [stream]
                      (vec (or (read-edn (stream-file stream)) [])))]
    {:put! (fn [bucket key value]
             (locking lock (atomic-write! (doc-file bucket key) value)))
     :get (fn [bucket key]
            (locking lock (read-edn (doc-file bucket key))))
     :append! (fn [stream value]
                (locking lock
                  (let [items (read-stream stream)
                        event-id (:ci.event/id value)
                        existing (when event-id
                                   (first (filter #(= event-id (:ci.event/id %)) items)))]
                    (if existing
                      existing
                      (let [item (assoc value :seq (inc (count items)))]
                        (atomic-write! (stream-file stream) (conj items item))
                        item)))))
     :read (fn [stream since]
             (locking lock
               (filterv #(> (:seq %) since) (read-stream stream))))}))
