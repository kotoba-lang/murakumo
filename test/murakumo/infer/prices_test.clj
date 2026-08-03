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
    (let [m (p/price-for reg :image "seedream-v5-pro")]
      (is (== 56.0 (credits/job-cost m {:images 4})) "14cr × 4 枚"))
    (let [m (p/price-for reg :video "wan2.2-ti2v-5b")]
      (is (== 50.0 (credits/job-cost m {:video-seconds 5})) "10cr × 5 秒")))
  (testing "価格の無い unit は job-cost が例外にする（既存の規律が生きている）"
    (is (thrown-with-msg? Exception #"missing price"
                          (credits/job-cost (p/price-for reg :image "seedream-v5-pro")
                                            {:video-seconds 5})))))

(deftest veo-promo-is-bounded
  (testing "frontier promo は原価割れなので上限が付いている（ADR-2608026200 §3-2）"
    (let [m (p/price-for reg :video "veo-3.1")]
      (is (= 35 (:promo/credit-per-video-second m)))
      (is (= 0.15 (:promo/plan-credit-share m)) "plan 月次 credits の 15% まで")
      (is (< (p/credits->usd reg (:promo/credit-per-video-second m))
             (:cost/usd-per-second m))
          "promo は原価を下回る = 意図的な原価割れであることを固定する"))))

;; ── 課金経路が実際に使う 2 つ（find-model / quote-credits）──────────────────

(deftest find-model-refuses-to-guess
  (testing "課金経路は model id しか持たない。modality はレジストリが知っている"
    (is (= :video (:modality (p/find-model reg "seedance-2.0"))))
    (is (= :text (:modality (p/find-model reg "murakumo-main"))))
    (is (= :image (:modality (p/find-model reg "seedream-v5-pro")))))
  (testing "未登録は例外 —— 沈黙は『無料』にも『最小整数』にもなる"
    (is (thrown-with-msg? Exception #"no published price"
                          (p/find-model reg "model-nobody-priced"))))
  (testing "同名が 2 modality にあるなら片方を黙って選ばない"
    (let [ambiguous (-> reg
                        (assoc-in [:image "collide"] {:credit/per-image 6})
                        (assoc-in [:video "collide"] {:credit/per-video-second 10}))]
      (is (thrown-with-msg? Exception #"ambiguous"
                            (p/find-model ambiguous "collide"))))))

(deftest quote-credits-is-the-fail-closed-path
  (testing "実際の課金と同じ経路で値が出る"
    (is (== 305.0 (p/quote-credits reg "seedance-2.0" {:video-seconds 5}))
        "61cr/秒 × 5 秒 = $3.05")
    (is (== 56.0 (p/quote-credits reg "seedream-v5-pro" {:images 4})))
    (is (== 53.0 (p/quote-credits reg "murakumo-main" {:mtokens-in 0.3 :mtokens-out 1.25}))
        "10×0.3 + 40×1.25 = 53cr = $0.53"))
  (testing "2026-08-02 まで本番に開いていた 3 つの穴が、いずれも例外になる"
    (testing "(1) 未登録 model が 1 credit/token で課金される"
      (is (thrown-with-msg? Exception #"no published price"
                            (p/quote-credits reg "unpriced-model" {:tokens 300}))))
    (testing "(2) 単位が無い動画ジョブが 0 credits になる"
      (is (thrown? Exception (p/quote-credits reg "seedance-2.0" {}))))
    (testing "(3) 登録済み text model に :tokens を投げると default 1 に落ちる"
      (is (thrown-with-msg? Exception #"missing price"
                            (p/quote-credits reg "murakumo-main" {:tokens 300}))
          ":mtokens-in/:mtokens-out で課金する。:tokens には価格キーが無い"))))

(deftest embedded-and-resource-registries-are-the-same-file
  (testing "cljs 側の埋め込みと JVM 側の io/resource が同一 EDN を指す"
    (is (= (p/load-registry) reg)
        "load-registry が resources/murakumo/prices.edn を読んでいる")))

;; ── 2026-08-03 の再検証で見つかった穴（ADR-2608036900）────────────────────────

(deftest every-published-price-declares-a-cost
  (testing "価格があって原価が無い entry を作らない。

           below-floor は cost と price が両方そろった entry しか見ないので、
           原価を書き忘れた entry は『合格』ではなく『未検査』になる ——
           どちらも空リストに見えるのが危険で、区別できるようにした。"
    (let [bad (p/uncosted reg)]
      (is (empty? bad) (str "原価未宣言: " (pr-str (vec bad)))))))

(deftest job-minimum-is-actually-applied
  (testing "`:credit/job-minimum` は宣言されていたが誰も読んでいなかった。

           単価 0.1cr/秒 の music は、最低額が効かないと 5 秒の生成が 0.5cr
           = $0.005 で通る —— Stripe の固定費 $0.30 どころか、どんな決済手段の
           手数料にも届かない。"
    (is (== 5.0 (p/quote-credits reg "ace-step" {:audio-seconds 5}))
        "0.1cr × 5秒 = 0.5cr だが、job-minimum 5cr に切り上がる")
    (is (== 12.0 (p/quote-credits reg "ace-step" {:audio-seconds 120}))
        "最低額を超えたら通常計算（0.1 × 120 = 12cr）")))

(deftest vendor-billing-dimension-is-preserved
  (testing "fal が分単位・切り上げで課金する music を、秒割りで値付けしない。

           旧 entry は $0.0133/秒 という秒割り原価を宣言していたが、fal の実際は
           $0.80/分の切り上げ。30 秒の出力は実原価 $0.80 なのに、秒割りモデルでは
           $0.40 に見える。floor テストは『宣言された原価』しか見ないので、この
           次元不一致は検査を素通りしていた。"
    (let [m (p/price-for reg :music "elevenlabs-music")]
      (is (nil? (:credit/per-audio-second m))
          "秒単価を持たせない —— 持たせた瞬間に短いジョブが原価割れする")
      (is (= 160 (:credit/per-audio-minute m)))
      (is (= 60 (:cost/billing-granularity-seconds m))
          "ベンダの課金粒度を明示する")
      (is (== 160.0 (p/quote-credits reg "elevenlabs-music" {:audio-minutes 1}))
          "30 秒の出力も 1 分として課金する（fal がそう課金するから）"))))

(deftest measured-costs-match-the-vendor
  (testing "2026-08-03 に fal カタログから再取得した実価格と一致する"
    (is (== 0.10 (:cost/usd-per-second (p/price-for reg :video "veo-3.1-fast")))
        "fal-ai/veo3.1/fast は音声なしで $0.10/秒。旧宣言 $0.18 は過大だった")
    (is (== 0.40 (:cost/usd-per-second (p/price-for reg :video "veo-3.1")))
        "fal-ai/veo3.1 は音声ありで $0.40/秒")
    (is (== 0.2419 (:cost/usd-per-second (p/price-for reg :video "seedance-2.0-fast"))))
    (is (== 0.0675 (:cost/usd-per-image (p/price-for reg :image "seedream-v5-pro")))
        "現行は v5 pro。v4 は fal のカタログに存在しない"))
  (testing "原価が下がっても掲示価格は据え置き、粗利として受け取る"
    (let [m (p/price-for reg :video "veo-3.1-fast")]
      (is (= 35 (:credit/per-video-second m)))
      (is (> (p/contribution-rate reg 35 (:cost/usd-per-second m)) 0.65)
          "原価訂正で寄与率は 49% → 70% 台へ"))))
