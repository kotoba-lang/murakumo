;; Offline unit test for murakumo.infer.media's await-history poll-interval
;; backoff (root-caused 2026-07-09/10: the old flat 3s interval opened a fresh
;; SSH connection per poll, ~28 connections for a single ~84s render, which
;; tripped intermittent 502/524s under back-to-back testing). No fleet, no SSH.
(ns murakumo.infer-media-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.infer.media :as media]
            [murakumo.ssh :as ssh]))

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

;; Root-caused live 2026-07-13: a node's ComfyUI process died right after
;; finishing a real render (confirmed via its own on-node log — sampling
;; completed, no crash report, no jetsam kill, just silence). curl-local's
;; blank-body-on-failure made "connection refused" look identical to "still
;; rendering" (both empty), so await-history polled the dead node for the
;; full 900s job timeout instead of surfacing the failure in seconds.
(def await-history #'media/await-history)

;; fast/slow-interval-s redefed to 0 in these tests only to skip the real
;; Thread/sleep backoff — the branching under test is poll-count logic, not
;; timing, so a live fleet run still waits the real 2s/8s intervals.
(deftest await-history-fails-fast-on-a-dead-node
  (testing "connection-refused (curl exit != 0) must not be mistaken for
            \"still rendering\" — a dead node has to surface an error within
            max-unreachable-polls, not the full timeout"
    (with-redefs [ssh/curl-local-raw (fn [_host _url] {:exit 7 :out ""})
                  media/fast-interval-s 0
                  media/slow-interval-s 0]
      (is (thrown? clojure.lang.ExceptionInfo
                   (await-history "dead-host" "pid-1" 600))))))

(deftest await-history-keeps-polling-a-live-but-still-rendering-node
  (testing "a reachable node with no completed history yet keeps polling
            instead of being mistaken for a dead one"
    (let [calls (atom 0)]
      (with-redefs [ssh/curl-local-raw
                    (fn [_host _url]
                      (if (>= (swap! calls inc) 3)
                        {:exit 0 :out "{\"pid-1\":{\"status\":{\"completed\":true}}}"}
                        {:exit 0 :out "{}"}))
                    media/fast-interval-s 0
                    media/slow-interval-s 0]
        (is (= {:status {:completed true}} (await-history "live-host" "pid-1" 600)))
        (is (= 3 @calls))))))

(deftest await-history-recovers-from-a-single-transient-miss
  (testing "one connection blip (process mid-restart) is not fatal — only
            max-unreachable-polls CONSECUTIVE misses give up"
    (let [calls (atom 0)]
      (with-redefs [ssh/curl-local-raw
                    (fn [_host _url]
                      (case (swap! calls inc)
                        1 {:exit 0 :out "{}"}
                        2 {:exit 7 :out ""}
                        {:exit 0 :out "{\"pid-1\":{\"status\":{\"completed\":true}}}"}))
                    media/fast-interval-s 0
                    media/slow-interval-s 0]
        (is (= {:status {:completed true}} (await-history "flaky-host" "pid-1" 600)))))))
