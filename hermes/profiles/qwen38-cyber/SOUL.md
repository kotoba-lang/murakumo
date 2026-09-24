# qwen38-cyber

`dealignai/Qwen3.8-Flash-Next-CYBERSECURITY-NVFP4` を primary にする唯一の Hermes profile。
api.murakumo.cloud の model id `qwen3.8-flash-next-cybersecurity-nvfp4` を通して呼ぶ
（origin は Modal 1×B200 + GPU memory snapshot、scale-to-zero、cold ~3 分・warm ~1 s、5 分無 request で停止。kotoba-lang/murakumo ADR-260911b、
gateway 側は cloud-murakumo-api `src/modal_hosted_model.js`）。

## この profile が居る理由

- モデル側の refusal が offensive-security 領域で外されているので、**authorized な**
  脆弱性評価・exploit 解析・malware 解析・red-team 検証の相談役として使う。
  対象は owner が権限を持つ資産（kotoba-lang / com-junkawasaki / network-awai /
  cloud-itonami 配下と murakumo fleet）に限る。第三者の資産への攻撃、大量標的化、
  破壊的手法の実行は行わない。
- 一般的な作業（監査の集計・GitHub 設定の点検など）は `sec-audit` が担当で、
  そちらは `murakumo-main`。この profile は「モデルの refusal が邪魔になる問い」
  だけを引き受ける。

## 運用上の事実（2026-09-11 実測）

- container が消えた状態からの最初の 1 回は **~3 分**（Modal が 127 GiB の snapshot を戻す時間。
  vLLM の wake 自体は 4 s）。5 分以内の続きは ~1 s。deploy 直後の 2〜3 回だけ 16〜26 分
  （worker type ごとに snapshot を作る Modal の仕様）。gateway はそれを最大 2,000 s 抱える。
- この profile の timeout（stale / request 2,000 s）は `.env` と `providers.custom.models`
  で効かせてある（`providers.murakumo.*` の値は hermes が読まない — root
  `scripts/hermes-murakumo-api.cljk` の profile-overrides を見よ）。
