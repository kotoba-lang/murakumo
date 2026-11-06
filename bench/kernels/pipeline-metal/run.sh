#!/bin/sh
# Gate a whole (small-UNet, full-size-text-tower) SDXL render on this Mac's GPU (stage M, slices 18 to 21): pipeline_metal.kotoba.in, ONE Kotoba program
# built by `bin/amu compile --runtime-profile amu-program-metal`, reads one safetensors file in ComfyUI / Hugging Face tensor names holding an SDXL-topology
# UNet (slice 17: model_channels 64, 158 M parameters), a VAE decoder (slice 16: ch 32) and the two text towers at full size (slice 15: CLIP-L 123 M and
# bigG 695 M parameters, prefixed clip_l. and clip_g.), and from a noise latent and the token ids of two prompts, on the GPU: runs both towers for the
# prompt and for the empty prompt, builds the conditioning (L then G penultimate states, pooled vector, size/crop embeddings: slice 19), builds the sigma
# table and the `simple` schedule (slice 14), and for each Euler step scales the latent, finds the nearest timestep in log-sigma, runs the UNet twice
# (conditional, unconditional; the SAME recorded dispatch list re-run after copying the matching context and label), combines the denoised values with
# classifier-free guidance (scale 4) and takes the Euler step; then decodes the final latent.
# make_fixture.py runs the same render with Hugging Face's CLIPTextModelWithProjection towers and ComfyUI v0.44.0's own UNetModel, Decoder,
# ModelSamplingDiscrete, simple_scheduler, sample_euler and encode_adm (random weights, float32, CPU; a local oracle only). Compared: the conditioning
# (context and label vectors), the latent after each of 4 steps and the decoded 256 x 256 image.
# Limits (|gpu - ref| / max(1, |ref|)): the conditioning vectors the two f16 text towers produce (penultimate states concatenated L then G, pooled vector,
# Timestep embeddings of the six size/crop values) 5e-2, the figure slice 15 fixed for the 32-layer bigG tower before its run (the earlier version of this gate
# read tower outputs from files and held 1e-4); steps and image 5e-2.
# The 5e-2 on steps and image was chosen AFTER the first run (2.7e-2 to 2.8e-2 at every step, 2.0e-2 on the image) from this
# arithmetic: the standalone UNet gate (slice 17) is accurate to ~1e-3 on eps, a guided step multiplies an eps error by (cfg + (cfg - 1)) = 7 and by
# the step length (sigma_0 - sigma_1 = 10.5) before it is divided by max(1, |x|) <= 61, so 1e-2 is not attainable in f16; 5e-2 is about twice what was
# observed. Mutants must exceed 0.2: guidance dropped, the timestep reversed (999 - t; a linear instead of log-sigma nearest search is an equivalent mutant on this schedule and gave the same output, so it is not used), the unconditional pass given the conditional
# context and label, L and G concatenated in the wrong order, the six size values in the wrong order, the bigG padding rule shifted by one position (token ids differ), the VAE residual dropped. Not a CI step. A GPU host is required.
#
#   sh run.sh /path/to/amu-checkout /path/to/ComfyUI-0.44.0 /path/to/stubs /path/to/python-with-torch /path/to/sd1_tokenizer
set -eu
AMU=${1:?amu checkout}; COMFY=${2:?ComfyUI v0.44.0 source dir}; STUBS=${3:?stubs dir}; PYC=${4:?python with torch, transformers, safetensors, einops, numpy, tqdm}; TOKDIR=${5:?CLIP tokenizer dir (vocab.json, merges.txt), e.g. ComfyUI/comfy/sd1_tokenizer}
HERE=$(cd "$(dirname "$0")" && pwd)
WK=$(mktemp -d "${TMPDIR:-/tmp}/pipeline-metal.XXXXXX")
fail=0
MC=64; LAT=32; CH=32; STEPS=4
LAYOUT=${LAYOUT:-hf}
# the conditional prompt mixes ASCII, Japanese, accented Latin, fullwidth forms and an emoji, so the whole path (tokenizer, towers, render) is exercised on non-ASCII text
export PROMPT_C=${PROMPT_C:-"桜の花が咲く夜景、a photo of a cat ÀÉ Straße ＡＢＣ１２３ 😀"}
if [ "$LAYOUT" = single ]; then PRE_L=conditioner.embedders.0.transformer.; PRE_G=conditioner.embedders.1.model.; SCH_G=1; else PRE_L=clip_l.; PRE_G=clip_g.; SCH_G=0; fi
PYTHONPATH="$STUBS" "$PYC" "$HERE/make_fixture.py" write "$WK/fx" "$COMFY" "$MC" "$LAT" "$CH" "$STEPS" "$TOKDIR" "$LAYOUT" > "$WK/fx.log" 2>&1
# the pinned digest the program must find in the checkpoint before reading it (here taken from the fixture just written; for a real
# checkpoint it comes from the source). bad.sha256 differs in the last character: the negative control below.
shasum -a 256 "$WK/fx/model.safetensors" | cut -c1-64 > "$WK/fx/model.sha256"
lastc=$(cut -c64 "$WK/fx/model.sha256"); [ "$lastc" = 0 ] && flip=1 || flip=0
printf "%s%s" "$(cut -c1-63 "$WK/fx/model.sha256")" "$flip" > "$WK/fx/bad.sha256"
SB=$(python3 -c "import struct;print(struct.unpack('<I',struct.pack('<f',($CH*4)**-0.5))[0])")
CFGB=$(python3 -c "import struct;print(struct.unpack('<I',struct.pack('<f',4.0))[0])")
build() { # name [from to]
  sed -e "s#@MODEL@#$WK/fx/model.safetensors#;s#@EXPECT@#$WK/fx/model.sha256#;s#@PRE_L@#$PRE_L#;s#@PRE_G@#$PRE_G#;s#@SCHEME_L@#0#;s#@SCHEME_G@#$SCH_G#;s#@X@#$WK/fx/x.f32#;s#@VOCAB@#$TOKDIR/vocab.json#;s#@MERGES@#$TOKDIR/merges.txt#;s#@PROMPTC@#$WK/fx/prompt_c.txt#;s#@PROMPTU@#$WK/fx/prompt_u.txt#;s#@SZ@#$WK/fx/sz.f32#;s#@MC@#$MC#g;s#@LAT@#$LAT#g;s#@CH@#$CH#g;s#@STEPS@#$STEPS#g;s#@TE@#$((4 * MC))#g;s#@NDC@#$((CH * 4 / 64))#g;s#@SCALEBITS@#$SB#;s#@CFGBITS@#$CFGB#" "$HERE/pipeline_metal.kotoba.in" > "$WK/$1.kotoba"
  if [ -n "${2:-}" ]; then
    FROM=$2 TO=$3 perl -0pi -e 's/\Q$ENV{FROM}\E/$ENV{TO}/ or die "mutation did not apply\n"' "$WK/$1.kotoba"
  fi
  "$AMU/bin/amu" compile "$WK/$1.kotoba" --runtime-profile amu-program-metal -o "$WK/$1" >/dev/null
  s0=$(date +%s); { "$WK/$1" > /dev/null 2> "$WK/$1.err"; } 2>/dev/null || true; s1=$(date +%s); echo $((s1 - s0)) > "$WK/$1.sec"
}
cmp_() { PYTHONPATH="$STUBS" "$PYC" "$HERE/compare.py" "$WK/$1.err" "$WK/fx" "$LAT" "$STEPS" 2>/dev/null | sed -n 1p; }
build clean
line=$(cmp_ clean)
ok=$(echo "$line" | awk '{ok=1; for(i=1;i<=NF;i++){split($i,a,"="); if(a[1]=="finite"){if(a[2]!=1) ok=0} else if(a[1]=="ids_mismatch"){if(a[2]!=0) ok=0} else if(a[1] ~ /^(step|image)/){if(a[2]>0.05) ok=0} else if(a[1] ~ /^(ctx|y)_/){if(a[2]>0.05) ok=0}}; print ok}')
case "$line" in *finite=1*) ;; *) ok=0 ;; esac
[ "$ok" = 1 ] || fail=1
echo "$STEPS Euler steps (2 UNet passes each) + VAE decode, 256 px, $(grep -a -o 'dispatches [0-9]*' "$WK/clean.err" | awk '{print $2}') recorded dispatches: $line $([ "$ok" = 1 ] && echo ok || echo FAIL) process wall $(cat "$WK/clean.sec") s"
# digest-before-read negative control: the same program pointed at a digest that differs in one character must refuse the checkpoint,
# so no render output (no "dispatches" line, no image) may exist
build mbad '/model.sha256' '/bad.sha256'
if grep -a -q 'dispatches' "$WK/mbad.err"; then echo "digest mismatch: render still ran (NOT refused)"; fail=1; else echo "digest mismatch: checkpoint refused, no render output (detected)"; fi
mutant() { # label from to
  build "m$1" "$2" "$3"
  line=$(cmp_ "m$1"); worst=$(echo "$line" | awk '{m=0; for(i=1;i<=NF;i++){split($i,a,"="); if((a[1] ~ /^(step|image|ctx|y|ids)/) && a[2]+0>m) m=a[2]+0}; print m}')
  bad=$(awk -v e="$worst" 'BEGIN{print (e+0 > 0.2 || e != e+0) ? 1 : 0}')
  [ "$bad" = 1 ] && echo "mutant [$1]: worst error $worst (detected)" || { echo "mutant [$1]: worst error $worst NOT detected"; fail=1; }
}
mutant guidance 'float den = du + (dc - du) * cfg;' 'float den = dc;'
mutant timestep-reversed '  Tb[0] = float(bj);' '  Tb[0] = float(999u - bj);'
mutant uncond-context '(vector-i64 ctxhu ctxh) (vector-i64 0 0) 24' '(vector-i64 ctxhc ctxh) (vector-i64 0 0) 24'
mutant concat-order '((c < 768u) ? L[r * 768u + c] : G[r * 1280u + (c - 768u)])' '((c < 1280u) ? G[r * 1280u + c] : L[r * 768u + (c - 1280u)])'
mutant size-order 'float arg = Sz[v] *' 'float arg = Sz[5u - v] *'
mutant g-padding '(> k (+ keep 1))' '(> k (+ keep 2))'
mutant vae-residual '(vector-i64 cout cout ih iw 3 1 2))
      (let [sc (scl!' '(vector-i64 cout cout ih iw 3 1 1))
      (let [sc (scl!'
if [ "$LAYOUT" = single ]; then
  mutant inproj-v-offset 'wv (cvle! f R S vwn HID HID (if (= scheme 0) 0 (* 2 (* HID HID))))' 'wv (cvle! f R S vwn HID HID 0)'
  mutant text-projection-transposed '(cv32! f R S (nm pre "text_projection"))' '(cvl! f R S (nm pre "text_projection") HID HID)'
  mutant f16-params-scaled 'D[i] = float(S[i]);' 'D[i] = float(S[i]) * 1.5f;'
  mutant f32-in-f16-file '(if (= dsz 4) 18 35)' '35'
fi
# in-place write after the hash: a mutant opens the checkpoint for writing right after the digest check and rewrites its first byte with
# the same value (contents unchanged, only the modification time moves). The program must notice (size/mtime stamp taken before the hash,
# compared after the last read) and trap before any render output. Run before the swap test, which replaces the file.
build mwrite 'u3 (cc f (sym f "pread") fd lenb 8 0 0)' "u3 (cc f (sym f \"pread\") fd lenb 8 0 0) wwr (cc f (sym f \"pwrite\") (cc f (sym f \"open\") (cstr f (vector-at R 3) \"$WK/fx/model.safetensors\") 1 0 0 0) lenb 1 0 0)"
if grep -a -q 'dispatches' "$WK/mwrite.err"; then echo "in-place write after hash: render still ran (NOT detected)"; fail=1; else echo "in-place write after hash: modification detected, no render output (detected)"; fi
# the same in-place write, but with size and mtime ignored by the stamp (the mutant reads them as 0): only ctime can notice it, which also
# checks the ctime offset; a program that compared only size and mtime would pass this write, a restored mtime would not hide it.
cp "$WK/mwrite.kotoba" "$WK/mctime.kotoba"
perl -0pi -e 's/\(peek f \(\+ sb 96\)\) \(peek f \(\+ sb 48\)\) \(peek f \(\+ sb 56\)\)/0 0 0/ or die "ctime-only mutation did not apply\n"' "$WK/mctime.kotoba"
"$AMU/bin/amu" compile "$WK/mctime.kotoba" --runtime-profile amu-program-metal -o "$WK/mctime" >/dev/null
"$WK/mctime" > /dev/null 2> "$WK/mctime.err" || true
if grep -a -q 'dispatches' "$WK/mctime.err"; then echo "in-place write, ctime only: render still ran (NOT detected)"; fail=1; else echo "in-place write, ctime only: detected by change time, no render output (detected)"; fi
# swap after the hash: the program renames a 4 KiB file over the checkpoint path right after the digest check. Replacing the path unlinks
# the hashed file's old name, which changes its change time, so the stamp check refuses the run; the program also reads through the
# descriptor it hashed, so even without that check the data it read would be the hashed file's. Expected: path replaced (checked by
# size) and no render output. Run last because it replaces the fixture's checkpoint.
head -c 4096 /dev/zero | tr '\0' 'z' > "$WK/fx/alt.bin"
build mswap 'shv (sha-verify! f R fd "'"$WK/fx/model.sha256"'")' "shv (sha-verify! f R fd \"$WK/fx/model.sha256\") swp (cc f (sym f \"rename\") (cstr f (vector-at R 3) \"$WK/fx/alt.bin\") (cstr f (vector-at R 3) \"$WK/fx/model.safetensors\") 0 0 0)"
swapped=$(wc -c < "$WK/fx/model.safetensors" | tr -d ' ')
if [ "$swapped" = 4096 ] && ! grep -a -q 'dispatches' "$WK/mswap.err"; then echo "swap after hash: path replaced by a 4096-byte file, run refused, no render output (detected)"; else echo "swap after hash: path size $swapped, render output present or swap did not happen (NOT detected)"; fail=1; fi
# the fixtures are large (up to 2.8 GB): on success the weight files are removed from the build dir unless KEEP=1
if [ "$fail" = 0 ]; then
  [ "${KEEP:-0}" = 1 ] || rm -f "$WK"/fx/model.safetensors
  echo "gate passed, build dir $WK"
else echo "GATE FAILED, build dir $WK (weights kept)"; exit 1; fi
