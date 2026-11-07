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

**SHA-256 (2026-11-06).** SHA-256 of a file in Kotoba on the Metal route (`bench/kernels/sha256-metal/`): equal to `shasum -a 256` on 15 inputs including the padding boundaries and a 5 MiB random file, a changed round constant detected, about 74 MiB/s on one core ([record](../bench/results/2026-11-06-sha256-metal/README.md)). With the reader above this covers digest-before-read for a checkpoint; joined to the render program in slice 37; run on a 4.5 GiB file in slice 36 (equal to `shasum`, 87 MiB/s, [record](../bench/results/2026-11-06-sha256-multi-gb/README.md)).

**I3b, first slice: the CLIP tokenizer (2026-11-06).** The CLIP byte-pair tokenizer in Kotoba on the Metal route (`bench/kernels/clip-tokenizer-metal/`): the same ids as Hugging Face's
`CLIPTokenizer` on 21 prompt lines (mixed case, contractions, digits, symbols, long words, truncation), three mutants detected, 0.38 s including the table build
([record](../bench/results/2026-11-06-clip-tokenizer-metal/README.md)). ASCII only at this slice (non-ASCII text since slice 41); ComfyUI's weighting, embeddings and chunking are not implemented.

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

**Stage M, slice 16: the VAE decoder (2026-11-06).** SDXL's decoder architecture (post_quant_conv, mid block with a 512-wide single-head attention over all pixels, four up levels) runs on the GPU in ~120 dispatches and agrees with ComfyUI's own `Decoder` (random weights, f32 CPU) to 6.2e-3 at 512 px and 6.3e-3 at 1024 px (the real SDXL decoder shape, 49.5 M parameters), 4.8e-3 on the small config with every stage inside 1.1e-2; about 1.8 s at 512 px and 10 s at 1024 px under heavy host load ([record](../bench/results/2026-11-06-vae-metal/README.md)). The GPU watchdog kills long command buffers and long dispatches at 1024 px, so the program issues short pieces and checks every command buffer (checking only the last one let a failure pass silently: an intermittent wrong image). With this, every stage of an SDXL render has a gated GPU implementation on the Metal route (text towers, UNet at SDXL shape for timing, sampler, VAE), but the pieces are not joined, the UNet is not gated at SDXL scale (see slice 17 for its topology), and nothing used a trained checkpoint.

**Stage M, slice 17: the SDXL UNet graph, gated (2026-11-06).** A program that reads ComfyUI's tensor names runs SDXL's whole UNet topology (depths 0/2/10, middle depth 10, skip concatenations, label embedding) in 2,074 dispatches and agrees with ComfyUI's own `UNetModel` built from `supported_models.SDXL` at every one of 21 stages: worst stage 9e-3, final 1.2e-3, at 256 and 512 px, at model_channels 64 (158 M parameters, random weights) instead of 320. It found a skip-tensor offset bug in the slice-11 timing program (fixed; timing unchanged). Not at SDXL's widths and no trained weights ([record](../bench/results/2026-11-06-unet-sdxl-named/README.md)).

**Stage M, slice 18: a whole render in one GPU program (2026-11-06).** The sampler, the SDXL-topology UNet (run twice per step, conditional and unconditional) and the VAE decoder are joined in one Kotoba program: noise latent + two context/label pairs in, a 256 x 256 image out, 4 guided Euler steps, 2,204 recorded dispatches. It agrees with ComfyUI's own `UNetModel` / `ModelSamplingDiscrete` / `simple_scheduler` / `sample_euler` / `Decoder` run on the same random weights to 2.8e-2 on every step's latent and 2.0e-2 on the image (limit 5e-2, set after the first run and justified by the guidance and step-length amplification of the 1e-3 UNet error); four mutants are detected ([record](../bench/results/2026-11-06-pipeline-metal/README.md)). Tiny widths, random weights, contexts read from files (no text tower in the loop). What now stands between this and a real render is not code structure but: widths 5x larger, a trained checkpoint and its name mapping (D1), the text path in front, and a place to run it (D4).

**Stage M, slice 19: SDXL's conditioning on the GPU (2026-11-06).** The render program now builds its context and label vector itself from the two towers' penultimate states and the pooled vector: the L+G concatenation (L first) and `SDXL.encode_adm` (pooled, then Timestep(256) of height, width, crop_h, crop_w, target_height, target_width) agree with ComfyUI's own code to 0 and 3.1e-5 (limit 1e-4, widened from an initial 1e-5 for the float32 sin/cos reason), six mutants detected ([record](../bench/results/2026-11-06-pipeline-conditioning/README.md)). The towers are still separate programs; running tokenizer, both towers and the render in one process is the remaining code gap on the text path.

