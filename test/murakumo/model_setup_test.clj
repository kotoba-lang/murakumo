(ns murakumo.model-setup-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [murakumo.model-setup :as setup]))

(def model {:model/id "demo" :hf/repo "org/demo"
            :model/checkpoint "weights/model.safetensors"
            :model/mmproj "mm proj.gguf"})

(deftest builds-resumable-hf-download
  (let [cmd (setup/download-command model ".cache/models")]
    (is (str/includes? cmd "hf download 'org/demo'"))
    (is (str/includes? cmd "'weights/model.safetensors'"))
    (is (str/includes? cmd "'mm proj.gguf'"))
    (is (str/includes? cmd "--local-dir '.cache/models/demo'"))
    (is (not (str/includes? cmd "HF_TOKEN")))))

(deftest downloads-entire-snapshot-when-no-file-is-declared
  (let [cmd (setup/download-command {:model/id "trellis" :hf/repo "microsoft/TRELLIS-image-large"} nil)]
    (is (= [] (setup/model-files {:model/id "trellis" :hf/repo "x"})))
    (is (str/includes? cmd "hf download 'microsoft/TRELLIS-image-large' --local-dir"))))

(deftest rejects-model-without-hf-repo
  (is (thrown-with-msg? Exception #"no :hf/repo"
                        (setup/download-command {:model/id "local"} nil))))
