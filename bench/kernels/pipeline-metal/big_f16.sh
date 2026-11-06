#!/bin/sh
# Real-size SDXL single-file float16 checkpoint (random weights, ~6.9 GB) through the whole render program: memory, time, finiteness. No oracle.
#   sh big_f16.sh /path/to/amu-checkout /path/to/ComfyUI-0.44.0 /path/to/stubs /path/to/python-with-torch-and-transformers /path/to/sd1_tokenizer [steps=2]
set -eu
AMU=${1:?amu}; COMFY=${2:?ComfyUI}; STUBS=${3:?stubs}; PYC=${4:?python}; TOKDIR=${5:?tokenizer dir}; STEPS=${6:-2}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/big-f16.XXXXXX"); MC=320; LAT=32; CH=128
PYTHONPATH="$STUBS" "$PYC" "$HERE/make_big_f16.py" "$WK" "$COMFY" "$LAT" > "$WK/fx.log" 2>&1; cat "$WK/fx.log"
ls -l "$WK/model.safetensors" | awk '{printf "file %.2f GB\n", $5/1e9}'
shasum -a 256 "$WK/model.safetensors" | cut -c1-64 > "$WK/model.sha256"  # the pinned digest the program checks before reading the file
SB=$(python3 -c "import struct;print(struct.unpack('<I',struct.pack('<f',($CH*4)**-0.5))[0])")
CFGB=$(python3 -c "import struct;print(struct.unpack('<I',struct.pack('<f',4.0))[0])")
sed -e "s#@MODEL@#$WK/model.safetensors#;s#@EXPECT@#$WK/model.sha256#;s#@PRE_L@#conditioner.embedders.0.transformer.#;s#@PRE_G@#conditioner.embedders.1.model.#;s#@SCHEME_L@#0#;s#@SCHEME_G@#1#;s#@X@#$WK/x.f32#;s#@VOCAB@#$TOKDIR/vocab.json#;s#@MERGES@#$TOKDIR/merges.txt#;s#@PROMPTC@#$WK/prompt_c.txt#;s#@PROMPTU@#$WK/prompt_u.txt#;s#@SZ@#$WK/sz.f32#;s#@MC@#$MC#g;s#@LAT@#$LAT#g;s#@CH@#$CH#g;s#@STEPS@#$STEPS#g;s#@TE@#$((4 * MC))#g;s#@NDC@#$((CH * 4 / 64))#g;s#@SCALEBITS@#$SB#;s#@CFGBITS@#$CFGB#" "$HERE/pipeline_metal.kotoba.in" > "$WK/big.kotoba"
"$AMU/bin/amu" compile "$WK/big.kotoba" --runtime-profile amu-program-metal -o "$WK/big" > /dev/null
/usr/bin/time -l "$WK/big" > /dev/null 2> "$WK/big.err" || true
"$PYC" - "$WK/big.err" "$LAT" "$STEPS" <<'PYEOF'
import sys, re, numpy as np
raw = open(sys.argv[1], "rb").read(); lat = int(sys.argv[2]); steps = int(sys.argv[3])
off = 4 * 308 + 2 * (77 * 2048 * 4 + 2816 * 4)
n = 4 * lat * lat * 4; ni = 3 * (8 * lat) ** 2 * 4
ok = True
for i in range(steps):
    a = np.frombuffer(raw[off:off + n], dtype=np.float32); off += n
    print("step %d: finite=%s max|x|=%.2f" % (i, bool(np.isfinite(a).all()), float(np.abs(a).max()))); ok = ok and bool(np.isfinite(a).all())
img = np.frombuffer(raw[off:off + ni], dtype=np.float32); off += ni
print("image %dx%d: finite=%s max|x|=%.2f mean|x|=%.3f" % (lat * 8, lat * 8, bool(np.isfinite(img).all()), float(np.abs(img).max()), float(np.abs(img).mean())))
t = raw[off:].decode("latin1")
m = re.search(r"dispatches (\d+)", t); print("recorded dispatches", m.group(1) if m else "?")
for key in ("maximum resident set size", "peak memory footprint", "real"):
    mm = re.search(r"(\d+)\s+" + re.escape(key), t)
    if mm: print("%s: %.2f GB" % (key, int(mm.group(1)) / 1e9))
print("finite" if ok and np.isfinite(img).all() else "NOT FINITE")
PYEOF
KEEP=${KEEP:-0}; [ "$KEEP" = 1 ] || rm -f "$WK/model.safetensors"
echo "build dir $WK"
