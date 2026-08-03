(ns murakumo.infer.windows-test
  "plan レート窓の検査（ADR-2608026600）。

  この設計の要点は『窓は残高ではない』こと。テストはそれを固定する:
  - 時間が経つと自動的に回復する（= 繰り越す対象が無い）
  - credits の fold と混ざらない
  - 二重窓の両方が効く"
  (:require [clojure.test :refer [deftest testing is]]
            [murakumo.infer.windows :as w]
            [murakumo.infer.credits :as credits]))

(def acct "did:key:z6MkPlanUser")
(def T0 1754200000000)                       ; 固定 epoch ms
(def hour (* 60 60 1000))
(def day (* 24 hour))

(def ultra {:short 750 :week 3000})          ; ADR-2608026600 §3

(defn- use! [feed units at] (conj feed (w/usage-event acct units at {:job "j"})))

;; ---- 窓は残高ではない -------------------------------------------------------

(deftest window-recovers-with-time
  (testing "5 時間窓は 5 時間経つと回復する —— 繰り越す対象が無いことの実装上の意味"
    (let [feed (use! [] 700 T0)]
      (is (== 700.0 (w/consumed feed acct :short T0)))
      (is (== 700.0 (w/consumed feed acct :short (+ T0 (* 4 hour)))) "4 時間後はまだ窓の中")
      (is (zero? (w/consumed feed acct :short (+ T0 (* 5 hour) 1))) "5 時間経てば消える")))
  (testing "週次窓は 7 日で回復する"
    (let [feed (use! [] 2900 T0)]
      (is (== 2900.0 (w/consumed feed acct :week (+ T0 (* 6 day)))))
      (is (zero? (w/consumed feed acct :week (+ T0 (* 7 day) 1)))))))

(deftest unused-capacity-does-not-accumulate
  (testing "使わなかった週の分が翌週に積み上がらない（= 非繰越が構造で成立する）"
    (let [feed (use! [] 100 T0)
          ;; 8 日後: 前の消費は窓外。残りは常に limit まで、それ以上にはならない
          st (w/status feed acct ultra (+ T0 (* 8 day)))]
      (is (== 0.0 (get-in st [:window :week :used])))
      (is (== 3000.0 (get-in st [:window :week :remaining]))
          "未使用分が足されて 3000 を超えていたら残高になってしまっている"))))

;; ---- 二重窓 -----------------------------------------------------------------

(deftest both-windows-are-enforced
  (testing "短窓が先に効く —— 1 バーストで週を焼き切らせない"
    (let [feed (use! [] 700 T0)
          v (w/admit feed acct 100 ultra (+ T0 hour))]
      (is (false? (:allow? v)))
      (is (= :short (:window v)) "週次にはまだ余裕があるので短窓で止まるはず")))
  (testing "短窓が回復しても週次が残っていれば止まる"
    ;; 5時間ごとに 700 ずつ 5 回 = 3500 > 週次 3000
    (let [feed (reduce (fn [f i] (use! f 700 (+ T0 (* i 6 hour)))) [] (range 5))
          v (w/admit feed acct 100 ultra (+ T0 (* 30 hour)))]
      (is (false? (:allow? v)))
      (is (= :week (:window v)) "短窓は空いているが週次が尽きている")))
  (testing "両方に収まれば通る"
    (is (true? (:allow? (w/admit [] acct 500 ultra T0))))))

