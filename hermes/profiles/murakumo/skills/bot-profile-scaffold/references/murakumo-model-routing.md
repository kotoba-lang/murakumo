# murakumo の model routing と alias 切替の実体

`api.murakumo.cloud`（cloud-murakumo-api worker、`src/local_murakumo/worker.cljs`）で
「どの model がどの backend に行くか」を決める時に必ず読む。bot の cron model を
murakumo-main で使う設計もここに従う。

## 中心の事実: KV alias は推論 routing を変えない

- `GET/PUT /infer/models/:id` の KV エントリは **dashboard / catalog 表示用の status
  宣言**であって、推論経路の決定に使われない（ADR-2607173100 の「切替 = 1 KV PUT」
  は dashboard 側の話。推論は別）。
- 実推論の経路は worker の **`resolve-endpoint` / `main-pool-model?` /
  `body-for-origin` / `model-admission!`** が決める。ここは KV を読まずコード中の
  条件で分岐する。

## murakumo-main は owned pool にハードコード

- `murakumo-main` / `nil` / `default-model-id`（`qwen3.8-27b`）の 3 つは
  `main-pool-model?` が true → `resolve-endpoint` で固定 `default-infer-endpoint`
  （owned qwen pool）へ。**KV alias を PUT しても推論は一切変わらない**。
- 「murakumo-main の backend を差し替える」には worker の routing コード変更が必須。
  実装パターン: **エントリポイント（`messages-route` / `chat-completions-route`）で
  body の `:model` を rewrite する env toggle**（例 `MURAKUMO_MAIN_BACKEND=basho` →
  murakumo-main/nil/default を `awai-network/basho` に書き換え、下流の
  `self/self-model-id?` 分岐に乗せる）。デフォルト OFF なら既存 owned pool は
  byte-identical で不変 → 安全に PR/deploy できる。

## basho（awai-network/basho）は reserved self-model

- `self-models.cljc` の `basho-model-id`。**公開 identity として serve されるが、
  従量課金（Modal GLM-5.3-Flash 等の vendor backend）で、月次予算ゲートと
  identity ゲートが義務**。
- `identity-gate` が要求する paying identity: `#{:passkey-session :murakumo-biscuit
  :cacao :mk1 :shared-token}`。つまり**共有 token（ANTHROPIC_PROXY_TOKEN）でも
  identity は通る** — 呼び出し元が token を送れば mk1 を全 profile に mint する
  必要は無い。
- ただし `authorize!` が token を検証するのは「token が送られた時」。Hermes の
  custom murakumo provider は keyless（`api_key_env` 未設定）なので、現状の
  fleet bot は token を送らず anonymous → basho にすると 401/402。
- つまり「murakumo-main → basho に切替」は **(1) worker routing toggle** +
  **(2) 各呼び出し元の custom provider に key（shared/mk1）を `api_key_env` で足して
  token を送る** の 2 本が揃って初めて効く。片方だけ deploy すると owned pool のまま
  （toggle なし）か 全 bot が 401/402（toggle のみ）になる。

## mk1 token の仕組み

- wire: `mk1.<payloadSeg>.<sig>`。HMAC-SHA256、stateless（KV/DB 引かず検証）。
- claims: `{sub, scope, iat, exp}`。`scope="all"` は何でも通す、他はルートの
  required と完全一致。`/v1/messages`・`/v1/embeddings` は `chat` を要求。
- 発行: `nbb scripts/run-task.cljs token issue --scope chat --sub <name>`（
  kotoba-lang/murakumo）。`MURAKUMO_TOKEN_SECRET` が **シェルに無いと
  「is not set — export the same value the gateway verifies with」で失敗**。
  この secret は worker の secret（wrangler secret）で、agent 環境には無い —
  mint は operator secret の取り扱い（credential ツール経由の狙い撃ち）が要る。
- 失効は無い（stateless）→ exp が唯一の失効。90 日上限。

## 「この bot を basho に接続する」だけなら routing 変更は要らない（option C）

fleet 全体の murakumo-main→basho 切替（上記 2 本）を待たず、1 体の bot を
`awai-network/basho` で直接動かせる。basho は model-id で serve される self-model
なので、呼び出し元が `model=awai-network/basho` + token を送れば worker が
basho 経路へ行く。routing toggle は不要。実測で 200（GLM-5.3-Flash）:

1. **`.env` に `MURAKUMO_API_TOKEN` を足す**（fleet 共有の mk1 token。値は読まず
   `grep -E '^MURAKUMO_API_TOKEN=' <template>/.env >> <bot>/.env` でテンプレートから
   コピー。複数 profile が同じ token を共有している — 既存 bot（aiueos-handoff 等）の
   .env にある）。
2. **config.yaml に `basho` provider を足す**: `base_url: https://api.murakumo.cloud/v1`
   + `api_mode: chat_completions` + `key_env: MURAKUMO_API_TOKEN` +
   `default_model: awai-network/basho`。
3. **cron job の model を `awai-network/basho`、provider を `basho` に**:
   `hermes cron edit <job> --model awai-network/basho --provider basho`。
4. 手動 fire して agent.log に `model=awai-network/basho provider=custom ... latency=`
   が並ぶことを確認（= basho 実働の証拠）。detached west worktree でも動く。

この path は面倒なく、1 体の bot を basho で実働させられる — 「murakumo-main を
全 fleet 切替する前に 1 bot だけ basho で試す」時の正解。

## 検証の罠

- basho の env 設定（`wrangler.toml` `MURAKUMO_BASHO_*`）は「serve される」と
  「呼び出し元から届く」を混同しない。env が ready=true でも、認証無し curl は
  401（`invalid x-api-key / bearer token`）、basho budget route は 402
  （`self_model_requires_identity`）。
- agent が worker routing を実装して検証するときは、変更ファイルを共有 checkout
  （sibling 全部 + node_modules を持つ場所）へバックアップ付き一時上書きして
  `nbb scripts/run-cljs-tests.cljs` を回し、終わったら必ず復元（`git status -s`
  empty）。isolated worktree では `nbb.edn` の `../../kotoba-lang/*` sibling
  classpath が解決できず `Could not find namespace: shadow.resource` になる。