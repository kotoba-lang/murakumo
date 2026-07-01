;; murakumo.smoke-test — namespace load checks for CLI shells.

(ns murakumo.smoke-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.cloud]
            [murakumo.overlay]
            [murakumo.overlay.adapter]
            [murakumo.core]
            [murakumo.dash]
            [murakumo.reconcile]))

(deftest cli-shell-namespaces-load
  (is (resolve 'murakumo.core/-main))
  (is (resolve 'murakumo.cloud/-main))
  (is (resolve 'murakumo.overlay/-main))
  (is (resolve 'murakumo.overlay.adapter/-main))
  (is (resolve 'murakumo.dash/-main))
  (is (resolve 'murakumo.reconcile/cmd-reconcile)))
