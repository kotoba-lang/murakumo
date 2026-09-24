# 同型 crawl bot の追加 — 実在構成（hyakka / wiki.yataverse.com 系）

hyakka / wiki.yataverse.com 系の「source ingest → proposal gate → PR」型 crawl bot を
1 体増やすときの手順。正本 repo は `network-awai/app-hyakka`（wiki.yataverse.com、
sourced claim graph）。

## 既存 fleet（同じ形で動いているもの）

- hyakka / hyakka-corpus — source・ontology・Wikidata class/sitelink/Commons scouts
- wiki-kaonavi-crawl（2026-09-06 新設）— Kaonavi Q27017035 単社の first-party source crawl。
  専用 profile + cron `25 6 * * *`、専用 worktree ~/.gftd/worktrees/wiki-kaonavi-crawl（app-hyakka）。
  evidence script は 8 個の kaonavi source の設定状態と live-plane 着地を測る。live-plane absent の間は
  新提案を抑える（read-lag を proposal で埋めない）。
- wiki-listed-crawl（2026-09-06 新設）— **全世界上場企業を 1 bot で反復処理**する scalable crawl
  （queue + ledger 方式、企業ごとに profile を立てない）。frontier は
  ~/.hermes/profiles/wiki-listed-crawl/workspace/listed-companies.edn、processed ledger は同
  workspace/listed-processed.jsonl（PR を開いた後にのみ追記）。每 run 最大 2 社 × 2 source。
  実証: 2026-09-06 PR #635 で Tokio Marine + Unilever を着地（LEI は GLEIF から実地解決）。
- wiki-company-crawl（企業・製品、日本以外）、wiki-trade-logistics-crawl、
  wiki-product-price-crawl、wiki-pr-merge、wiki-projection-ops
- itonami-bible-scout（2026-09-12 新設）— 聖書を 3 面で接続する bot（章全文
  web-document / shoseki 書卷 QID / Wikidata ontology）。evidence script は
  大元帳 Q1845 → OT/NT → 章を P527 で辿り、book-classes pass 判定 + wikisource
  章 URL 実測で 1 章/tick を提案。frontier ledger は profile `workspace/` の
  JSONL。evidence は worktree を `git show origin/main:<path>` で read-only に読む
  （fetch/merge しない — 共有 worktree 衝突を避ける）。「大元帳から frontier 導出」
  手順は本ファイル末尾の節参照。
- legal-disclosure-crawl、whois-org-crawl、server-operator-crawl
- otent（地理空間 ingest + vision 分析 + hyakka publish）、yabai-intel / yabai-classify、
  adnetwork-scout（R2 Iceberg）

## 共通パイプライン（全部同じ gate を使う）

1. evidence script（profile の `scripts/*_evidence.py`）が worktree を origin/main に
   sync し、`scripts/wiki_growth_evidence.cljs`（repo 側、判断を含まない collector）を
   実行。失敗時は REFUSED banner で「盲目的な提案」を防ぐ。exit code が load-bearing
   （2 = could not look、0+空 = clean、を畳まない）。
2. agent は測定を読み、既存 `config/knowledge-ingest.edn` の `:sources` と重複しない
   候補を最大 2 件、各 URL を run 内で実 fetch（HTTP 200 + body 必須）して
   `/tmp/hyakka-source-proposal.edn` に書く。
3. gate `nbb --classpath src scripts/verify_source_proposal.cljs --root . --proposal ...`
   が最終決定権を持つ: URL 実測、`:kind` が collector (`resident_ingest.cljs` の
   `collect!`) が dispatch するものか、`:source-classes` が corpus policy に適合するか、
   public なら `:license` 必須、`:interval-seconds` 正値。property 追加は receipts の
   拒否記録による裏付けが無いと拒否される。
4. exit 0 のときだけ branch → commit → push → app-hyakka へ PR 1 本。

## 新 bot を 1 体足す手順

1. template は最も近い既存 crawl profile（例: wiki-company-crawl）。config.yaml +
   .env（API key 行ごと）+ scripts/throttle.py を複製。
2. evidence script を複製し、WORKTREE 既定を新 bot 専用 worktree に書き換える。
   `git worktree add --detach ~/.gftd/worktrees/<name> origin/main`（app-hyakka を
   fetch してから。detach は west/共有 worktree の正常形）。
