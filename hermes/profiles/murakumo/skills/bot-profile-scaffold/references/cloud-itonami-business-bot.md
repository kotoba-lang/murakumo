# cloud-itonami 自律営業・分析 bot パターン

transport-efficiency 既存 profile を拡張して cloud-itonami の自律営業・マーケティング・
分析 bot へ展開する構成。

## 基本構成

**profile 名**: `transport-efficiency` (既存拡張)

**構成ファイル**:
- `profile.yaml` - gateway discovery 用 (description + description_auto: false)
- `SOUL.md` - bot の役割と行動範囲定義 (更新には owner 承認)
- `yakuwari.edn` - 権限表 (構造化、未記載は :blocked)
- `config.yaml` - model/provider 設定
- `scripts/transport_evidence.py` - データ収集 (read-only)
- `scripts/transport_marketing.py` - 営業・マーケティング分析
- `cron/jobs.json` - 登録された cron job 一覧

## cron job 構成

| job 名 | 頻度 | 役割 |
|---|---|---|
| `transport-tick` | 2 時間 | 物流効率データ収集 (MLIT, World Bank LPI 等) |
| `transport-marketing` | 6 時間 | 営業・マーケティング分析・ Outreach メッセージ作成 |

**頻度決定規則**: ソースの自然な変化間隔に合わせて調整。データ量でなく、変化の速さで頻度を決める。

## evidence script 出力形式

```
=== TRANSPORT EFFICIENCY DATA ===
MEASURE	mlit_lpi	UNMEASURED
MEASURE	mlit_lpi_url	https://lpi.worldbank.org/api/v1/scores

=== CLOUD-ITONAMI MARKET SIGNALS ===
MEASURE	itonami_cloud_status	UNMEASURED
MEASURE	itonami_cloud_status_url	https://itonami.cloud/status
```

## 権限表 (yakuwari.edn 型)

```edn
{:yakuwari/capabilities
 [{:capability :read_data :decision :ok}
  {:capability :propose_outreach :decision :ok}
  {:capability :propose_product_improvement :decision :ok}
  {:capability :publish :decision :blocked}
  {:capability :send_outbound :decision :blocked}]}
```

**規則**: 未記載 capability は :blocked として処理。

## SOUL.md 拡張パターン

既存の「観測者」から「営業・分析 bot」へ拡張するときは:

1. 行動範囲を明記 (データ収集 / 営業 / 分析 / 改善提案)
2. 権限範囲を明記 (propose のみ / publish 禁止)
3. 報告書式を維持 (測った/測れていないを区別)

**注意**: SOUL.md 更新は owner 承認が必要。承認前は evidence script / cron / profile.yaml は正常運用可能。

## データ収集戦略

**外部 API**: cron job は unattended で HTTP call が BLOCKED される。
  - 事前に別 job でデータを pull してローカルに置く
  - evidence script はローカルファイルのみ読み取る

**ローカルデータパス**:  `/tmp/*.json` (crawler が事前に収集)

## 改善提案フロー

1. evidence script でデータ収集
2. cron job が LLM に分析を依頼
3. 改善点を SOUL.md に基づき提案
4. 提案は人間のレビュー/承認待ち (propose のみ)

## 運用チェックポイント

- cron job の execution 状態: `~/.hermes/profiles/<name>/cron/executions.db`
- bot の最終報告: `state.db` の messages テーブル (最新 row の content)
- 認証エラー: `agent.log` に「No LLM provider configured」等のメッセージ
- gateway 再起動後: `~/.hermes/gateway_state.json` の `served_profiles` に名が載ったか確認
