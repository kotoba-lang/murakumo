(ns murakumo.ci-release-bundle-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba-git.object :as object]
            [kotoba-git.repo :as repo]
            [murakumo.cd.bundle :as bundle]
            [murakumo.ci.attest :as attest]
            [murakumo.ci.release-bundle :as release-bundle]))

(defn put! [store text]
  (let [bytes (.getBytes text java.nio.charset.StandardCharsets/UTF_8)
        cid (second (object/write-blob (repo/empty-repo) bytes))]
    (swap! store assoc cid bytes)
    {:cid cid :size (alength bytes)}))

(deftest verified-ci-outputs-become-a-typed-deployable-bundle
  (let [store (atom {})
        component (put! store "wasm")
        manifest-text (pr-str {:kotoba.app/name "demo"
                               :kotoba.app/components [{:name "demo" :cid (:cid component)}]})
        manifest (put! store manifest-text)
        receipt {:receipt/source {:source/revision "0123456789abcdef"}
                 :receipt/jobs [{:ci.job/steps
                                 [{:ci.step/artifacts
                                   [(assoc manifest :path "kotoba.app.edn")
                                    (assoc component :path "components/demo.wasm")]}]}]}
        packaged (release-bundle/build! receipt "kotoba.app.edn"
                                        #(get @store %) #(swap! store assoc %1 %2))
        descriptor (:receipt/release-bundle packaged)
        decoded (bundle/decode (:cid descriptor) (get @store (:cid descriptor)))]
    (is (= :murakumo/release-bundle (:type descriptor)))
    (is (= "0123456789abcdef" (:cd.bundle/revision decoded)))
    (is (= manifest-text (:cd.bundle/manifest decoded)))
    (is (= ["components/demo.wasm"] (mapv :path (:cd.bundle/components decoded))))
    (is (= descriptor (last (attest/artifact-manifest packaged))))))

(deftest missing-or-corrupt-components-fail-closed
  (let [store (atom {})
        component (put! store "wasm")
        other (put! store "other")
        manifest (put! store (pr-str {:kotoba.app/components
                                      [{:name "demo" :cid (:cid component)}]}))
        receipt {:receipt/source {:source/revision "rev"}
                 :receipt/jobs [{:ci.job/steps [{:ci.step/artifacts
                                                 [(assoc manifest :path "kotoba.app.edn")
                                                  (assoc other :path "other.wasm")]}]}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a verified artifact"
                          (release-bundle/build! receipt "kotoba.app.edn"
                                                 #(get @store %) (fn [& _]))))))
