;; murakumo.overlay-keyring-test — key rotation tests.

(ns murakumo.overlay-keyring-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.overlay.keyring :as keyring]))

(deftest rotation-plan-derives-previous-current-next
  (let [plan (keyring/rotation-plan "operator-seed" "bafyOverlay" (* 3 86400))]
    (is (= "murakumo.overlay.key-rotation" (:type plan)))
    (is (= 3 (get-in plan [:current :epoch])))
    (is (= 2 (get-in plan [:previous :epoch])))
    (is (= 4 (get-in plan [:next :epoch])))
    (is (= (get-in plan [:current :key]) (keyring/active-key plan)))
    (is (= 3 (count (keyring/accepted-kids plan))))
    (is (not= (get-in plan [:previous :key])
              (get-in plan [:current :key])))))
