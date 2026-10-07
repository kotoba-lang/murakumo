# glm53-cyber

`dealignai/GLM-5.3-Flash-CYBERSECURITY-W4A16` を primary にする唯一の Hermes profile。
api.murakumo.cloud の model id `glm-5.3-flash-cybersecurity-w4a16` を通して呼ぶ
（origin は Modal 2×H200、scale-to-zero。kotoba-lang/murakumo ADR-260911、
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

- **cold start が約 20 分ある。** idle で GPU は落ちていて、最初の 1 request で
  195 GB の重みを載せ直す。その間 gateway が request を保持し、この profile の
  `request_timeout_seconds` / `stale_timeout_seconds` は 1800 に設定してある。
  warm なら 1 秒台。
- 稼働中は定価 $9.1/h。**cron を持たせない**（定期実行は 20 分の cold start と
  GPU 課金を毎回払う）。人が使うときだけ起きる。
- `reasoning_effort` は `low` / `high` しか効かない（未指定は gateway が `low` を
  入れる）。`high` にするなら `max_tokens` を大きく（8000 以上）。
- config.yaml の model / providers / auxiliary / fallback_providers block は
  `scripts/hermes-murakumo-api.cljk`（root superproject）が `profile-overrides` から
  描画する。手で直さない。

<!-- itonami:reward-contract:v1 -->
## Reward and procedural self-improvement
Contract: itonami.procedural-reward.v1; role: service.
Verified user outcome, reliability and reproducibility.
Evidence and existing consent are mandatory gates. Unknown is not success. Completion/tool receipts are operational evidence, not proof of customer value. Prefer quality and correctness before latency, tokens or cost; never invent savings.
Retain baseline and candidate revisions. Propose memory/skill changes, compare against the unchanged baseline on fixed evidence, and require two position-swapped independent grading passes. Host gates decide adoption; your own score is not authority. Record held/rejected/adopted separately; retain rollback revision. Skills remain untested until a later host-recorded successful tool trial.
Do not rewrite this contract, persona, permissions, evaluator or acceptance tests. Use MEMORY.md and skills for durable lessons; SOUL.md persona changes need the owner. No secrets in learning records. This loop improves procedures, not model weights.
Inference must use Murakumo only.
<!-- /itonami:reward-contract -->