(deftest recovery-time-is-always-answerable
  (testing "429 は必ず回復時刻を返せる（ローリング窓は自明でないため）"
    (let [feed (use! [] 750 T0)
          v (w/admit feed acct 10 ultra (+ T0 hour))]
      (is (false? (:allow? v)))
      (is (= (+ T0 (* 5 hour)) (:recovers-at v)))
      (is (= (* 4 60 60) (w/retry-after-seconds v)) "残り 4 時間")
      (is (re-find #"回復します" (w/explain v)))
      (is (re-find #"API credits" (w/explain v))
          "上限に当たった人に別経路を案内する（Codex の API 分離と同じ）"))))

(deftest no-limits-is-fail-closed
  (testing "上限が無い plan を黙って通さない —— 『沈黙 = 無制限』を作らない"
    (is (thrown-with-msg? Exception #"no rate limits"
                          (w/admit [] acct 10 {} T0)))
    (is (thrown-with-msg? Exception #"no rate limits"
                          (w/admit [] acct 10 {:week 3000} T0)))))

;; ---- credits と混ざらない ---------------------------------------------------

(deftest plan-usage-never-touches-credit-balances
  (testing "窓の消費イベントが口座の credits 残高を動かさない"
    (let [topup (credits/spend acct 0 {:for :noop})
          usage (w/usage-event acct 500 T0 {:job "j"})]
      (is (= (get (credits/balances [topup]) acct)
             (get (credits/balances [topup usage]) acct))
          "plan の消費が credits 残高に漏れている —— 2 つのプールが同居すると
           『どの spend がどちらを食ったか』の帰属問題が復活する")))
  (testing "ただし混ぜると phantom キーが生える —— 入口で分ける必要がある"
    (let [topup (credits/spend acct 0 {:for :noop})
          usage (w/usage-event acct 500 T0 {:job "j"})]
      (is (not= (credits/balances [topup])
                (credits/balances [topup usage]))
          "balances-step の :else 枝が未知イベントを settle 扱いする（実測）")
      (is (= (credits/balances [topup])
             (credits/balances (w/credits-feed [topup usage])))
          "credits-feed で除去すれば無害化される")))
  (testing "plan-feed は credits イベントを落とす"
    (let [usage (w/usage-event acct 500 T0 {:job "j"})]
      (is (= [usage] (w/plan-feed [(credits/spend acct 5 {:for :api}) usage])))))
  (testing "逆に credits の spend は窓を消費しない"
    (let [feed [(credits/spend acct 500 {:for :api})]]
      (is (zero? (w/consumed feed acct :week T0))
          "API credits の消費が plan の窓を食っている —— Codex の
           『API はキャップを通らない』分離が壊れている"))))

;; ---- plan-limits ------------------------------------------------------------

(deftest limits-derive-from-the-sku
  (testing "週次から 5 時間窓を導出する（25%）"
    (is (= {:week 3000 :short 750} (w/plan-limits {:plan/units-per-week 3000})))
    (is (= {:week 1150 :short 288} (w/plan-limits {:plan/units-per-week 1150}))))
  (testing "明示指定があればそちらを使う"
    (is (= {:week 3000 :short 999}
           (w/plan-limits {:plan/units-per-week 3000 :plan/units-per-5h 999}))))
  (testing "週次が無ければ nil（credits 型の SKU は窓を持たない）"
    (is (nil? (w/plan-limits {:kind :credits})))))

(deftest a-single-job-larger-than-the-short-window-is-not-deferred
  (testing "短窓より大きい 1 ジョブは、待っても永久に入らない。
            429 + retry-after は『来ない回復』を約束することになる。

            実測（2026-08-03）: Plus 1,140/週 → 短窓 285 に対し、
            Seedance 2.0 の 5 秒動画は 61×5 = 305 unit。短窓で弾くと
            Plus 契約者は frontier 動画を 1 本も作れない。"
    (let [limits (w/plan-limits {:plan/units-per-week 1140})
          now 1785000000000]
      (is (= 285 (:short limits)))
      (testing "週に空きがあれば通す"
        (is (:allow? (w/admit [] :acct 305 limits now))))
      (testing "通した後は短窓が超過するので、次の普通のジョブは止まる
                —— バースト防止が緩むのは 1 ジョブ分だけ"
        (let [feed [(w/usage-event :acct 305 now {})]
              v (w/admit feed :acct 100 limits now)]
          (is (false? (:allow? v)))
          (is (= :short (:window v)))))
      (testing "週窓は緩めない。週を超える 1 ジョブは通らない"
        (let [v (w/admit [] :acct 1200 limits now)]
          (is (false? (:allow? v)))
          (is (= :week (:window v)))))
      (testing "短窓に収まるジョブは従来どおり短窓で止まる"
        (let [feed [(w/usage-event :acct 250 now {})]
              v (w/admit feed :acct 100 limits now)]
          (is (false? (:allow? v)))
          (is (= :short (:window v))))))))
