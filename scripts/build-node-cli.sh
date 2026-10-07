#!/bin/sh
# Temporary extension adapters for the existing nbb runtime; canonical source stays .cljk.
set -eu
cd "$(dirname "$0")/.."
mkdir -p .node-build/src/murakumo/infer release
trap 'rm -rf .node-build' EXIT HUP INT TERM
for name in poll_worker image_job backoff keepalive replicated_topology jobs jobs_cli resident fleet_gateway observation; do
  cp "src/murakumo/infer/$name.cljk" ".node-build/src/murakumo/infer/$name.cljs"
done
mkdir -p .node-build/src/murakumo
cp src/murakumo/bench.cljk .node-build/src/murakumo/bench.cljs
cp src/murakumo/fleet_ps.cljk .node-build/src/murakumo/fleet_ps.cljs
cp src/murakumo/fleet_health.cljk .node-build/src/murakumo/fleet_health.cljs
mkdir -p .node-build/src/murakumo/fleet
cp src/murakumo/fleet/spec.cljk .node-build/src/murakumo/fleet/spec.cljs
cp src/murakumo/fleet/spec_cli.cljk .node-build/src/murakumo/fleet/spec_cli.cljs
cp src/murakumo/health.cljk .node-build/src/murakumo/health.cljs
cp src/murakumo/device_claim.cljk .node-build/src/murakumo/device_claim.cljs
for name in device_report node_console node_console_qr onboard onboard_audio onboard_device onboard_state onboard_window nm_keyfile wifi_share wifi_share_page; do
  cp "src/murakumo/$name.cljk" ".node-build/src/murakumo/$name.cljs"
done
cp nbb.edn .node-build/nbb.edn
(cd .node-build && kbb --backend sci -e nil)
mkdir -p .node-build/src/cacao/edge
for name in mint verify cbor base58; do
  cp .node-build/.nbb/.cache/*/nbb-deps/cacao/edge/$name.cljk .node-build/src/cacao/edge/$name.cljs
done
mkdir -p .node-build/src/grant
cp .node-build/.nbb/.cache/*/nbb-deps/grant/device_attest.cljk .node-build/src/grant/device_attest.cljs
cp .node-build/.nbb/.cache/*/nbb-deps/grant/acoustic_onboard.cljk .node-build/src/grant/acoustic_onboard.cljs
(cd .node-build && kbb --backend sci --classpath src bundle ../scripts/node-cli.cljk -o ../release/node.mjs)
cp nixos/node.nix release/nixos-node.nix
# The bundler may return zero after an EDN read error and leave a broken bundle.
node release/node.mjs node --help >/dev/null
node -e 'const fs=require("fs"),c=require("crypto");fs.writeFileSync("release/node.sha256",c.createHash("sha256").update(fs.readFileSync("release/node.mjs")).digest("hex")+"\n")'
node - <<'JS'
const fs=require('fs'),crypto=require('crypto');const hashes={};
for(const f of ['node.mjs','package.json','package-lock.json','nixos-node.nix'])hashes[f]=crypto.createHash('sha256').update(fs.readFileSync('release/'+f)).digest('hex');
const version=crypto.createHash('sha256').update(JSON.stringify(hashes)).digest('hex').slice(0,16);
fs.writeFileSync('release/install.sh',fs.readFileSync('scripts/install-node.sh.in','utf8').replace('__HASHES__',JSON.stringify(hashes)).replace('__VERSION__',version));
JS
