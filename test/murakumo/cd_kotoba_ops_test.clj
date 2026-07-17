(ns murakumo.cd-kotoba-ops-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.canonical :as canonical]
            [murakumo.cd.bundle :as bundle]
            [murakumo.cd.kotoba-ops :as kotoba-ops]
            [murakumo.cd.node-ops :as node-ops]))

(defn blob [s]
  (let [bytes (.getBytes s java.nio.charset.StandardCharsets/UTF_8)]
    [(second (object/write-blob (repo/empty-repo) bytes)) bytes]))

(deftest verified-bundle-is-put-published-health-checked-and-rollbackable
  (let [[v2-cid v2-bytes] (blob "wasm-v2")
        [v1-cid v1-bytes] (blob "wasm-v1")
        make-bundle (fn [revision component-cid]
                      (bundle/create
                       {:revision revision
                        :manifest (pr-str {:kotoba.app/name "demo"
                                           :kotoba.app/components
                                           [{:name "demo" :cid component-cid}]})
                        :components [{:path "components/demo.wasm" :cid component-cid}]}))
        v2 (make-bundle "v2" v2-cid)
        v1 (make-bundle "v1" v1-cid)
        v2-bundle-cid (bundle/cid v2)
        v1-bundle-cid (bundle/cid v1)
        store {v2-cid v2-bytes v1-cid v1-bytes
               v2-bundle-cid (canonical/encode-bytes v2)
               v1-bundle-cid (canonical/encode-bytes v1)}
        root (str (java.nio.file.Files/createTempDirectory
                   "murakumo-kotoba-ops"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        state-file (str root "/active.edn")
        commands (atom [])
        ops (kotoba-ops/operation-set
             {:fetch-bytes store :releases-root (str root "/releases")
              :state-file state-file :kotoba "/opt/murakumo/bin/kotoba"
              :token "operator-token" :url "http://127.0.0.1:8077"
              :wit-dir "/opt/murakumo/wit" :health-url "http://127.0.0.1:8077/health"
              :exec-fn (fn [command]
                         (swap! commands conj (:argv command))
                         {:exit 0 :duration-ms 1})})
        request {:node "node-a" :artifact-cid v2-bundle-cid
                 :environment "prod" :revision "v2"}]
    (is (:ok? ((:deploy-fn ops) request)))
    (is (= v2-bundle-cid (:cd.active/artifact-cid (node-ops/read-active state-file))))
    (is (:ok? ((:health-fn ops) request)))
    (is (:ok? ((:rollback-fn ops)
               (assoc request :artifact-cid v1-bundle-cid :revision "v1"))))
    (is (= "v1" (:cd.active/revision (node-ops/read-active state-file))))
    (is (= 5 (count @commands)))
    (is (= ["/usr/bin/curl" "--fail" "--silent" "--show-error"
            "--max-time" "5" "http://127.0.0.1:8077/health"]
           (nth @commands 2)))))
