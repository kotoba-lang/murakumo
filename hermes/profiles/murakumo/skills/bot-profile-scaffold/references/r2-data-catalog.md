# R2 Data Catalog (Iceberg) write playbook

Target: Cloudflare R2 Data Catalog on bucket `cloud-itonami-datalake`, account
`4da88288dc30d9ee257f319d3c33ecf0` (wrangler whoami で確認できる）。Python は
`/opt/homebrew/bin/python3`（pyarrow + pyiceberg が入っている唯一の interpreter。
/usr/local/bin/python3 には無い）。

## Connection

`scripts/datalake_catalog.py` を共有接続として使う（account/bucket/warehouse/token
解決のみ共有し、書き方は consumer 毎に別実装を許す設計 — 型付き schema と list 列を
捨てる単一 loader に畳まない）。

- token 解決順: `CF_CATALOG_TOKEN` env → Keychain `security find-generic-password
  -s gftd.cf -a API_TOKEN -w`。他の fallback を作らない（通らない credential への
  静かなフォールバックは失敗を「謎の 401」に変える）。
- **wrangler の OAuth session は metadata 面だけ通り storage 面で 401** —
  `GET /v1/config` は 200 でも `create_table` が落ちる。使えない。
- 403 Forbidden（payload が生テキストの `Forbidden`）が catalog 面で出たら最初に
  token 失効を疑う。`/user/tokens/verify` が 401 Invalid を返す併発が確定条件。
  cfat_ token は他 session が rotate していることがある — owner に新 token を
  要求し、Keychain `gftd.cf`/`API_TOKEN` を更新する。

## Write pattern (proven)

```python
from datalake_catalog import connect, ensure_namespace
cat = connect()
ensure_namespace(cat, NAMESPACE)          # already-exists は報告して握り潰さない
try:
    t = cat.load_table(ident)             # exists
except Exception:
    t = cat.create_table(ident, schema=SCHEMA)
```

- schema は明示 pyarrow（`pa.list_(pa.string())` と int64 を含めて）— 型推論に任せない。
- upsert: stable `record_id`（入力キーの sha256）で後勝ち。既存行の削除は
  `t.delete(delete_filter=In(term="record_id", values=[...]))` — **文字列 SQL を
  渡すと NotImplementedError**。Pyright の型 stub は `values`/`literals` を
  勘違いするので runtime 実測で確認する。
- append 後は必ず readback: `cat.load_table(ident).scan().to_arrow()` で今回の
  全 record_id と総行数を照合。missing>0 は失敗。**0 件の書き込みは成功として
  報告しない**（sync script を exit 1 にする）。
- 冪等性テストは同一入力の再実行で行数不変 + missing=0 を見る。
- 既存の先行実装: `scripts/ses-mail-datalake-sync.py`（upsert 型）と
  `scripts/datalake-sync.py`（全置換型）。新しい consumer はどちらかの契約を
  名指しで選ぶ。

## CSV/BQ ソースを R2 へ載せる（datalake-sync.py を使わない経路）

`scripts/datalake-sync.py` は **JSON ファイル**しか読まない（`json.loads(p.read_text())`）。
外部 DB から CSV を出して載せる場合は渡さない — `JSONDecodeError` で落ちる。CSV → Arrow →
PyIceberg append を直接書く（2026-09-08 BQ→R2 で実測）:

```python
import pyarrow.csv as pa_csv
from datalake_catalog import connect
cat = connect()
ident = ("cloud_itonami", f"bq_public_{dataset}_{table}")
arr = pa_csv.read_csv(csv_path)
try:
    t = cat.load_table(ident); t.append(arr)      # 2 回目以降
except Exception:
    t = cat.create_table(ident, schema=arr.schema); t.append(arr)  # 初回
n = cat.load_table(ident).scan().to_arrow().num_rows   # readback 必須
```

- **append は重複を防がない。** フル export を毎回 append すると再実行で行が倍になる。
  冪等性は load 側でなく**呼び出し側の progress ledger**（一度 ok のテーブルを再実行しない）
  が担保する。テストでリセットして再実行すると読めば分かる重複を作る — それ自体は
t 正常（progress が cron では防ぐ）。
- **スキップは成功と同形にしてはならない。** BigQuery の RECORD/ARRAY 列は sandbox の
  CSV 出力で不能（`Error printing table: Cannot print record field` — しかも **exit 0** で
  返ることがある）、schema 無しテーブル（external/virtual）は `COUNT(*)` が
  `does not have a schema` で失敗。どちらも `SKIP:+理由` として progress に記録し、
  `Ok`/`SKIP`/`ERR` を出力で区別する（8-問 4）。
- **BigQuery の chunk は `|> LIMIT n |> OFFSET m` が sandbox で失敗する**
  （`expected JOIN`）。`|> LIMIT n` は通るが OFFSET は通らない環境がある —
  250k スライス 1 本に留め、多チャンク paging は `ORDER BY` 実列が要る（異種テーブルに無い）。

### bq CLI の呼び出し規律（複数回の取り込みで実測）

- **bq は同一シェルで連続呼び出しすると gcloud auth が落ちる。** 毎回 fresh
  subprocess で、`gcloud config unset project`（ls / discover 用）か `gcloud config
  set project jun784`（query / export 用）を先行させる。unset のまま query は
  `Cannot start a job without a project id`、set のまま ls は
  `Not found: Dataset jun784:...` になる。
- **`python bq.py` 直起動は credential_loader の PYTHONPATH 衝突で壊れる**
  （`ImportError: cannot import name 'bq_error'`）。PATH の `bq` shell wrapper 経由で
  起動し、`env -u PYTHONPATH -u HERMES_HOME` で Hermes の PATH 汚染を外す。
- **superproject root（や開発用の cwd）から叩くと `bigquery-public-data` を
  jun784 の dataset と誤読する**。`cwd=/` から実行する。
- データセット一覧は `bq ls bigquery-public-data`（プロジェクト名を付けない）。
  テーブル一覧も同じ形 `bq ls bigquery-public-data:<ds>`。1 回の連続ループで
  数十回 bq を回すのは Hmac だったので、数件ずつに分ける。

## Diagnosis quick table

| 症状 | 正体 | 対処 |
|---|---|---|
| catalog 403 `Forbidden` + verify 401 Invalid | token 失効 | owner が新 token 発行 → Keychain 更新 |
| catalog 403 + error code 1010 | UA を変えても直らなければ権限拒否 | token 権限（R2 Data Catalog: Edit + Workers R2 Storage: Edit）を確認 |
| verify が通るのに create_table が 401 | wrangler OAuth で metadata 面だけ通っている | API token に置き換える |
| `ModuleNotFoundError: pyarrow` | /usr/local/bin/python3 を使っている | `/opt/homebrew/bin/python3` を明示 |
| DuckDB attach も 403 | token 失効の裏取りになる | pyiceberg と同じ token 問題 |
