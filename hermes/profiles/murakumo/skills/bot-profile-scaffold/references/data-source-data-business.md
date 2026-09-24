# data-source bot → データ事業登録の下流（BMC・ライセンス・ロスター着地）

per-corpus data-source bot（`data-source-market-intel` / `data-source-gleif` 型）を立ち上げた後、
itonami cloud に登録し、データを x402 で売る準備をする一連の the on-ramp。bot 本体の構成は
SKILL.md の「per-corpus data-source bot」節。ここは上流の登録・法務・名簿化。

## 再販ライセンス判定（売る前に必ず triage — datum の正本）

「公開 repo に置いてある」「収集できた」は再販可否の根拠にならない（ADR-2608039700）。
再販可は**出所ライセンスが再配布を明示的に許す**か**自社所有**の場合のみ。3 値で書く:
`:sellable` / `:not-sellable` / `:unverified`（未測定を clean と同形にしない）。

- **sellable**: SEC EDGAR company facts（US 政府 public domain 17 U.S.C. §105）//
  GLEIF LEI 基準データ + relationship（GLEIF open、出所帰属つき）// 自社 corpus
  （hyakka / hayari、Wikimedia CC0 由来 + 自社分類）// 自社導出の metadata。
- **not-sellable**: IOS feed（feed 毎条項未確定）・passive DNS・threat/PII・
  アカウント識別子 — 安全側で not-sellable。
- **対象外**: private-offer 商用データ（D&B / FactSet / Equifax / Weather Source 等）—
  workspace に実体もライセンスも無く、そもそも corpus として並ばない。

正本は `90-docs/compliance/{scope,data-licensing}` — scope.datoms.edn は infra worker の
asset 台帳（生成物）。`data-licensing.datoms.edn` はデータ再販の手書き新規正本（corpus ごと
source/authority/licence/redistribution/attribution/resale/status）。生成物でなく手書き。可
判定はパース + 型 assert（vector/map、read-string の分裂罠）。

## supplier（必要な IT がやればよい）

BMC's new product（`:cloud-itonami-data-products` 等）を「既存 product の feature でなく上市」
するとき。

1. **cli registry**: `70-tools/bmc/src/gftd/cli.cljc` の `:products` ベースタブレット（例
   `:itonami {:products [:cloud-itonami :cloud-itonami-data-products] ...}`）に追加。
2. **base datom**: `90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn` に新 product の
   canvas 9 ブロック + hypothesis を追記（`]` の前に、nexus-x402 と同じ書式）。**base は
tooling からは毎回 event として ledger に append するが、新 product の初回登録は base に直接

datoms を置く（ai-gftd-apex が同例）」**。追加後 EDN をパースして entity 数で確認。
3. **new product の vector**:  `gftd canvas add` は全省 governor が `unknown canvas id` で
   拒否する — **base に product を入れないとリジェクト**。base に入れてからなら二回目の
   `gftd canvas md --product :X` / `gftd canvas datoms --product :X` で生成物
   （business-model.edn / canvas.datoms.edn）が作れる。
4. **kotobase dual-write の破断**: `gftd canvas add` 等はツールを順に town CACAO です
   `kotobase.client` を要求する — sparse worktree で `Could not find namespace: kotobase.client`
   で落ちる。**`--no-kotobase` を付けて dual-write を外す**（README にも明記）。
5. products 一覧が r「生成物に出ない」のは **products index が base datoms の`:canvas/product`
   から派生するため**（`canvas.cljc` の `index`）。registry の `:products` を変えただけでは
   一覧に出ない。`gftd canvas md --product :id` の明示指定で動くことだけで確認し、products 一覧の
   不在に慌てない。

## itonami cloud profile（`.itonami/profile.edn`）

- bot profile 名 repo は role 面（`loop-` = continuous orchestrator）。stub の実: repo root
  `.itonami/profile.edn` schema `cloud.itonami.app.repo-profile.v1`。**grant キー（:bot/tools
  :accounts :workspace :omakase?）を 1 つでも書くと `:repo-profile/grant-refused`** — 記述のみ。
  検証: `repo_profile.cljc` の validator を `nbb --classpath` で `:accepted? true` を実測。
- **new repo のカトロ**: `gh repo create cloud-itonami/loop-<x> --public` → 子 repo に push 済み
  → superproject の `manifest/repos.edn` `extra-projects` + `gen-west-manifest.cljs --entry`
  + `verify-west-pins.cljs`。**これらは worktree で行う（superproject は統合専用・他セッション
  WIP がある）**。

## ロスター着地（post-merge の別 PR）

`repo-bots.edn` / `repo-profiles.edn` は生成物（scripts/repo-bots/gen-registry.cljs /
scripts/repo-profiles/gen-roster.cljs）。**生成器は origin/main の west.yml を読む**ため、全体
の PR がマージされるまで新 repo をロスターに入れられない（loop-x402-qr-growth と同じ sync）。
- **sparse worktree では大破**: 生成器は checkout 済み repo を走査するので、sparse cone では
  大部分が `no-checkout` になり repo-bots.edn が 400+ 行並べ替わる、repo-profiles `--check` が
  `STALE` を返す。**生成は全 checkout を持つ共有 checkout で回し、その生成物を実 diff として
  take、worktree で commit**。生成物は generator の current 出力が正（巨大 diff でも手編集禁止）。
- **git の sparse index 罠**: `git merge --ff-only origin/main` をしても共有 checkout の
  working tree `manifest/west.yml` が HEAD とズレて `west update` が `unknown project` を返す。
  `git checkout HEAD -- manifest/west.yml` で working tree を HEAD に戻してから west を走らせる。

## 検証の原則（下記）

bot 構築の外部副作用（repo 作成・PR 作成・マージ・validator 合格）は sub/self-report を信じる
な — **read-back で確める**（`gh api repos/.../releases` 404→存在、`gh pr view --json
mergeable,files`、`git show origin/main:<file>` の内容・件数）。今回 debug したビルドは secret
 が署名 commit などの全の次のな問題ではなく、read-back 1 手順で解決した。