**Stage M, slice 21: both towers inside the render program (2026-11-06).** The render program now runs OpenAI CLIP-L and OpenCLIP bigG at full size (from token ids, for the prompt and the empty prompt) and builds the conditioning itself: 2,886 dispatches, conditioning within 1.0e-2 of Hugging Face's towers, step latents within 3.6e-2 and the image within 1.7e-2 of ComfyUI's render, six mutants detected ([record](../bench/results/2026-11-06-pipeline-text/README.md)). Only the tokenizer (a separate Kotoba program, gated earlier) is not in it. The main function hit a compiler limit around 250 let bindings, worked around by moving recorders into helpers.

**Stage M, slice 22: prompt text in, image out (2026-11-06).** The Kotoba CLIP tokenizer is now inside the render program: it reads prompt text, tokenizes (BOS/EOS, end-token padding for L, 0 padding for bigG), runs both towers, builds the conditioning and renders, all in one process. Token ids equal Hugging Face's exactly (0 mismatches over 4 x 77), the rest of the chain is unchanged from slice 21, seven mutants detected ([record](../bench/results/2026-11-06-pipeline-tokenizer/README.md)). Still random weights, a small UNet and VAE, 256 px; ASCII prompts only; the render gate uses two prompts, the tokenizer's own gate 21.

**Stage M, slice 23: a GPU-side timer, and a faster 3x3 convolution (2026-11-06).** The slice-11 program now has a profiling variant that times every dispatch with the command buffer's GPU timestamps, immune to other processes' CPU load: at 512 px the linear layers take the most GPU time, then the convolutions, then attention (about 7 percent). A specialised 3x3 stride-1 convolution cut the convolution time by 15 to 20 percent (8 to 10 percent of the call) with unchanged numerics in all gates: per UNet call 0.62 s at 512 px, 1.52 s at 768 px, 2.87 s at 1024 px on a host at load 8, the 1024 px VAE decode 6.0 s ([record](../bench/results/2026-11-06-gpu-profile-conv-k3s1/README.md)). Next: the linear layers.

**Stage M, slice 24: the linear layers (2026-11-06).** With the GPU timer, slice 13's threadgroup staging turns out measurably slower, as are k-unrolling and eight simdgroups; what costs is the transposed weight load. Storing the weight [K, N] (a transposing conversion at load) cut linear time by about 16 percent with unchanged numerics in all gates (a mutant on the new conversion is detected). One UNet call (synthetic weights, load 7): 0.56 s at 512 px, 1.12 s at 768 px, 2.32 s at 1024 px; about 56 s of UNet time for a 25-step guided render at 768 px, under the 72 s ComfyUI took on a 16 GiB M4 mini (different chip, UNet only) ([record](../bench/results/2026-11-06-linear-transposed-weights/README.md)).

**Stage M, slice 25: SDXL's UNet at its real width, gated (2026-11-06).** The named-weights UNet at model_channels 320 (2,567,463,684 parameters, a 10.27 GB float32 file) agrees with ComfyUI's own `UNetModel` at all 21 stages (worst 9.5e-3, final 8.2e-4) at a 256 px latent, 400 ms. The earlier "does not fit in RAM" was the fixture's, not the GPU's: the new low-RAM path streams the weights into the file and memory-maps it for the CPU oracle (1.07 GB peak instead of ~20 GB) ([record](../bench/results/2026-11-06-unet-sdxl-real-width/README.md)). Random weights; the remaining gaps are a trained checkpoint (D1) and a place to run (D4).

**Stage M, slice 26: attention in one pass, and real width at 512 px (2026-11-06).** The fused attention kernel now uses an online softmax in a single pass (statistics spread over all threads, running output rescaled by a diagonal-matrix product, no threadgroup barrier in the key loop): about 45 to 50 percent less attention GPU time, results within the same gates (UNet at 64 channels, mini UNet, whole render incl. the causal CLIP towers). The UNet at real width (320 channels, 2.57 B parameters) also passes at 512 px (worst stage 1.11e-2, final 8.4e-4, 660 ms). One UNet call (synthetic weights, load 11): 0.54 s at 512 px, 1.04 s at 768 px, 2.19 s at 1024 px, about 52 s of UNet time for 25 guided steps at 768 px ([record](../bench/results/2026-11-06-attention-online-softmax/README.md)).

