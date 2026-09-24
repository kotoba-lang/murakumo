---
name: fleet-model-tier-assignment
description: Use when re-assigning fleet models by job type via τ²-bench.
---

# fleet-model-tier-assignment

fleet 全 job に性質 tag を付け、τ²-Bench(agentic tool-call)実測でモデルを割り当てる手順。
2026-09-09 実施済み (C案)。再割当のときはこの手順を繰り返す。

## 手順

1. **job 実測**: 全 `<profile>/cron/jobs.json` から enabled job を収集 (job 名/prompt 先頭/model/provider/schedule)。`.bak-*` profile 除外、`jobs.json` だけ読む (glob 禁止)。
2. **tag 分類** (名前+prompt 先頭の keyword で決定論的に):
   - research-loop (co-scientist/falsify) / code-critical (merge・review・pin・dispatch 判断) / code-maint (maint tick・build) / crawl-classify (scout・ingest・分類) / ops-watch (heartbeat・watchdog) / analysis (経済・kaizen) / write-publish (公開 content) / knowledge-pipeline (hyakka ontology) / misc
   - 「maint tick だが keyword で critical に引っかかる」profile は FLOOR_FORCE set で明示的に落とす。
3. **τ²-Bench は benchmarks API で引く**: `GET /api/v1/benchmarks?source=openrouter&task_type=agentic` (permaslug は日付付き — models API の generic slug に mapping が必要)。web ページの leaderboard より API が正。
4. **価格は models API 全リストから client-side filter** (per-model endpoint は 404)。`pricing.prompt`×1e6 で $/1M。
5. **適用は job 単位で CLI 経由**: `hermes -p <p> cron edit <id> --profile <p> --provider openrouter --model <id>`。config.yaml 直編集は model.default だけ拒否されるが fallback/auxiliary block は sed 可 (.bak-stamp 残す)。CLI edit は snapshot を null にして drift_skip を防ぐ。1 job ~0.4s、235 job で ~2 分、pending 差分検出で再開可能。
6. **verify は 2 方向**: manifest との match 数 + fleet 全体に旧 slug pin が残 0。両方数えて報告。
7. **gateway restart** で in-memory config 刷新 (restart しないと job が旧 provider で resolve し続ける)。
8. **検証は scheduler log が正本**: jobs.json の last_status は executions.db 競合で stale になる。restart 後に発火した job の ok 率を数える。

## 2026-09-09 の割当 (C案)

| tag | jobs | model | 根拠 (τ² airline) |
|---|---|---|---|
| research-loop | 11 | z-ai/glm-5.3 | 80.0% / $0.091 |
| code-critical | 21 | stepfun/step-3.7-flash | 77.3% / $0.020 Pareto |
| code-maint+crawl+ops+misc | 193 | z-ai/glm-5.3-flash | 73.3% / $0.0048 floor支配 |
| analysis+write-publish | 7 | qwen/qwen3.8-27b | 78.7% / $0.082 |
| knowledge-pipeline | 3 | google/gemma-4-31b-it | 76.1% / $0.016 |

manifest: `~/.hermes/profiles/itonami/model-assignment-proposal.json` (job→tag→model)
baseline: `~/.hermes/profiles/itonami/model-assignment-baseline.json` (適用前 status/streak)

## 監視 bot (2026-09-09 立上げ済み)

profile `fleet-model-watch` が hourly で測定する (job 231c4ac61441, no-agent — model token 0):

- script: `~/.hermes/scripts/model_health.py` (= profile scripts/ と同一。cron は ~/.hermes/scripts/ 相対名しか受けない)
- ledger: `~/.hermes/profiles/fleet-model-watch/scripts/model-health-ledger.jsonl` (append-only, window 差分)
- manifest の copy は profile root 直下 (script は `os.path.dirname(HERE)` で解決)
- job は `--no-agent --deliver local` で登録。schedule 変更後は gateway restart で multiplexer に読ませる
- 実測 2026-09-09: restart 後 423 runs 全 model 100% ok (126 job は daily/weekly で未発火)
- ⚠ 未確認: multiplexer による 14:01 自然発火 (cron list で登録は確認済み)。次回 health check で ledger が 1h ごとに増えているかを見る

## 罠

- **fallback_providers の 9180 は config 内に複数形で居る**: fallback entry 単体 / auxiliary (vision・compression・approval・mcp・title_generation・triage_specifier) の base_url。fallback entry は regex で削除、auxiliary は `api.murakumo.cloud/v1` に repoint。
- **profile config の `providers:` block は global を shadow する**: profile が providers block を持つと global の `openrouter-free` が解決できず `Unknown provider` で fallback に落ちる。fallback が死んでいると Connection error。
- **hyakka の resolve_free_model.cljs は job id を DEFAULT profile の jobs.json から取る** → `hermes cron edit` に `--profile default` が必須 (2026-09-09 patch 済)。
- **cron run は同期 block する** — 検証は jobs.json/log を読む方針で、発火待ちで cell timeout させない。
- model.default 一括変更後、unpinned job は drift_skip するが、CLI edit (job-level pin) は snapshot=null にするので drift しない。
