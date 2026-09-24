# CLJS Worker の governed release artifact 再生成

cloud-murakumo-api 等、`release/cljs/manifest.json` で CLJS 成果物（worker/ui）を
検証する worker で、`.cljs`/`.cljc` を変えた後に deploy する前の手順。
「CLJS input changed: refresh the governed release artifact」で package-release が
fail closed したらこれをやる。

## なぜ必要か

`npm run build` は `scripts/package-release.mjs` が `manifest.json` の
`sourceDigest`（deps.edn + shadow-cljs.edn + src 全 `.clj[cs]` のハッシュ）と
現実の入力が一致するか検証し、`release/cljs/*.gz` を gunzip して dist/ を再現する。
ソースを変えたのに artifact を再生成しないと digest 不一致で fail。

## 手順（順序どおり）

1. **JVM shadow-cljs で release を回す**（1 回だけ compiler を注入）:
   ```
   clojure -Sdeps '{:deps {thheller/shadow-cljs {:mvn/version "2.28.20"}}}' \
     -M -m shadow.cljs.devtools.cli release worker ui
   ```
   これは数分かかる。`npm ci` 済み（react/reagent 等 npm deps が node_modules に
   ある）ことを先に確認 — 無いと `[:ui]` で `The required JS dependency "react" is not
   available` が出て部分失敗する。`npm ci` で入る（package-lock に react はある）。
2. **新 compile output を確認**: `dist/worker.js` + `public/js/ui.js` +
   `public/js/manifest.edn` が産出されたか。
3. **gz を作り直す**: README の通り `gzip -9 -n` + **gzip OS フィールド
   （byte 9）を 0xff に** + manifest の digest 全部を再計算して書き換える。
   Python で `gzip.compress(data, compresslevel=9, mtime=0)` して byte 9=0xFF を入れ、
   `outputSha256` / `archiveSha256` / `sourceDigest` / `generatedFrom` を更新。
   `node scripts/package-release.mjs --print-input-digest` で新 sourceDigest を取れる。
   ここで `gzip -9 -n` ではなく生 `gzip`（level 6）や mtime 非ゼロだと
   archiveSha256 が元と変わってしまい、UI 等の byte-identical な一部でも
   digest 不一致で落ちる。
4. **`node scripts/package-release.mjs` が `{"ok":true,...}` を出すまで** iterate。
   ok:true = dev で再現する artifact ができた。
5. commit → push → PR → merge。`ui.js` がソース変更の影響を受けないなら
   バイト完全一致で digest も変わらない — それで正しい（全 artifact が変わるとは
   限らない）。

## 罠

- **isolated worktree（superproject 外）では classpath が壊れる**。shadow-cljs の
  deps.edn sibling（`../../kotoba-lang/*`）と `node_modules` を必要とするので、
  共有 checkout（sibling 全部を持つ場所）で実行する。
- `nbb scripts/run-cljs-tests.cljs`（CLJS test 面）でテスト green を先に確認 —
  ソースの妥当性は artifact refresh より先。
- governed な worker の deploy に `wrangler deploy` は **wrangler login の OAuth が
  必要**で、`gftd.cf` の cfat API token は read-only（worker service に
  `code 10000 auth error`）。OAuth が期限切れなら `wrangler login` を interactive で
  やり直す必要がある。
