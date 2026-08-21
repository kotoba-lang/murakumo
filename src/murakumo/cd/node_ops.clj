(ns murakumo.cd.node-ops
  "Node-local deploy operations backed by locally configured argv builders.
   The wire request can select signed release values, but can never supply an
   executable or shell fragment. Successful activation is durably recorded."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]))

(defn valid-argv? [argv]
  (and (vector? argv)
       (seq argv)
       (every? #(and (string? %) (not (str/blank? %))) argv)))

(defn read-active [state-file]
  (let [file (io/file state-file)]
    (when (.isFile file)
      (edn/read-string (slurp file)))))

(defn write-active!
  "Atomically replace the active-release record, including fsync of its temp
   file. ATOMIC_MOVE is used where supported and safely falls back to replace."
  [state-file state]
  (let [target (.toPath (io/file state-file))
        parent (.getParent target)
        _ (when parent (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
        tmp (Files/createTempFile parent ".active-" ".edn"
                                  (make-array java.nio.file.attribute.FileAttribute 0))
        bytes (.getBytes (pr-str state) StandardCharsets/UTF_8)]
    (try
      (with-open [channel (java.nio.channels.FileChannel/open
                           tmp (into-array java.nio.file.OpenOption
                                           [java.nio.file.StandardOpenOption/WRITE]))]
        (.write channel (java.nio.ByteBuffer/wrap bytes))
        (.force channel true))
      (try
        (Files/move tmp target
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move tmp target
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      state
      (finally (Files/deleteIfExists tmp)))))

(defn- execute-one [exec-fn argv timeout-ms]
  (if-not (valid-argv? argv)
    {:ok? false :reason :invalid-local-argv}
    (let [result (exec-fn {:argv argv :timeout-ms timeout-ms})]
      (if (zero? (:exit result))
        {:ok? true :exit 0 :duration-ms (:duration-ms result)
         :stdout-digest (:stdout-digest result) :stderr-digest (:stderr-digest result)}
        {:ok? false :reason (if (:timed-out? result) :timeout :command-failed)
         :exit (:exit result) :duration-ms (:duration-ms result)
         :stdout-digest (:stdout-digest result) :stderr-digest (:stderr-digest result)}))))

(defn- command-plan [plan]
  (cond
    (valid-argv? plan) [plan]
    (and (vector? plan) (seq plan) (every? valid-argv? plan)) plan
    :else nil))

(defn- execute [exec-fn plan timeout-ms]
  (if-let [commands (command-plan plan)]
    (loop [remaining commands results []]
      (if-let [argv (first remaining)]
        (let [result (execute-one exec-fn argv timeout-ms)
              results (conj results result)]
          (if (:ok? result)
            (recur (rest remaining) results)
            (assoc result :commands results)))
        {:ok? true :commands results
         :duration-ms (reduce + 0 (keep :duration-ms results))}))
    {:ok? false :reason :invalid-local-argv}))

(defn operation-set
  "Create callbacks for `murakumo.cd.remote/handler`.

   The three argv builders are node-local trusted configuration. They receive
   `{node artifact-cid environment revision}` and must return one argv vector
   or a vector of argv vectors, executed sequentially and fail-fast.
   No argv from the network is accepted. `exec-fn` defaults to the direct
   ProcessBuilder host executor."
  [{:keys [state-file deploy-argv-fn health-argv-fn rollback-argv-fn exec-fn
           timeout-ms clock-fn]
    :or {timeout-ms 60000 clock-fn #(System/currentTimeMillis)}}]
  (when-not (and (string? state-file) deploy-argv-fn health-argv-fn rollback-argv-fn)
    (throw (ex-info "murakumo-cd: incomplete node operation configuration"
                    {:reason :invalid-node-operation-config})))
  (let [lock (Object.)
        exec-fn (or exec-fn (requiring-resolve 'murakumo.ci.host/execute-command!))
        activate (fn [argv-fn request]
                   (locking lock
                     (let [result (execute exec-fn (argv-fn request) timeout-ms)]
                       (when (:ok? result)
                         (write-active! state-file
                                        {:cd.active/version 1
                                         :cd.active/node (:node request)
                                         :cd.active/artifact-cid (:artifact-cid request)
                                         :cd.active/environment (:environment request)
                                         :cd.active/revision (:revision request)
                                         :cd.active/activated-at (clock-fn)}))
                       result)))]
    {:deploy-fn #(activate deploy-argv-fn %)
     :rollback-fn #(activate rollback-argv-fn %)
     :health-fn
     (fn [request]
       (locking lock
         (let [active (read-active state-file)]
           (if-not (and (= (:revision request) (:cd.active/revision active))
                        (= (:artifact-cid request) (:cd.active/artifact-cid active))
                        (= (:environment request) (:cd.active/environment active)))
             {:ok? false :reason :active-release-mismatch :active active}
             (execute exec-fn (health-argv-fn request) timeout-ms)))))}))
