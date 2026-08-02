(ns murakumo.infer.prices-test
  "掲示価格レジストリの検査。

  ADR-2608026200 §5-5 の不変条件を『主張』ではなく『テスト』にする:
    1. 寄与率 ≥ 30%（breakage ゼロ・利用率 100% の最悪ケース）
    5. 掲示 ÷ 原価 ≥ 1.8

  加えて、このレジストリが存在しなかった間に実際に効いていた 2 つの壊れた
  価格（text $10,000/Mtok、image $1.00/枚）が二度と通らないことを固定する。"
  (:require [clojure.test :refer [deftest testing is]]
            [murakumo.infer.prices :as p]
            [murakumo.infer.credits :as credits]))

(def reg (p/load-registry))

(deftest registry-loads
  (testing "resources/murakumo/prices.edn が読める"
    (is (map? reg))
    (is (= 100 (p/peg-credits-per-usd reg)) "ペグは $1 = 100cr（ADR-2607030030）"))
  (testing "全 modality が登録されている（3D の『保留』も解消済み）"
    (doseq [m [:text :video :image :model3d :voice :music]]
      (is (map? (get reg m)) (str m " が無い")))))

(deftest floor-invariant
  (testing "寄与率 30% floor を割る掲示価格が 1 件も無い（ADR-2608026200 §5-5 不変条件 1）"
    (let [bad (p/below-floor reg 0.30)]
      (is (empty? bad)
          (str "floor 割れ: " (pr-str (vec bad)))))))

(deftest markup-invariant
  (testing "掲示 ÷ 原価 ≥ 1.8（§5-5 不変条件 5。fal 値上げ耐性）"
    ;; promo は意図的な原価割れなので対象外（上限つきで別管理、§3-2）
    (doseq [[modality models] (select-keys reg [:video :image :model3d :voice])
            [model-id m] models
            :let [cost (some m [:cost/usd-per-second :cost/usd-per-image
                                :cost/usd-per-asset :cost/usd-per-ktext])
                  price (some m [:credit/per-video-second :credit/per-image
                                 :credit/per-asset :credit/per-ktext])]
            :when (and cost price)]
      (let [ratio (/ (p/credits->usd reg price) cost)]
        (is (>= ratio 1.8)
            (str modality "/" model-id " の掲示÷原価 = " ratio))))))

(deftest fail-closed-on-unpriced
  (testing "未登録 model は例外。沈黙が『無料』にも『最小整数』にもならない"
    (is (thrown-with-msg? Exception #"no published price"
                          (p/price-for reg :video "not-a-real-model")))
    (is (thrown-with-msg? Exception #"unknown modality"
                          (p/price-for reg :hologram "x")))))

(deftest stale-registry-refuses-to-quote
  (testing "原価が古いレジストリでは見積もらない（ADR-2608026000）"
    (is (false? (p/stale? reg "2026-08-02")))
    (is (false? (p/stale? reg "2026-10-30")) "90 日以内は fresh")
    (is (true?  (p/stale? reg "2026-12-01")) "90 日超は stale")
    (is (thrown-with-msg? Exception #"stale"
                          (p/price-for reg :video "seedance-2.0" "2026-12-01")))))

(deftest the-two-broken-prices-cannot-come-back
  (testing "text: 1 credit/token（= $10,000/Mtok、実勢の 20,000 倍）は二度と価格にならない"
    (let [m (p/price-for reg :text "murakumo-main")]
      (is (= 40 (:credit/per-mtoken-out m)))
      (is (= 10 (:credit/per-mtoken-in m)))
      (is (nil? (:credit/per-token m))
          "per-token を持たせない —— i64 の最小値が価格になる事故の再発源")
      ;; $0.40/Mtok = 40cr。旧価格 $10,000/Mtok = 1,000,000cr との比。
      (is (= 25000.0 (/ 1000000.0 (:credit/per-mtoken-out m)))
          "旧価格は新価格の 25,000 倍だった")))
  (testing "image: 即値 100cr（$1.00/枚）は 6cr（$0.06）になった"
    (is (= 6 (:credit/per-image (p/price-for reg :image "animagine-xl-4.0"))))))

(deftest seedance-is-priced
  (testing "コードで結線済みの唯一の hosted engine（ADR-2607170500）に値段がある"
    (doseq [id ["seedance-2.0" "seedance-2.0-fast" "seedance-2.0-ref" "seedance-2.0-1080p"]]
      (let [m (p/price-for reg :video id)]
        (is (pos? (:credit/per-video-second m)) (str id " が未価格"))
        (is (= :seedance (:engine m))))))
  (testing "reference-to-video は fal の ×0.6 を反映して標準より安い"
    (is (< (:credit/per-video-second (p/price-for reg :video "seedance-2.0-ref"))
           (:credit/per-video-second (p/price-for reg :video "seedance-2.0"))))))

(deftest job-cost-uses-the-registry
  (testing "レジストリの map がそのまま job-cost に渡せる"
    (let [m (p/price-for reg :image "seedream-v4")]
      (is (== 24.0 (credits/job-cost m {:images 4})) "6cr × 4 枚"))
    (let [m (p/price-for reg :video "wan2.2-ti2v-5b")]
      (is (== 50.0 (credits/job-cost m {:video-seconds 5})) "10cr × 5 秒")))
  (testing "価格の無い unit は job-cost が例外にする（既存の規律が生きている）"
    (is (thrown-with-msg? Exception #"missing price"
                          (credits/job-cost (p/price-for reg :image "seedream-v4")
                                            {:video-seconds 5})))))

(deftest veo-promo-is-bounded
  (testing "frontier promo は原価割れなので上限が付いている（ADR-2608026200 §3-2）"
    (let [m (p/price-for reg :video "veo-3.1")]
      (is (= 35 (:promo/credit-per-video-second m)))
      (is (= 0.15 (:promo/plan-credit-share m)) "plan 月次 credits の 15% まで")
      (is (< (p/credits->usd reg (:promo/credit-per-video-second m))
             (:cost/usd-per-second m))
          "promo は原価を下回る = 意図的な原価割れであることを固定する"))))
