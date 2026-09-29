#!/bin/sh
# Build the stock-nbb bundle for a web node (ADR-260929).
# Nodes run stock nbb, which does not load .cljk, so murakumo's web sources are
# copied as .cljc alongside their two dependency-free deps (pinned in deps.edn).
#   scripts/web-bundle.sh <out-dir>
set -eu
out=${1:?usage: web-bundle.sh <out-dir>}
here=$(cd "$(dirname "$0")/.." && pwd)
libs=${GITLIBS:-"$HOME/.gitlibs/libs/io.github.kotoba-lang"}
text_sha=73bdb13ae7a3d004b44bca08be03a3191157a38f
bytes_sha=d5259f35ab7c4f5a66e63ca29ed4dcf7ba3f1ac5
rm -rf "$out"; mkdir -p "$out/murakumo/web" "$out/kotoba/lang"
cp "$here/src/murakumo/canonical.cljk" "$out/murakumo/canonical.cljc"
cp "$here/src/murakumo/web.cljk" "$out/murakumo/web.cljc"
for f in worker host robots crawl search verify; do
  cp "$here/src/murakumo/web/$f.cljk" "$out/murakumo/web/$f.cljc"
done
cp "$libs/text/$text_sha/src/kotoba/lang/text.cljc" "$out/kotoba/lang/text.cljc"
cp "$libs/bytes/$bytes_sha/src/kotoba/bytes.cljk" "$out/kotoba/bytes.cljc"
cp "$here/scripts/web_node.cljc" "$out/web_node.cljs"
( cd "$out" && find . -type f ! -name MANIFEST.sha256 | sort | xargs shasum -a 256 > MANIFEST.sha256 )
echo "bundle: $out ($(find "$out" -type f | wc -l | tr -d ' ') files)"
