# Roadmap: image, video and audio generation on Kotoba and Amu selfhost

Owner direction, 2026-11-06 (JUN-927): move murakumo's image, video and audio
generation off Python ComfyUI / PyTorch and C++ `sd.cpp`, onto Kotoba built by
Amu selfhost, running natively on the fleet's nodes. First scope: this design,
plus the first image stage. Video and audio are designed and their blockers are
recorded; nothing for them is implemented here.

Status: plan. Nothing below is implemented by this document. Sections marked
"observed" were read from code or the live service on 2026-11-06; every stage
target is a target.

## Where it stands (observed 2026-11-06)

### What serves media today

| Kind | Route today | Where in this repo | Not Kotoba because |
| --- | --- | --- | --- |
| Image, SDXL (`awai-network/hokusai`: waiREALMIX, animagine) | Python ComfyUI on the node; the worker queues a workflow over loopback HTTP and fetches the PNG | `src/murakumo/infer/image_worker.cljk` (`comfy-render!`), `image_job.cljk` | PyTorch, CUDA/ROCm/MPS |
| Image, Qwen Image 2.1 (GGUF) | `sd-cli` from stable-diffusion.cpp, pinned revision `3f8527a4…`, three artifacts with sha256 | `src/murakumo/infer/image_native.cljk` | a C++ program, not Amu-built |
| Video (MiniMax-H3, LTX-Video 2B, LTX-2.3 22B, Wan 2.2, SVD-XT, HunyuanVideo 1.5) | ComfyUI instances on the gad APU | `resources/murakumo/resource-classes.edn` (`:apu/video`), `capacity-observations.edn` | same as SDXL |
| Audio (Qwen3-TTS, Stable Audio Open, ace-step, musicgen) | per-function services behind `generation.murakumo.cloud` | `kotoba-lang/cloud-murakumo-generation` | Python services |
| 3D (TRELLIS, Hunyuan3D 2.1) | out of scope here | | |

`kotoba-lang/comfyui` (comfyui-clj) is a Kotoba-side ComfyUI: API-format
workflows, a cached topological executor, a prompt server, and executable
SD1/SD2/SDXL pieces (safetensors reader, CLIP tokenizer and encoder, UNet,
VAE, DDIM/euler/euler_ancestral/dpmpp_2s_ancestral/dpmpp_2m, Karras
schedules). Its own README says it is not yet a verified production SD/SDXL
render and that production still uses Python ComfyUI. murakumo does not call
it: `media.cljk` only notes that the workflow wire format is the same so the
graph could be handed over later. It is `.cljk` on the Deno/Metal and JVM
routes, not an Amu-built native program, and it has no video, audio or 3D
nodes.

### What is live

Probed from outside on 2026-11-06 around 04:10Z; the Tailscale read came later the same day.

- `api.murakumo.cloud`: `/v1/models` lists `awai-network/hokusai` and
  `ao/hokusai` (image). `POST /v1/images/generations` returned 502: its gateway
  answered Cloudflare 530 / 1033 (tunnel down). `POST /v1/videos/generations`
  is 404, `POST /v1/audio/speech` is 503 `no VOICE_URL configured`. `/ready`
  reports `gateways-ready: 0`.
- Video and music jobs enter at `generation.murakumo.cloud`, which needs a
  generation-scope token. No job was submitted.
- Over Tailscale only `jacob` answered, running no generation or inference
  server. gad, the aiueos nodes, xavier and the Mac minis did not answer.
  `21mbp` and `25mbair` are not inference nodes (owner, 2026-11-06).

So no generation route could be shown working on 2026-11-06. The measurements
in `capacity-observations.edn` (2026-08/09) are history, not current state.

### What stops a Kotoba generation engine

Gaps 1–8 of [inference-roadmap.md](inference-roadmap.md) all apply. Diffusion
adds these:

| # | Gap | Effect |
| --- | --- | --- |
| G1 | No convolution, GroupNorm, non-causal attention, nearest-upsample or timestep-embedding kernels in the inference stages (S0–S8 plan a causal LM) | a UNet/VAE has nothing to run on, even once S1–S3 land |
| G2 | Checkpoints are large: SDXL fp16 is about 6.9 GB, LTX-2.3 22B fp8 about 22 GB, MiniMax-H3 about 43 GB. The Mac CLI gives guests no filesystem, a 5 s `run` and 1 MiB source (gap 5); S4 plans a read-only map for one file | a model cannot reach a guest until S4 exists |
| G3 | The 16 GiB Mac minis cap an SDXL render at 768 × 768 (1024 swaps, measured 2026-09, `image_job.cljk`) | the native engine must fit the same budget or the size table changes |
| G4 | gad and the aiueos nodes are Linux. Static Linux images have a 64 MiB address space and no `mmap` after start; GPU is reached through a provider process (amu ADR 0371), and the linked provider route is refused by the native-only entry | the video tier (gad's 51 GB APU) has no native route to its GPU |
| G5 | Text encoders: SDXL needs two CLIPs (comfyui-clj has them); LTX-Video and Wan need T5-family encoders; Qwen Image needs a Qwen3-VL encoder | each is a transformer the S5 engine does not cover |
| G6 | Video VAEs are causal 3D convolutions and the DiTs use 3D rotary attention over frames × height × width | new kernels beyond the image set |
| G7 | Audio codecs (EnCodec-style, DAC, Stable Audio's VAE) are strided 1D convolutions with snake/GELU activations | new kernels; TTS also needs S5's autoregressive loop |

## Principles

1. **Same rules as the inference roadmap.** Spec before intrinsics; exact gates
   with a twin and a mutant; one recorded number per stage; no CI changes while
   CDCI is stopped; no C, JVM, nbb or Python on the path; public wording follows
   the AWAI-18 claim gate.
2. **The reference is allowed as a one-off oracle, never as a route.** The
   Python ComfyUI render, `sd-cli`, and comfyui-clj on JVM/Deno may be run on a
   developer machine to obtain reference tensors or images (owner decision
   2026-10-29/11-06). Record command, versions, inputs, outputs and date. They
   are not a fallback and not a required comparison in a gate.
3. **The job contract does not change.** `render-image` pull jobs, the size
   table, the single-slot admission and the resource classes stay as they are;
   a native runner replaces the renderer behind them. A node that has not
   qualified the native runner keeps claiming nothing, as today.
4. **Digest before execute.** Every checkpoint is pinned by revision and sha256
   before it is read (`image_native.cljk` already does this for Qwen Image).
   Where no digest exists, the artifact is recorded as unverified and the stage
   stays open.
5. **Do not guess the order across modalities.** The stages below are ordered by
   what each needs that does not exist yet; "Open decisions" lists where the
   owner's call changes the order.

## Image stages

I-stages depend on the S-stages of the inference roadmap; they do not replace
them.

| Stage | What | Gate | Needs |
| --- | --- | --- | --- |
| I0 · record | this document; the two current renderers described; a reference set of Python ComfyUI renders (fixed seed, prompt, steps) saved with their SDXL checkpoint digest | the reference images and the command that made them are in `bench/results/` | a node that is up (none today) |
| I1 · tensor kernels | `conv2d` 3 × 3 and 1 × 1, GroupNorm, SiLU, LayerNorm, GEGLU, non-causal multi-head attention (self and cross), nearest upsample, sinusoidal timestep embedding, as Kotoba kernels in f32 | each kernel against a C twin and the f64 oracle (comfyui-clj `num` CPU), bit-exact or a stated tolerance, plus a mutant | S1 (merged), S2 bytes; S3 for speed |
| I2 · tiny graph | the comfyui-clj test graph (U-shaped denoiser, 8 tensors, two DDIM steps) run natively, then a one-block SpatialTransformer | latents equal comfyui-clj's CPU result within tolerance; a flipped weight changes the output | I1 |
| I3 · real SDXL | `CheckpointLoaderSimple` over a real pinned safetensors file, both CLIPs, UNet, KSampler (euler + karras first), tiled VAE decode, 768 × 768 | PNG within a stated per-pixel bound of the I0 reference at the same seed; memory fits a 16 GiB mini | S4 (`:model/read`), S7 (Metal) for a usable time |
| I4 · serve | the image worker claims `render-image` jobs and runs the I3 binary instead of ComfyUI; the occupancy and receipt logic is unchanged | the worker's existing receipt tests pass against the native runner; the render-time number is recorded next to ComfyUI's 60–100 s warm | I3, a live node |
| I5 · other image models | Qwen Image 2.1 (replaces `sd-cli`), SD1/SD2 variants, other samplers | per model, I3's gate | I3, G5 |

The first executable unit is I1, one kernel at a time, starting with GroupNorm +
SiLU because it is the smallest kernel with a fused-activation question.

**I1 progress (2026-11-06).** Slice 1, GroupNorm (affine) + SiLU in f32, is in
`bench/kernels/groupnorm-silu/`: a Kotoba kernel built by `bin/amu`, a C twin, an
f64 oracle and `run.sh`. Its checksum equals the C twin's bit for bit, a 1-ulp
mutant is caught, and the outputs are within 5e-6 of the f64 reference. It is
about 17x slower than `cc -O2` (diagnostic). Record:
[bench/results/2026-11-06-groupnorm-silu](../bench/results/2026-11-06-groupnorm-silu/README.md).
Slice 2, conv2d 3 × 3 (stride 1, pad 1, bias) in `bench/kernels/conv2d-3x3/`, passes the same
gate on a square and a non-square shape ([record](../bench/results/2026-11-06-conv2d-3x3/README.md)); about
10x slower than `cc -O2`, diagnostic on a busy host. Slice 3, non-causal multi-head attention (self and cross) in `bench/kernels/attention/`, passes it on three
shapes ([record](../bench/results/2026-11-06-attention/README.md)); 15 to 50x slower than `cc -O2`, diagnostic.
Slice 4 adds the rest of the SDXL block set under `bench/kernels/` with a shared `gate.sh`: LayerNorm, matmul (linear), GEGLU
(erf by Abramowitz-Stegun), nearest 2x upsample, sinusoidal timestep embedding (own sin/cos reduction) and a general
conv2d (1 × 1, 3 × 3, stride 1 or 2); every one is bit-exact against its twin with a detected mutant
([record](../bench/results/2026-11-06-i1-rest/README.md)). I1's kernel list is now covered at the
single-kernel level; what is not: f16 storage, GPU, the label-embedding MLP and ResBlock/SpatialTransformer wiring (I2), and
speed (roughly 6 to 15 times slower than `cc -O2`, diagnostic). The reference here is the twin's own math in double, not yet
comfyui-clj or ComfyUI output.

**I2 progress (2026-11-06).** Step 1, a full ResBlock composed from the I1 kernels, is in
`bench/kernels/resblock/`: bit-exact against its twin on two shapes, within 7.2e-6 of an f64 reference, a dropped skip
connection is detected ([record](../bench/results/2026-11-06-resblock/README.md)). Step 2, a SpatialTransformer block (groupnorm, proj_in, self- and
cross-attention, GEGLU feed-forward, proj_out, residuals) in `bench/kernels/spatial/`: bit-exact against its twin on two shapes,
within 7e-6 of an f64 reference, a wrong cross-attention source is detected
([record](../bench/results/2026-11-06-spatial/README.md)). The comfyui-clj comparison named in the I2 gate has not been done:
it needs a Deno/JVM run and a checkpoint-layout mapping.

## Audio stages

| Stage | What | Gate | Needs |
| --- | --- | --- | --- |
| A1 · codec decoder | a strided 1D conv decoder (the codec half of Qwen3-TTS) producing a WAV from fixed codes | samples within tolerance of the reference decoder, WAV header exact | I1-style conv kernels in 1D |
| A2 · TTS LM | the autoregressive part of Qwen3-TTS as an S5-style loop emitting codec tokens | the same token sequence as the reference for a fixed text, seed and sampler | S5 |
| A3 · serve | `/v1/audio/speech` answered by A1 + A2; `VOICE_URL` set | WAV equals A2/A1's offline output | A2, a live node |
| A4 · music and SFX | Stable Audio Open, ace-step, musicgen | per model | A1, I3-class machinery (DiT + T5) |

The judgement behind putting audio's first usable result before video's: TTS
reuses the S5 language-model loop and needs only the codec decoder on top, while
video needs the diffusion stack and a 3D VAE at 22–43 GB. This is an inference
from the dependencies, not a measurement.

## Video stages

Blocked behind I3. Recorded here so the dependency is explicit.

| Stage | What | Gate | Needs |
| --- | --- | --- | --- |
| V1 · 3D kernels | causal `conv3d`, 3D rotary attention, frame-chunked VAE decode | kernel twins as in I1 | I1 |
| V2 · LTX-Video 2B | the smallest video model in the fleet (frames 8n + 1, 9–121) | a short clip within a stated bound of the reference | V1, T5 encoder (G5), I3 |
| V3 · Linux/gad route | the same engine on gad's APU through the Vulkan provider | needs a native-only route for the linked provider (amu ADR 0374 records it as refused today) | G4 |
| V4 · large models | LTX-2.3 22B, Wan 2.2, MiniMax-H3 | not planned until V2 and V3 are measured | memory beyond one mini |

## Open decisions (owner)

1. **Order of modalities.** The plan above reaches usable audio before video by
   dependency; image first. If video is the business priority, the cost is that
   V1 and G4 come before any usable result.
2. **Linux / gad.** The fleet's video capacity is on gad (Linux, AMD APU). The
   native-only entry refuses the linked GPU provider, so V3 has no route. Whether
   to open one, or to run video on Macs only, is a decision.
3. **Kernel strategy.** Write each kernel in Kotoba (I1 as planned), or route
   the diffusion graph through the KIR v2 → MSL path of amu ADR 0373/0375 once
   it can batch dispatches.
4. ~~comfyui-clj's role.~~ **Decided 2026-11-06 (owner): comfyui-clj stays a
   verification reference (oracle) only.** The graph executor and the kernels
   live in the native runner; comfyui-clj supplies reference tensors and
   graphs for gates, run on a developer machine and recorded as in principle 2.

## Related work

- JUN-927: this roadmap. JUN-863: itonami `image_generate`, STT and voice loop.
- JUN-701 / JUN-740: the Amu-native engine and murakumo serving it.
- JUN-804: S1–S8. JUN-819 (ADR 0405): first Metal kernel. JUN-377 (ADR 0375).
- `kotoba-lang/comfyui`, `kotoba-lang/cloud-murakumo-generation`.
