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
| I3 · real SDXL | `CheckpointLoaderSimple` over a real pinned safetensors file, both CLIPs, UNet, KSampler (euler + karras first), tiled VAE decode, 768 × 768 | PNG within a stated per-pixel bound of the I0 reference at the same seed; memory fits a 16 GiB mini | S4 (`:model/read`), S7 (Metal) for a usable time; the design in [ADR-261106](adr/ADR-261106-i3-sdxl-native-design.md) adds f16 storage and a matrix-multiply Metal tier |
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
([record](../bench/results/2026-11-06-spatial/README.md)). Step 3 (oracle): both blocks were also run against ComfyUI v0.44.0's own
PyTorch `ResBlock` and `SpatialTransformer` on the same parameters (one-off local run, owner-approved download): max error 7.6e-6 and
3.6e-6, and a nudged weight is detected ([record](../bench/results/2026-11-06-comfyui-oracle/README.md), script in
`bench/oracle/comfyui/`). The comparison with comfyui-clj named in the I2 gate has still not been done; the owner has since also
approved ComfyUI's Python as an oracle, and that is what ran.

**Stage M, slice 1 (2026-11-06).** The owner chose to build the f16 matrix tier (decision D2 of the I3 design, PR #607). An
f16 `simdgroup_matrix` GEMM reached from Amu selfhost through dyld (`bench/kernels/gemm-metal/`): bit-exact against an integer
C twin on four shapes, 3.1 to 3.6 TMAC/s on an M1 Max under load
([record](../bench/results/2026-11-06-gemm-metal/README.md)). A GEMM only: convolution, attention, norms and dispatch batching
are the rest of M.

**Stage M, slice 2 (2026-11-06).** A 3 x 3 convolution as an implicit GEMM on the same matrix units (`bench/kernels/conv-metal/`):
bit-exact against an integer C twin on five shapes up to 640 -> 640 channels at 48 x 48, a wrong padding offset detected, 1.1 to 1.9 TMAC/s
on an M1 Max under load ([record](../bench/results/2026-11-06-conv-metal/README.md)). Only the 3 x 3 stride-1 case with multiple-of-32/64
channel counts; bias, epilogues, stride 2, 1 x 1 and the 4-channel end convolutions are not covered.

**Stage M, slice 3 (2026-11-06).** Fused non-causal multi-head attention on the same matrix units (`bench/kernels/attention-metal/`): within
2.4e-4 to 3.1e-4 of a double-precision reference on five shapes including SDXL-like self-attention (2304 x 2304, 10 heads) and cross-attention
against 77 keys, two mutants detected, 0.4 to 1.4 TMAC/s on an M1 Max under load
([record](../bench/results/2026-11-06-attention-metal/README.md)). Approximate (half P, GPU exp), head dim 64 only.

**Stage M, slice 4 (2026-11-06).** The convolution kernel generalised and given epilogues (`bench/kernels/conv-epilogue-metal/`): 1 x 1 and 3 x 3
windows, stride 1 and 2, any channel counts (so SDXL's 4-channel first and last convolutions), and bias, bias + residual or SiLU applied
before the store. Exact against an integer reference for the non-SiLU modes (error 0), 7.9e-8 for SiLU, three mutants detected, 0.66 to 0.85 TMAC/s
on an M1 Max under load, slower than slice 2's plain kernel ([record](../bench/results/2026-11-06-conv-epilogue-metal/README.md)).

**Stage M, slice 5 (2026-11-06).** GroupNorm+SiLU, LayerNorm, GEGLU, nearest 2x upsample, the timestep embedding and the residual add on the GPU
(`bench/kernels/ops-metal/`): 2.2e-7 to 6.9e-5 against a double-precision reference (upsample and add exact), six mutants detected
([record](../bench/results/2026-11-06-ops-metal/README.md)). Their timings are the command-buffer floor; batching dispatches is the next gap.

**Stage M, slice 6 (2026-11-06).** Batching: a dispatch in its own command buffer costs ~264 to 338 us, the same dispatches in one command buffer and one
encoder cost ~3.5 us (tiny kernel) to ~20 us (12 MB moved), 76x and 17x cheaper (`bench/kernels/batch-metal/`,
[record](../bench/results/2026-11-06-batch-metal/README.md)); one SDXL UNet forward makes at least 1,314 counted module calls, about 4.6 ms of dispatch overhead
per call batched against about 347 ms unbatched (arithmetic).

**Stage M, slice 7 (2026-11-06).** The GEMM on non-integer data (`bench/kernels/gemm-f16-metal/`, [record](../bench/results/2026-11-06-gemm-f16-metal/README.md)): against
a double reference built from the half-rounded values the error is 1.3e-5 to 2.1e-5 (float32 accumulation); against the unrounded values it is
8.5e-3 to 1.2e-2 (f16 storage), about 1 percent for random data of order 1. That second figure is the scale of the f16-versus-exact gap I3e has to tolerate.

**Stage M, slice 8 (2026-11-06).** A whole ResBlock on the GPU from one Kotoba program (`bench/kernels/resblock-metal/`): six kernels in one command
buffer and one encoder, within 4.9e-3 of the double-precision block on the same parameters (the f32 CPU twin: 9.4e-6), three mutants detected
([record](../bench/results/2026-11-06-resblock-metal/README.md)). The first composed block on the GPU; the gap to the CPU twin is the half-precision
convolution inputs.

**Stage M, slice 9 (2026-11-06).** A whole SpatialTransformer block on the GPU from one Kotoba program (`bench/kernels/spatial-metal/`): 21 kernels in one command
buffer, within 5.6e-2 (normalised; ~5e-4 of the largest output) of the double-precision block on the CPU twin's parameters (the f32 CPU twin: 3.7e-5), four mutants detected
([record](../bench/results/2026-11-06-spatial-metal/README.md)). With slice 8, both block types of the UNet now run on the GPU in single command buffers.

**I3a, first slice (2026-11-06).** A safetensors reader on the Metal route (`bench/kernels/safetensors-metal/`): header parsed into a tensor catalog, per-tensor size
and dtype admission, data read into a Metal buffer, on a synthetic 28-tensor file equal to a Python oracle line for line; truncation and a wrong shape are refused
([record](../bench/results/2026-11-06-safetensors-metal/README.md)). No real checkpoint and no mapping of multi-gigabyte files yet.

**SHA-256 (2026-11-06).** SHA-256 of a file in Kotoba on the Metal route (`bench/kernels/sha256-metal/`): equal to `shasum -a 256` on 15 inputs including the padding boundaries and a 5 MiB random file, a changed round constant detected, about 74 MiB/s on one core ([record](../bench/results/2026-11-06-sha256-metal/README.md)). With the reader above this covers digest-before-read for a checkpoint; not yet joined to it, not run on a multi-gigabyte file.

**I3b, first slice: the CLIP tokenizer (2026-11-06).** The CLIP byte-pair tokenizer in Kotoba on the Metal route (`bench/kernels/clip-tokenizer-metal/`): the same ids as Hugging Face's
`CLIPTokenizer` on 21 prompt lines (mixed case, contractions, digits, symbols, long words, truncation), three mutants detected, 0.38 s including the table build
([record](../bench/results/2026-11-06-clip-tokenizer-metal/README.md)). ASCII only; ComfyUI's weighting, embeddings and chunking are not implemented.

**I3b, second slice: a CLIP text tower on the GPU (2026-11-06).** A CLIPTextModel-structured tower (`bench/kernels/clip-tower-metal/`) reading Hugging Face safetensors weights ([out, in] Linear layout), 32 kernels in one
command buffer, within 2.3e-3 of PyTorch's float32 `CLIPTextModel` on random weights (2 layers, width 128, the real vocabulary and 77 positions), four mutants detected
([record](../bench/results/2026-11-06-clip-tower-metal/README.md)). Not CLIP-L / CLIP-G size, not joined to the tokenizer, no SDXL penultimate-layer or projection handling.

**I3b, third slice: prompt text to hidden states (2026-11-06).** The tokenizer and the tower joined in one Kotoba program (`bench/kernels/text-encode-metal/`): the same 77 ids as
Hugging Face's `CLIPTokenizer` and hidden states within 2.4e-3 of PyTorch's `CLIPTextModel`, two mutants detected
([record](../bench/results/2026-11-06-text-encode-metal/README.md)). First end-to-end path from text to model output in Kotoba; still the small random-weight tower.

**Stage M, slice 10: a ComfyUI UNet on the GPU (2026-11-06).** ComfyUI's own `UNetModel` in a mini configuration (981 k parameters; ResBlocks, a Downsample, SpatialTransformers, skip concatenation,
an Upsample, the time-embedding MLP) run end to end from one Kotoba program, weights read from a safetensors file under ComfyUI's parameter names, **219 kernels in one command buffer**: final output
within 1.4e-3 of ComfyUI's float32 forward pass, every block within 4.3e-3, three mutants detected, ~7.9 ms (`bench/kernels/unet-metal/`,
[record](../bench/results/2026-11-06-unet-metal/README.md)). The whole-UNet wiring named in the I3 ADR exists at mini scale; SDXL sizes, trained weights and the sampler do not.

