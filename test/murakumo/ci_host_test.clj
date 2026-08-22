(ns murakumo.ci-host-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [murakumo.ci.host :as host]))

(deftest process-adapter-executes-argv-without-shell
  (let [result (host/execute! {:sandbox/argv ["/usr/bin/printf" "hello"]
                               :sandbox/timeout-ms 1000})]
    (is (= 0 (:exit result)))
    (is (= "hello" (:stdout result)))
    (is (false? (:timed-out? result)))
    (is (string? (:stdout-digest result)))))

(deftest process-adapter-kills-timeouts
  (let [result (host/execute! {:sandbox/argv ["/bin/sleep" "1"]
                               :sandbox/timeout-ms 10})]
    (is (= 124 (:exit result)))
    (is (true? (:timed-out? result)))))

(deftest host-hashes-and-persists-declared-artifact-bytes
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "murakumo-ci-artifacts"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (spit (io/file dir "app.wasm") "real-wasm-bytes")
        stored (atom {})
        artifacts (host/collect-artifacts!
                   {:sandbox/output-dir dir :sandbox/artifacts ["app.wasm"]
                    :sandbox/max-artifact-bytes 1024
                    :sandbox/store-artifact! #(swap! stored assoc %1 %2)})
        cid (:cid (first artifacts))]
    (is (= [{:path "app.wasm" :cid cid :size 15}] artifacts))
    (is (= "real-wasm-bytes" (String. ^bytes (get @stored cid))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (host/collect-artifacts!
                  {:sandbox/output-dir dir :sandbox/artifacts ["missing"]
                   :sandbox/max-artifact-bytes 1024
                   :sandbox/store-artifact! (fn [& _])})))))

(deftest successful-step-with-declared-artifact-fails-without-store
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "murakumo-ci-no-store"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        file (str (io/file dir "out"))
        result (host/execute! {:sandbox/argv ["/usr/bin/touch" file]
                               :sandbox/timeout-ms 1000 :sandbox/output-dir dir
                               :sandbox/artifacts ["out"]
                               :sandbox/max-artifact-bytes 1024})]
    (is (= 125 (:exit result)))
    (is (= :artifact-store-required (get-in result [:artifact-error :reason])))))

(deftest dirty-step-output-is-rejected-before-command-executes
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "murakumo-ci-dirty"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        marker (io/file dir "stale")
        _ (spit marker "old")
        touched (str (io/file dir "executed"))
        result (host/execute! {:sandbox/argv ["/usr/bin/touch" touched]
                               :sandbox/timeout-ms 1000 :sandbox/output-dir dir})]
    (is (= :dirty-output-directory (get-in result [:artifact-error :reason])))
    (is (false? (.exists (io/file touched))))))
