(ns murakumo.infer-gateway-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.gateway :as gw]))

(deftest parse-size-test
  (testing "valid WxH"
    (is (= [832 1216] (#'gw/parse-size "832x1216")))
    (is (= [1024 1024] (#'gw/parse-size "1024x1024"))))
  (testing "missing/malformed falls back to the txt2img default"
    (is (= [832 1216] (#'gw/parse-size nil)))
    (is (= [832 1216] (#'gw/parse-size "not-a-size")))))

(deftest image-filename-test
  (testing "extracts the SaveImage output filename from a run-job! history"
    (is (= "murakumo_00001_.png"
           (#'gw/image-filename {:outputs {:7 {:images [{:filename "murakumo_00001_.png"}]}}}))))
  (testing "nil when the expected shape is absent"
    (is (nil? (#'gw/image-filename {:outputs {}})))
    (is (nil? (#'gw/image-filename {})))))

(deftest pick-any-node-eligibility-test
  (testing "a nil/default checkpoint restricts to nodes actually holding it,
            not every :comfyui node regardless of model (the bug a live
            end-to-end test caught: an unspecified checkpoint used to make
            video-only nodes 'eligible' for a plain txt2img request)"
    (let [f {:nodes [{:name "img-node"} {:name "video-node"}]}]
      (with-redefs [murakumo.infer.media/live-fleet
                    (fn [_] [{:name "img-node" :engines #{:comfyui}
                             :checkpoints #{"animagine-xl-4.0.safetensors"} :queue 0 :free-bytes 1e9}
                            {:name "video-node" :engines #{:comfyui}
                             :checkpoints #{"ltxv-2b-0.9.6-distilled-04-25.safetensors"} :queue 0 :free-bytes 1e9}])]
        (is (= "img-node" (:name (gw/pick-any-node! f))))
        (is (= "img-node" (:name (gw/pick-any-node! f "animagine-xl-4.0.safetensors"))))))))
