(ns murakumo.infer.prices
  "掲示価格レジストリ（`resources/murakumo/prices.edn`）の読み出しと参照。

  ── なぜこの ns が要るのか ──

  `murakumo.infer.credits/job-cost` は『model map の `:credit/per-*` を引き、
  無ければ例外』という正しい規律を持っていたが、**その map を供給する側が
  存在しなかった**。結果、実際に効いていた価格は次の 2 つだけだった:

  - text : kotoba oracle の `default-per-token` = `:i64 1` → $0.01/token
           = $10,000/Mtok = 実勢の 20,000 倍。**誰も選んでいない** ——
           `:i64` の正の最小値が 1 で、それがそのまま価格になっていた
  - image: `murakumo.infer.media/cmd-generate` の即値 `:credit/per-image 100`
           = $1.00/枚 = fal 原価 $0.03 の 33 倍

  video / audio / 3D は価格が無く、`job-cost` は仕様どおり例外を投げていた。
  つまり **売れる状態にあったのは、桁が違う 2 つの価格だけ**だった。

  この ns は EDN を読むだけで、価格の判断は持たない（判断は ADR-2608026200 /
  ADR-2608026500、値は prices.edn）。`spec.cljc` が `murakumo.edn` を読むのと
  同じ分担。

  ── staleness ──

  原価は他社の公開価格に依存するので必ず古くなる。`:verified-at` が
  `:staleness-days` を超えたレジストリで見積もりを出すのは『間違った数字を
  自信をもって返す』ことなので、`price-for` は例外を投げる（ADR-2608026000）。
  掲示価格そのものは固定でよいが、それが今も黒字かは原価の鮮度に依存する。"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

(def resource-path "murakumo/prices.edn")

(defn load-registry
  "prices.edn を読む。引数で解析済み map を渡せる（cljs / テスト用）。"
  ([] (load-registry nil))
  ([parsed]
   (or parsed
       #?(:clj (some-> (io/resource resource-path) slurp edn/read-string)
          :cljs (throw (ex-info "load-registry requires JVM; pass the parsed registry"
                                {:path resource-path}))))))

(defn- days-between
  "ISO date 文字列 2 つの日数差（`from` → `to`）。時刻は持たない。"
  [from to]
  (letfn [(->days [s]
            (let [[y m d] (map #(#?(:clj Long/parseLong :cljs js/parseInt) %)
                               (str/split s #"-"))
                  ;; civil-days（Howard Hinnant のアルゴリズム）。うるう年を正しく扱う。
                  y' (if (<= m 2) (dec y) y)
                  era (quot (if (>= y' 0) y' (- y' 399)) 400)
                  yoe (- y' (* era 400))
                  doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
                  doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
              (+ (* era 146097) doe -719468)))]
    (- (->days to) (->days from))))

(defn stale?
  "レジストリが `today`（ISO 文字列）時点で陳腐化しているか。"
  [registry today]
  (let [limit (:staleness-days registry)
        at    (:verified-at registry)]
    (boolean (and limit at (> (days-between at today) limit)))))

(defn assert-fresh!
  "陳腐化していたら例外。silent に古い原価で見積もらない。"
  [registry today]
  (when (stale? registry today)
    (throw (ex-info "price registry is stale — re-verify provider costs before quoting"
                    {:verified-at (:verified-at registry)
                     :staleness-days (:staleness-days registry)
                     :today today})))
  registry)

(defn price-for
  "modality（:text/:video/:image/:model3d/:voice/:music）と model id →
  `job-cost` に渡せる model map。

  未登録なら例外 —— `job-cost` が価格キー欠落で例外を投げるのと同じ規律を、
  レジストリ参照の段でも守る。**沈黙は『無料』にも『最小整数』にもなりうる**
  ので、どちらも起こさない。"
  ([registry modality model-id] (price-for registry modality model-id nil))
  ([registry modality model-id today]
   (when today (assert-fresh! registry today))
   (let [by-modality (get registry modality)]
     (when-not by-modality
       (throw (ex-info "unknown modality in price registry"
                       {:modality modality :available (set (keys registry))})))
     (or (get by-modality model-id)
         (throw (ex-info "model has no published price — refusing to quote"
                         {:modality modality :model model-id
                          :available (set (keys by-modality))}))))))

(defn peg-credits-per-usd [registry]
  (get-in registry [:peg :credits-per-usd]))

(defn usd->credits [registry usd]
  (* (double usd) (peg-credits-per-usd registry)))

(defn credits->usd [registry credits]
  (/ (double credits) (peg-credits-per-usd registry)))

(defn contribution-rate
  "掲示価格に対する寄与率 = 1 − 原価/(掲示 × (1 − 収入側費用))。
  収入側 4.4% = Stripe 2.9% + Tax 0.5% + チャージバック引当 1.0%
  （ADR-2608026200 §4）。ADR-2608026200 §5-5 の floor は 0.30。"
  ([registry price-credits cost-usd] (contribution-rate registry price-credits cost-usd 0.044))
  ([registry price-credits cost-usd rev-side]
   (let [price-usd (credits->usd registry price-credits)]
     (if (pos? price-usd)
       (- 1.0 (/ (double cost-usd) (* price-usd (- 1.0 rev-side))))
       0.0))))

(defn below-floor
  "floor（既定 0.30）を割る登録価格を全部返す。捏造ではなく機械検査で
  『赤字にならない』を保証するための関数（ADR-2608026200 §5-5 不変条件 1）。
  → [{:modality m :model id :price c :cost usd :contribution r}]"
  ([registry] (below-floor registry 0.30))
  ([registry floor]
   (let [cost-keys [:cost/usd-per-second :cost/usd-per-image :cost/usd-per-asset
                    :cost/usd-per-ktext :cost/usd-per-mtoken-out]
         price-keys [:credit/per-video-second :credit/per-image :credit/per-asset
                     :credit/per-ktext :credit/per-mtoken-out :credit/per-audio-second]]
     (for [[modality models] registry
           :when (map? models)
           [model-id m] models
           :when (map? m)
           :let [cost (some m cost-keys)
                 price (some m price-keys)]
           :when (and cost price (pos? price))
           :let [r (contribution-rate registry price cost)]
           :when (< r floor)]
       {:modality modality :model model-id :price price :cost cost :contribution r}))))
