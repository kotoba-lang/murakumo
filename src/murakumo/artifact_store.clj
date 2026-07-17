(ns murakumo.artifact-store
  "Verified filesystem CAS shared by CI packaging and CD activation."
  (:require [clojure.java.io :as io]
            [multiformats.core :as multiformats])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption StandardCopyOption StandardOpenOption]))

(defn valid-cid? [cid]
  (boolean (and (string? cid) (re-matches #"baf[a-z2-7]+" cid))))

(defn- cid-matches? [cid bytes]
  (or (= cid (multiformats/cidv1-raw bytes))
      (= cid (multiformats/cidv1-dag-cbor bytes))))

(defn path [root cid]
  (when-not (valid-cid? cid)
    (throw (ex-info "murakumo-artifact: invalid CID" {:reason :invalid-cid})))
  (io/file root (subs cid 0 (min 6 (count cid))) cid))

(defn put! [root cid bytes]
  (when-not (cid-matches? cid bytes)
    (throw (ex-info "murakumo-artifact: CID mismatch"
                    {:reason :artifact-cid-mismatch :cid cid})))
  (let [target (.toPath (path root cid))
        parent (.getParent target)]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (if (Files/exists target (make-array LinkOption 0))
      (when-not (cid-matches? cid (Files/readAllBytes target))
        (throw (ex-info "murakumo-artifact: stored object is corrupt"
                        {:reason :stored-artifact-corrupt :cid cid})))
      (let [tmp (Files/createTempFile parent ".artifact-" ".tmp"
                                      (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (with-open [channel (FileChannel/open
                               tmp (into-array StandardOpenOption
                                               [StandardOpenOption/WRITE
                                                StandardOpenOption/TRUNCATE_EXISTING]))]
            (let [buffer (ByteBuffer/wrap bytes)]
              (while (.hasRemaining buffer) (.write channel buffer)))
            (.force channel true))
          (try
            (Files/move tmp target (into-array StandardCopyOption
                                               [StandardCopyOption/ATOMIC_MOVE]))
            (catch java.nio.file.AtomicMoveNotSupportedException _
              (Files/move tmp target (make-array StandardCopyOption 0)))
            (catch java.nio.file.FileAlreadyExistsException _))
          (try
            (with-open [directory (FileChannel/open
                                   parent (into-array StandardOpenOption
                                                      [StandardOpenOption/READ]))]
              (.force directory true))
            (catch Exception _))
          (finally (Files/deleteIfExists tmp)))))
    cid))

(defn get-bytes [root cid]
  (let [target (.toPath (path root cid))]
    (when (Files/isRegularFile target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (let [bytes (Files/readAllBytes target)]
        (when-not (cid-matches? cid bytes)
          (throw (ex-info "murakumo-artifact: stored object is corrupt"
                          {:reason :stored-artifact-corrupt :cid cid})))
        bytes))))

(defn adapter [root]
  {:put! #(put! root %1 %2)
   :get #(get-bytes root %)})
