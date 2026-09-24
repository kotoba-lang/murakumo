# cloud-itonami 職種/産業 bot（isic / isco）を建てるときの地図

「実際にその isic/isco の業務を担当する bot」を職種（ISCO major）/産業（ISIC
section）ごとに 1 体建てるときの、対象リポジトリ群と実装の実状。

## 対象リポジトリ群（superproject west checkout）

- **分類ミラー repo**: `cloud-itonami/isco`（ILO ISCO-08 全体、619 職種、
  正本 `data/isco-occupations.edn`）と `cloud-itonami/isic`（UN ISIC Rev.4、
  **R0 scaffold — `data/` は空**で taxonomy 未 ingest）。isco/isic は
taxonomy authority mirror であり、消費側に移す・業務を自走させない
（CLAUDE.md の authority boundary 節）。
- **per-code 設計図アクター**: `cloud-itonami-isic-*`（457 repo）と
  `cloud-itonami-isco-*`（340 repo）。「実際に業務を担当する bot」の実体は
  これで、langgraph-clj StateGraph（`advisor ⊣ governor`、operation.cljc の
  intake→advise→govern→decide→action→audit）で propose-only effect を強制する。

## カテゴリ→メンバー mapping の導出（推測で組まない）

- **ISCO major（0-9）** は `orgs/cloud-itonami/isco/data/isco-occupations.edn` の
  `:isco.occupation/level :major` エントリを読んで列挙（0 Armed Forces 〜
  9 Elementary / 1 Managers / 2 Professionals / 3 Tech-Assoc Prof /
  4 Clerical Support / 5 Services-Sales / 6 Skilled Agri / 7 Craft /
  8 Plant-Machine Operators）。membership は repo 名の code が `str(major)` で
  始まるかで決まる。
- **ISIC section（A-U）** は isic repo が未 ingest なので標準の先頭 2 桁範囲で
  決める（A=01-03, B=05-09, C=10-33, D=35, E=36-39, F=41-43, G=45-47,
  H=49-53, I=55-56, J=58-63, K=64-66, L=68, M=69-75, N=77-82, O=84,
  P=85, Q=86-88, R=90-93, S=94-96, T=97-98, U=99）。
- repos.edn の repo 参照は引用符を**付けたり付けなかったり**するので、
  抽出 regex は引用符省略可能形（`cloud-itonami-(is[ic]o|isic)-\d+`）で書く。

## 実装の実状 — per-code アクターは JVM/git-sha 重、nbb 軽量実行は無理

- deps.edn は大量の `:git/sha` .clj 依存（langgraph / labor / governor /
  taxlaw / langchain-store / jp-go-dds 等）+ cognitect test-runner で、
  **テストは JVM 前提**（得に isco-4313 は 633 tests / 35k assertions）。
- ルートに `run*.cljs` は**無い**（repo により `tools/mutate.cljs` はあるが
  nbb で回すと JVM 解決待ちで **timeout 124** になる）。根レベルの nbb
test entry を仮定して evidence が期待しない・前提しない。
- したがって職種 bot の evidence は「テストを毎 tick 実走させる」のではなく
  **README の成熟度分類 + 移植可能性（nbb/jvm-free entry の有無）読み取り**に
  留め、frontier アクターのテスト実走は「必修 JVM suite の native/移植可能 gate
  化」提案までにする。nbb 単独で確実に緑にできる entry がある時だけ実走し、
  緑にできない事情を考える（対象 repo の README が `:implemented` でも
  test entry 無しは緑と report しない）。

## bot の型

- 1 tick = 1 finding、propose-only（SOUL.md の絶対規則どおり: publish 権限・
governor 迂回 token を bot に持たせない）。
- memory に記録済み: kotoba native CLI（`kotoba -M`）を JVM route より優先、
  kbb-first（新規 ops tooling を nbb で書かない） — 職種 bot の実装も同順。
- frontier 選択: `:implemented` かつ test entry 有りを優先、次に scaffold。
  27 分類を 1 体ずつ立てる展開時は、同一 blueprint repo を複数 bot が
  git worktree で共有→ git lock 衝突するので、bot 間で cron 時刻をずらす。
