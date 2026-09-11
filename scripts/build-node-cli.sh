#!/bin/sh
# Temporary extension adapters for the existing nbb runtime; canonical source stays .cljk.
set -eu
cd "$(dirname "$0")/.."
mkdir -p .node-build/src/murakumo/infer release
trap 'rm -rf .node-build' EXIT HUP INT TERM
for name in poll_worker image_job backoff; do
  cp "src/murakumo/infer/$name.cljk" ".node-build/src/murakumo/infer/$name.cljs"
done
cp nbb.edn .node-build/nbb.edn
(cd .node-build && nbb -e nil)
mkdir -p .node-build/src/cacao/edge
for name in mint verify cbor base58; do
  cp .node-build/.nbb/.cache/*/nbb-deps/cacao/edge/$name.cljk .node-build/src/cacao/edge/$name.cljs
done
(cd .node-build && nbb --classpath src bundle ../scripts/node-cli.cljk -o ../release/node.mjs)
node -e 'const fs=require("fs"),c=require("crypto");fs.writeFileSync("release/node.sha256",c.createHash("sha256").update(fs.readFileSync("release/node.mjs")).digest("hex")+"\n")'
node - <<'JS'
const fs=require('fs'),crypto=require('crypto');const hashes={};
for(const f of ['node.mjs','package.json','package-lock.json'])hashes[f]=crypto.createHash('sha256').update(fs.readFileSync('release/'+f)).digest('hex');
fs.writeFileSync('release/install.sh',fs.readFileSync('scripts/install-node.sh.in','utf8').replace('__HASHES__',JSON.stringify(hashes)).replace('__VERSION__',hashes['node.mjs'].slice(0,16)));
JS
