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
