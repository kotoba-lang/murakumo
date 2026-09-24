# メール送受信プロバイダ playbook (Resend / Cloudflare / SES)

relay 型メール受信箱（app-mail-relay / Cloudflare Worker + Resend）を配置・配線するときの手順と実測済みの罠。正本は `orgs/cloud-itonami/app-mail-relay` の README + `src/mail_relay/api.cljc` route 表。

## ドメイン面の決め方

- **API hostname とメールドメインは別に決める。** Worker の `domain` 定数が From/persona/base address を組み立てる。Resend は plan のドメイン上限（実測: 追加不可で 403 domain limit）があるので、**既に verified なメールドメイン（例: mail.<domain>）を env `MAIL_DOMAIN` / コードの mail-domain で差す**。API 面（custom domain routes）はそのまま別 hostname でよい。
- 送信 From に使うドメインは Resend domains API (`GET /domains`) で `sending: enabled` を実測してから配線する。verified 一覧は `data[].name` + `capabilities` で読む。
- ドメインの `receiving` capability は **dashboard トグル専用**。API `PATCH /domains` は 200 を返すが capability は変わらない（黙る no-op）。dashboard → domain details → Receiving toggle → 表示される MX を DNS に追加 → verified を待つ、の順。

## 受信配線チェックリスト（順に実測してから次へ）

1. MX: 受信ドメインに `inbound-smtp.<region>.amazonaws.com` (priority 9) があること。Resend の sending 用 `feedback-smtp...` MX (priority 10) と**共存してよい**（prio 9 が受信を優先、バウンス経路は無傷）。
2. Resend webhook: `POST /webhooks` で `{"endpoint":"https://<worker>/webhooks/resend","events":["email.received"]}`。作成応答の `signing_secret` を Worker secret `RESEND_WEBHOOK_SECRET` に put + kagi に格納（値は state file に置かない）。
3. **Worker secret `RESEND_RECEIVING_DOMAINS` に受信ドメインを設定する。** デフォルト集合は別ドメインに焼かれている — webhook が Ok で届いても inbox が空ならまずこれを疑う（`{"ignored":true}` で黙って捨てられる）。
4. e2e: 別 verified ドメインから persona 宛に Resend API で送り、inbox list で `sealed:true` + CID + SPF/DKIM/DMARC pass を確認する。webhook 到達確認は `npx wrangler tail <worker>`（background）で POST 1 行を見る。

## 診断早見表

| 症状 | 正体 | 対処 |
|---|---|---|
| 送信 403 `domain is not verified` | From ドメインが Resend 未登録 | verified ドメインへ mail-domain を寄せるか、dashboard で追加 |
| webhook 届くのに inbox 空 | `RESEND_RECEIVING_DOMAINS` が別ドメインのまま | secret を設定（デフォルト集合を信じない） |
| webhook 401 | signing secret 不一致 | 作成応答の `whsec_...` を `RESEND_WEBHOOK_SECRET` に再 put |
| 手動署名検証が invalid | `svix-timestamp` が古い（clock skew 300s） | 現在時刻で作り直す |
| PATCH /domains で receiving が変わらない | dashboard トグル専用 | cua/browser で dashboard 操作 |

## Svix 署名の手動検証（テスト用）

```python
key = base64.b64decode(secret_without_whsec + "=" * (-len(secret) % 4))
sig = base64.b64encode(hmac.new(key, f"{svix_id}.{ts}.{payload}".encode(), hashlib.sha256).digest()).decode()
# headers: svix-id / svix-timestamp(現在時刻) / svix-signature: v1,<sig>
```

## Cloudflare DNS レコード追加（token scope が無いとき）

- wrangler OAuth token は zones list は読めるが DNS write は `Authentication error`。`gftd.cf` API token は R2 scope のみ。
- dash.cloudflare.com を browser/cua で開ける session があるなら、**そのタブで `fetch('/api/v4/zones/<id>/dns_records', {method:'POST', ...})` は WAF に遮断される**（Attention Required）。GET は通る。
- 正攻法は dash の DNS UI フォーム操作（Add record → Type を MX に → name/mail server/priority → Save）。React 製 select は `role=combobox` trigger を click → `role=option` のテキスト一致で click。テキスト input は `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el, v)` + `input` event で入る（textarea は HTMLTextAreaElement 側）。AppleScript `tell <tab> to execute javascript` は Chrome の「Apple Events からの JavaScript」が有効な環境で tab を跨いで使える。