**Stage M, slice 11: the SDXL graph timed on the GPU (2026-11-06).** ComfyUI's SDXL `UNetModel` graph with synthetic weights (2.57 billion half values, ~2,100 kernels in one command buffer, `bench/kernels/unet-sdxl-metal/`): about 0.84 s at 512 px,
1.67 s (best; up to 2.4 s under contention) at 768 px and about 4.1 s at 1024 px per UNet call on an M1 Max under load ([record](../bench/results/2026-11-06-unet-sdxl-metal/README.md)).
At 768 px that is ~1.1 TMAC/s overall, the same order as the ~1.3 TMAC/s ComfyUI reached on a smaller GPU, so about 84 s for the 50 UNet calls of a 25-step render: not yet faster. This replaces the arithmetic in the I3 ADR's time table with one measurement.

**Stage M, slice 12: where the SDXL time goes, and a conv retune (2026-11-06).** Ablating single kernels puts the linear layers at about 0.65 to 0.9 s of a ~1.8 s call at 768 px, the convolution about 0.55 to 0.7 s, attention at noise level, the rest about 0.3 to 0.4 s. The runtime-parameter conv kernel got its pixel decode hoisted, constant-divisor index decode and a 64-wide K tile with vector weight loads; the mini-UNet correctness gate is unchanged (final 1.368e-3) and the SDXL-shaped call is probably modestly faster (about 15 percent at the median at 512 px; not resolved at 768 px under host load) ([record](../bench/results/2026-11-06-conv-tune/README.md)). Next: the linear (f16 GEMM) kernel.

