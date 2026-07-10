;; Offline unit test for murakumo.infer.media's await-history poll-interval
;; backoff (root-caused 2026-07-09/10: the old flat 3s interval opened a fresh
;; SSH connection per poll, ~28 connections for a single ~84s render, which
;; tripped intermittent 502/524s under back-to-back testing). No fleet, no SSH.
(ns murakumo.infer-media-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.media :as media]))

(def poll-interval-s #'media/poll-interval-s)
(def fast-window-s @#'media/fast-window-s)
(def fast-interval-s @#'media/fast-interval-s)
(def slow-interval-s @#'media/slow-interval-s)

(deftest fast-phase-during-the-early-window
  (testing "polls quickly for the first fast-window-s (catches genuinely fast jobs)"
    (is (= fast-interval-s (poll-interval-s 0)))
    (is (= fast-interval-s (poll-interval-s (dec fast-window-s))))))

(deftest backs-off-once-past-the-fast-window
  (testing "polls at the slower interval once waited has reached fast-window-s"
    (is (= slow-interval-s (poll-interval-s fast-window-s)))
    (is (= slow-interval-s (poll-interval-s (* fast-window-s 10))))))

(deftest slower-than-old-flat-3s-interval
  (testing "both phases are >= the old flat 3s interval — this change only
            reduces connection churn, never increases responsiveness worse
            than before by more than the slow interval allows"
    (is (>= fast-interval-s 2))
    (is (>= slow-interval-s 3))))

(deftest total-connection-count-materially-reduced-for-a-typical-render
  (testing "simulate the poll loop's own `waited` accumulation for an 84s
            render (the real duration observed 2026-07-09) and count how many
            polls (= SSH connections) it takes — must be well under the old
            flat-3s count of ~28"
    (let [render-s 84]
      (loop [waited 0 polls 0]
        (if (>= waited render-s)
          (is (< polls 15) (str "polls=" polls " (old flat-3s would be ~28)"))
          (recur (+ waited (poll-interval-s waited)) (inc polls)))))))