3. SOUL.md を書く（承認要。SKILL.md 本体の手順 2 参照）。職責・規律・候補は新
   domain に合わせるが、gate 呼び出しと「1 run = 1 PR / main 直 push 禁止 /
   アグリゲータ・third-party wiki 禁止 / cron runtime 拒否コマンド形を書かない」は
   そのまま使う。
4. cron 登録は `hermes cron create '<expr>' --name <name>-scout --deliver local \
   --script scripts/<name>_evidence.py --workdir ~/.gftd/worktrees/<name> "<prompt>"`。
   時刻は既存 bot とずらす（worktree lock 衝突防止）。
5. `hermes gateway stop && start` で multiplexer に再発見させ、手動 1 run で
   succeeded を実測してから運用に入る。

## gap の測り方（domain 選びの根拠）

- `nbb --classpath src scripts/wiki_growth_evidence.cljs --root . --days 7` が
  live plane の items/facts/sources と idle sources、拒否 receipt 分類を出す。
- source の domain 別欠けは config の `:id` 総当たりで測る: 特許（epo/uspto/wipo/
  j-platpat）0、OECD/UN comtrade/COFOG/census 0、e-stat は情報ページ 1 件のみ、
  一方で調達 (koukyou-chotatsu) は日々着地 — 「政府系なら何でも」ではなく
  corpus 単位で掘る。
- 未接続 corpus の在処: `src/hyakka/corpus/*.cljc` に corpus 定義（fudosan /
  keizai-census / koukyou-hojokin / jp-tetsuzuki 等）があり、`knowledge/seeds/*.edn`
  に seed があるが、config に `:corpus` が付いた source が 0 の corpus は
  collector 未接続（seed script が別経路）— 新 bot の宛先候補になる。

## 実在の connector kind（gate が受理する `:kind` の例）

`resident_ingest.cljs` の `collect!` が dispatch する（gate がここから parse する）。
web-document が最多（class は source 側宣言）、決定論的 connector には
companies-house-overview / gleif-lei-record / sec-companyconcept / rdap-domain /
dns / overpass-osm / nominatim-place / wikidata-book / nsf-awards-json /
cordis-projects-json 等。新 kind は connector 実装が先、source 提案は後。

## corpus 定義は `.kotoba` へ移行済み — 追加前に拡張子を正本にする

⚠ **先に worktree 側の現在地を読んでからこの節に従う** — `.kotoba` 移行の進行は
時点で変わる（2026-09-12 時点の実測では `src/hyakka/corpus/` は `.cljc` のまま
で、registry も `registry.cljc`）。**新規 PR の base 拡張子は origin/main の
実物から `ls` で確定させ、ここの記述を前提にしない。**

app-hyakka の corpus 定義（`src/hyakka/corpus/*`）は **`.cljc` → `.kotoba` に全面移行しており、
registry も `registry.cljc` でなく `registry.kotoba`** が正本（`world_legal.kotoba` /
`world_research.kotoba` 等）。`.kotoba` のソースは Clojure 構文のまま（`(def policy ...)` 等）
なので読めるが、**署名拡張子が違うだけで検証経路が変わる**:

- **`nbb --classpath src` は `.kotoba` を解決できない**（`hyakka.corpus.registry` が
  `Could not find namespace` — nbb は `.cljs/.cljc` しか require しない）。
- **`npm run catalog` も `.kotoba` namespace（例 `hyakka.facts`）で落ちる**。catalog 生成と
  corpus 追加の検証は shadow-cljs(kbb) build 経路が正本で、nbb 単独の green は
  corpus 追加では信用できない検証。

**corpus を 1 つ足すとき:** ①最初に `src/hyakka/corpus/` の拡張子を確認し、**現 main の形
（`.kotoba`）に合わせて作る**。past の `.cljc` で作ると origin/main（`.kotoba` 移行済み）への
PR が構造的に non-conformant になり、merge conflict ではなく「言語不一致」で着地しない。
②registry 登録は 3 箇所（ns require / corpora map の entry / properties union）で、
`.cljc` 時代と同位置。

### 生成物 conflict は「溶かして再生成」でなく「古い PR を閉じて fresh に再移植」