**Stage M, slice 27: an SDXL single-file float16 checkpoint can be loaded (2026-11-06).** The render program reads a float16 file in the real single-file layout (UNet under `model.diffusion_model.`, VAE under `first_stage_model.`, CLIP-L under `conditioner.embedders.0.transformer.`, OpenCLIP-bigG under `conditioner.embedders.1.model.` with stacked q/k/v, `c_fc`/`c_proj`, `ln_final`, transposed `text_projection`): float16 weights are converted on the GPU, parameter tensors to float32 at first use. A fixture in exactly that layout (made by inverting ComfyUI's own conversion table) gives the same agreement with ComfyUI as before (ids exact, conditioning 5.9e-3, image 1.7e-2) and ten mutants are detected ([record](../bench/results/2026-11-06-single-file-f16/README.md)). No real checkpoint has been read; that waits on D1.

**Stage M, slice 28: single-file loading survives mixed dtypes and extra tensors (2026-11-06).** A harder fixture (VAE encoder tensors, scalar EMA tensors, a float32 `alphas_cumprod`, four read tensors stored as float32 inside the float16 file) first gave NaN: the loader chose float16 or float32 mode from the first header entry, and safetensors orders the header by dtype. Fixed (mode = any float16 tensor; float32 tensors in a float16 file are copied to the parameter buffer); same agreement as before and eleven mutants detected ([record](../bench/results/2026-11-06-single-file-mixed/README.md)).

**Stage M, slice 29: the VAE's float16 range, and a real-size file (2026-11-06).** A stress fixture (decoder residual stream up to 1.5e5, past the half range) showed that my claim that the VAE was safe in float16 was wrong: the program gave NaN where an un-normalised float tensor is converted to half (the Upsample input, the 1x1 shortcut input). Fixed with a per-tensor power-of-two scale found on the GPU, convolution on the scaled tensor, rescale and bias afterwards: the stress image matches the float32 oracle (6.5e-3) and normal-scale numerics are unchanged. An int64 `position_ids` tensor is indexed and ignored. A random-weight float16 single file of the real size (6.87 GB, 2,406 tensors) renders end to end with finite values and a 15.3 GB peak footprint ([record](../bench/results/2026-11-06-vae-range-and-real-size/README.md)).

**Stage M, slice 30: a 128-channel tile for the 3x3 convolution (2026-11-06).** Profiling the VAE decode showed the convolution at 75 percent of its GPU time and GroupNorm at 9; the convolution kernel's gathered input tile is now shared by 128 output channels with eight simdgroups (a four-simdgroup version of the same tile is 4 to 5 times slower): about 40 percent less convolution GPU time in the UNet and the VAE, unchanged numerics in all gates (UNet at 64 and 320 channels, VAE incl. the range stress, mini UNet, whole render in both checkpoint layouts). VAE decode 0.85 s at 512 px and 6.0 s at 1024 px on a busy host ([record](../bench/results/2026-11-06-conv-k3s1-bm128/README.md)).

**Stage M, slice 31: GroupNorm for large groups (2026-11-06).** For groups of 262,144 elements or more (the VAE at 512 px and up) the statistics are computed as partial sums over 64 tiles per group and the normalisation is element-parallel instead of 32 threadgroups reading the group three times: about 75 percent less GroupNorm GPU time in the 1024 px decode (0.59 to 0.82 s down to 0.19 s), unchanged numerics in the gates, two mutants on the new kernels detected ([record](../bench/results/2026-11-06-groupnorm-tiles/README.md)).

**Stage M, slice 32: the VAE attention in one pass (2026-11-06).** The 512-wide single-head attention of the VAE mid block got the online softmax: about 12 percent less GPU time (the kernel is bound by tile loads, not by the softmax); two variants that did not help (shared Q loads, untransposed K) are recorded, accuracy slightly lower but inside the limits ([record](../bench/results/2026-11-06-attn-wide-online/README.md)).

**Stage M, slice 33: the linear kernel (2026-11-06).** The structural ideas left open (a 128-row tile with eight simdgroups, threadgroup-staged tiles) were measured and give nothing for the linear layers; an explicit x4 unroll of the k loop with named fragment registers is about 12 percent faster (my slice-24 "unrolling is slower" came from writing the fragments as indexed arrays, which spilled). Numerics unchanged in all gates including the real-width UNet at 512 px; one UNet call about 0.43 s at 512 px and 0.84 s at 768 px on a loaded host ([record](../bench/results/2026-11-06-linear-unroll-x4/README.md)).

**Stage M, slice 34: software pipelining of the convolution gather (2026-11-06).** The next tile's gather loads are issued before the current tile's matrix multiplies and consumed at the top of the next iteration: 10 to 15 percent less convolution GPU time in the UNet and about 7 percent in the VAE, numerics unchanged in all gates including the real-width UNet at 512 px (532 ms) ([record](../bench/results/2026-11-06-conv-gather-prefetch/README.md)).

**Stage M, slice 35 (negative result, 2026-11-06).** A resident Q tile for the VAE's 512-wide attention (16 queries per threadgroup so that Q fits in threadgroup memory) is about 50 percent slower than the current kernel (twice the K and V traffic per query) and was rejected; no SDXL-class checkpoint exists on this Mac to test trained weights with (only unrelated speech/language models), so that stays behind D1 ([record](../bench/results/2026-11-06-attn-wide-resident-q/README.md)).

**Stage M, slice 37: digest before read (2026-11-06).** The render program hashes its checkpoint (the slice-36 SHA-256, now helpers in the program) and traps unless the digest equals a pinned digest file, before reading the file; a one-character-different digest is refused with no render output, both layout gates still pass, and a 6.87 GB real-size file hashes and renders. The test digests come from `shasum` on the fixture, so a real pin from the publisher still waits on D1; since slices 38 to 40 the program hashes and reads through one descriptor and traps if the size, modification time or change time of the file changed across the hash-and-load window (an in-place write, even with a restored mtime, and a rename-over are refused), tested in both layouts ([record](../bench/results/2026-11-06-digest-before-read/README.md)).

**Stage M, slice 44: prompt weighting (2026-11-07).** `(text:1.2)`, `(text)`, nesting and `\\(` escapes now work as in ComfyUI: a Kotoba port of its parser gives each token a weight and two Metal kernels blend each token's hidden state toward the empty prompt's, for both prompts and both towers. Equal to ComfyUI's own tokenizer on 25 weighted prompts (ids exact, weights within 8/65536), seven mutants detected, and the render-program gate passes in both layouts with both prompts weighted. Chunking past 75 tokens and `embedding:` remain open ([record](../bench/results/2026-11-07-prompt-weighting/README.md)).

**Stage M, slice 45: prompts longer than 75 tokens (2026-11-07).** Both programs used to truncate at 75 tokens. They now cut a prompt into blocks of 77 with ComfyUI's batching rule, run each block through the towers, join the outputs along the sequence and repeat the shorter of the two prompts to the common length; the cross-attention key count is a run-time value. 32 blocks equal ComfyUI's tokenizer (ids exact, weights within 8/65536) and the render-program gate passes in both layouts with a 2-block and a 3-block prompt (ctx 9.4e-3, image within the gate), with six mutants detected. More than 4 blocks and `embedding:` names remain open ([record](../bench/results/2026-11-07-prompt-blocks/README.md)).

**Stage M, slice 46: our UNet against ComfyUI's own on the same GPU (2026-11-07).** ComfyUI's SDXL `UNetModel` in float16 on PyTorch MPS, same Mac, similar load: 0.325 / 0.94 to 1.05 / 2.41 s per call at 512 / 768 / 1024 px, against our 0.44 / 1.09 / 2.59 s, i.e. 4 to 35 percent slower, closest at large sizes. This replaces the older comparison with a different machine's figure; the remaining gap is the small-layer overhead, not the GEMM or convolution ([record](../bench/results/2026-11-07-unet-vs-comfyui-mps/README.md)).

**Stage M, slice 47: Greek, Cyrillic, Armenian and Hebrew prompts (2026-11-07).** The tokenizer, standalone and in the render program, now handles these scripts (class and lower-case tables generated from `unicodedata`, a 210-pair NFC table, the capital sigma and anything NFC changes refused). 18,632 corpus lines equal Hugging Face's tokenizer, two table mutants are detected, and the render-program gate passes in both layouts with a mixed-script prompt ([record](../bench/results/2026-11-07-clip-tokenizer-unicode/README.md)).

**Stage M, slice 48: `embedding:` words (2026-11-07).** Textual-inversion embeddings (SDXL `.safetensors` with `clip_l` and `clip_g`) are loaded from a directory and their rows replace the word in the towers' inputs, with ComfyUI's name rules ported exactly. 16 prompts equal ComfyUI's tokenizer (ids and rows exact), the render-program gate passes in both layouts with embeddings in both prompts, six mutants are detected ([record](../bench/results/2026-11-07-embeddings/README.md)).

**Stage M, slice 49: schedulers normal and karras, sampler dpmpp_2m (2026-11-07).** The render program picks its sigma schedule (simple, normal, karras) and sampler (Euler, DPM-Solver++ 2M) at build time. Each pair tested equals ComfyUI's own scheduler and sampler source on the toy model (steps within 4e-2), with sampler and scheduler mutants detected; the default build is unchanged ([record](../bench/results/2026-11-07-samplers-schedulers/README.md)). Other samplers and schedulers remain.

**Stage M, slice 41: non-ASCII prompts (2026-11-07).** The Kotoba CLIP tokenizer, standalone and inside the render program, now handles UTF-8: Japanese, accented Latin, fullwidth forms, emoji. All 17,734 corpus lines and the 27 original lines equal Hugging Face's tokenizer, nine mutants are detected, characters it cannot handle faithfully are refused, and the render program renders a Japanese-plus-ASCII-plus-emoji prompt with exact ids and the usual agreement. Slice 42 adds NFC for a letter plus one combining mark (decomposed Japanese and accents; 17,988 corpus lines equal). Slice 43: the special-token spellings in a prompt now match Hugging Face, and HTML entities need nothing (the oracle does not unescape them). Other scripts, longer mark sequences, weighting and chunking past 75 tokens remain open ([record](../bench/results/2026-11-07-clip-tokenizer-unicode/README.md)).

**Stage M, slice 20: the UNet graph at wider widths (2026-11-06).** The slice-17 gate at model_channels 128 (494 M parameters) and 192 (1.007 B parameters, heads up to 12) agrees with ComfyUI's `UNetModel` at all 21 stages (worst 9.7e-3, final 1.0e-3), 134 and 187 ms at 256 px ([record](../bench/results/2026-11-06-unet-sdxl-named-widths/README.md)). SDXL's real 320 (2.57 B) was not run: the 10 GB fixture needs ~20 GB of RAM while the Mac's swap was nearly full; the gate scripts now remove their large weight files on success, because earlier slices' leftover build directories had filled the disk.

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

## Remaining gaps, and why each is still open (2026-11-07, after slice 46)

Written so that "what is left" is a statement in the record, not an impression. Owner decisions need no further engineering until made; the rest are choices of effort, with the reason they were not taken.

| gap | kind | why it is open |
|---|---|---|
| a trained SDXL checkpoint, its publisher digest, a real render and its quality check (D1) | owner decision | which checkpoint, its licence and the download are the owner's; no SDXL-class file exists on this Mac; every correctness gate so far uses random weights against ComfyUI / Hugging Face code |
| where a real render runs (D4) | owner decision | the fleet nodes were offline during this work; which node to use is a choice |
| other image models (SD 1.x / 2.x, Qwen Image) and the samplers beyond Euler / dpmpp_2m (euler_ancestral, heun, dpmpp_sde, lms, uni_pc ...) | needs weights / effort | each needs its checkpoint (a D1-class download decision) and its own graph; none is in use |
| UNet per-call speed (4 to 35 percent behind PyTorch MPS, slice 46) | effort, uncertain return | at 768 px the profile (slice 23 tool) puts 52 percent of the GPU time in `linear_t` and 26 percent in `conv_k3s1`; tile shapes, unrolling, staging, transposed weights, prefetch and a resident-Q attention were each measured (slices 24 to 35) and the remaining ones were negative; caching the context's K / V across steps saves about 2 percent; the rest needs a different algorithm per layer, with no measured candidate |
| prompts of more than 4 blocks; `.pt` / `.bin` embeddings | not needed yet | the block count is capped at 4 (300 tokens; the repeat to a common length grows with the least common multiple of the two prompts' block counts); `embedding:` words work for `.safetensors` files (slice 48), the pickle formats are refused |
| scripts outside the supported set (Arabic, Indic, Thai, Korean jamo, ... ; Greek, Cyrillic, Armenian and Hebrew letters are done), mark sequences longer than one | effort, low demand | refused with a message, never tokenized differently; each needs its class and lower-casing table generated and checked against the oracle, the same way as slice 47 |
| the digest check hashes the file in a separate pass | accepted | the program hashes and reads through one descriptor and refuses a changed size, modification time or change time, so a swap or a write is caught; a writer that also controls the clock and the kernel is out of scope |