**Stage M, slice 13 (negative result, 2026-11-06).** Staging the linear kernel through threadgroup memory kept the ComfyUI gate unchanged but gave no measurable speedup under host load, so it was not merged ([record](../bench/results/2026-11-06-linear-staging-negative/README.md)). Further tuning of the linear and conv kernels needs a quiet host and the Metal counters, not more guessing.

**Stage M, slice 14: the sampler on the GPU (2026-11-06).** The sigma table, ComfyUI's `simple` schedule and the Euler loop with EPS input scaling and classifier-free guidance run on the GPU around toy stand-in models and agree with ComfyUI's own `ModelSamplingDiscrete` / `simple_scheduler` / `sample_euler` to 1e-6 (sigmas) and 8e-6 (a 4,000-fold-cancelling final latent); four mutants are detected ([record](../bench/results/2026-11-06-sampler-metal/README.md)). Not the UNet inside it, only Euler.

**Stage M, slice 15: the two SDXL text towers at full size (2026-11-06).** The CLIP tower program is now parametric and also outputs the penultimate hidden state and the projected end-token embedding. At OpenAI CLIP-L (12 layers, 768) and OpenCLIP bigG (32 layers, 1,280; a 2.8 GB random-weight file) it agrees with transformers' own `CLIPTextModelWithProjection` to 2.7e-3 / 4.8e-3 / 1.5e-3 (L: last, penultimate, projected) and 3.4e-3 / 1.0e-2 / 1.8e-3 (G), in about 12 ms and 70 ms ([record](../bench/results/2026-11-06-clip-full-metal/README.md)). Random weights, Hugging Face tensor names only (SDXL's OpenCLIP checkpoint naming is not mapped), no L+G concatenation or conditioning embeddings.

**Stage M, slice 16: the VAE decoder (2026-11-06).** SDXL's decoder architecture (post_quant_conv, mid block with a 512-wide single-head attention over all pixels, four up levels) runs on the GPU in ~120 dispatches and agrees with ComfyUI's own `Decoder` (random weights, f32 CPU) to 6.2e-3 at 512 px and 6.3e-3 at 1024 px (the real SDXL decoder shape, 49.5 M parameters), 4.8e-3 on the small config with every stage inside 1.1e-2; about 1.8 s at 512 px and 10 s at 1024 px under heavy host load ([record](../bench/results/2026-11-06-vae-metal/README.md)). The GPU watchdog kills long command buffers and long dispatches at 1024 px, so the program issues short pieces and checks every command buffer (checking only the last one let a failure pass silently: an intermittent wrong image). With this, every stage of an SDXL render has a gated GPU implementation on the Metal route (text towers, UNet at SDXL shape for timing, sampler, VAE), but the pieces are not joined, the UNet is not gated at SDXL scale, and nothing used a trained checkpoint.

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