corpus 追加を渡すと `catalog.cljc` / `claims_fixture.cljc`（両方とも生成物）が
origin/main と conflict しやすい。**これらは再生成（`npm run catalog`）で marker を
溶かそうとすると、`.kotoba` 移行中は生成器自身が `hyakka.facts` で落ちて解けない。**
加えて origin/main が大規模に進んでいたら（config 数千行変更等）、merge を in-place で
戦うより **古い PR を close → `git worktree add --detach origin/main` で fresh worktree を
切り → 変更を source 部分（非生成物）だけ移植 → 生成物は fresh 上で再生成 → 新 branch/PR**
が正しい。generated-file conflict + 大規模 drift は「base が古い/形が変わった」の症状で、
finish-me-not-resolve-me と読む。旧 branch は worktree が to use している限り delete 不能 —
worktree を detach/remove してから `git branch -D` する。

## shoseki（書籍）corpus の QID 受理

`wikidata-book` connector が book と認める P31 は **1 種ではない**。`book-classes`
（`src/hyakka/wikidata_book.cljc`）は Q7725634（literary work）に加えて
Q571（book）・Q3331189（edition）・Q47461344（written work）・Q8261（novel）等
8 種を受理する。**edition の QID は本物の book として通る** — EPUB/paperback 版の
QID を「literary work でないから除外」と捨てるのは誤り。

- **book 追加の upcheck は book-classes の集合に照らせ。** 単一の P31 でなく
  `book-classes` の実物（コードを読む）を正本にし、P31 が 8 種のいずれかに
  含まれるかで判定する。
- **QID を当て推量で seed に足すな。** recalled QID は誤る（実測: 想起した複数の
  anatomy book QID が P31 で全部落ちた）。候補は必ず live `Special:EntityData`
  JSON で P31 を upcheck してから frontier に入れる。誤 QID は 200 を返すので
  HTTP status は判定にならない。
- **frontier は「手で足した verified QID の列」と corpus 自体が coverage note に
  明記している。** 自動列挙（wbsearchentities / SPARQL）は robots で禁じられて
  おり、これに頼らず verified QID を 1 tick 1 件ずつ足すのが正規経路。

## 大元帳 entity から frontier を導出する（scout の frontier 作成手順）

書籍・章・部分のような「上位 entity の has-part として列挙される」domain では、
frontier を手で書かず**大元帳 entity の P527 を辿って導出する**。実測（聖書）:
聖書 Q1845 → P527 で旧約 Q19786（46 書卷）/ 新約 Q18813（27 書卷）→ 各書卷 entity
の P527 に章 entity（P31 = Q29154515）。導出した候補 QID は**1 個ずつ live
Special:EntityData で P31 upcheck してから frontier に入れる**（大元帳から辿った
QID でも recall のため、誤 QID は 200 を返す）。

- **gate の受理判定は corpus 側の class 集合で分岐する。** 同じ大元帳の子でも、
  shoseki gate（book-classes 8 種）を通る子と通らない子がある — 聖書 66 書卷の
  うち book-class pass 33 / refuse 40（Q179461 religious text のみの entity は
  shoseki に落ちる）。落ちた側は**別 corpus/別経路（web-document 全文 source など）
  に降格させ、shoseki の QID frontier に無理に足さない**。
- **robots.txt を source 採用判定に使え。** en.wikisource.org は `/wiki/` を
  許可している（KJV などパブリックドメイン全文は章 URL で取れる）— 「全文が
  欲しいが corpus gate が落とす」場合の正攻法は、ライセンス（Public domain）と
  robots 許可を実測した上で `:kind :web-document` の章 source として提案する。
- **章 URL は 1 つで全章を指すことがある。** en.wikisource `Bible_(King_James)/<書卷>`
  は 1 URL に全章が載る（章見出しは anchor）。URL 毎の fetch 実測（HTTP 200 +
  body bytes）を evidence が 1 回だけ行い、frontier ledger（JSONL: book/chapter/
  url/anchor/http/body_bytes）に記録して tick 間重複提案を防ぐ。
- **evidence script は外部 fetch 付きでも read-only を維持する。** 実測した URL と
  body size を自分の profile `workspace/` に append するだけにし、worktree への
  git 書込み（fetch/merge）をしない — 複数 profile の worktree 共有衝突を避ける。