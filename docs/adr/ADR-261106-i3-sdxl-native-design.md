# ADR-261106: I3, a real SDXL render on Kotoba / Amu selfhost, designed

- Status: **design**, with one decision made and one slice built since it was merged (amended 2026-11-06): D2 is decided
  and stage M's first slice (an f16 matrix-unit GEMM) is implemented. Nothing else in it is implemented. Linear JUN-927.
- Date: 2026-11-06
- Owner direction: 2026-11-06, "I3 の設計を実施"; image first, native on the fleet's nodes; comfyui-clj stays an
  oracle, and ComfyUI's Python may be run as a one-off oracle (see [media-generation-roadmap.md](../media-generation-roadmap.md)).
- Relates to: roadmap stages I3 and I4; [inference-roadmap.md](../inference-roadmap.md) S2, S3, S4, S6, S7; JUN-804.

## What I3 is

The roadmap's I3 row: a real SDXL checkpoint, pinned by revision and sha256, rendered by a Kotoba program built by Amu
selfhost: both CLIP text encoders, the UNet, a sampler, a tiled VAE decode, at 768 x 768. This document turns that row
into sub-stages with gates, says what each needs, and records one finding that changes the plan: **the compute does
not fit any CPU tier the inference roadmap plans.**

## The finding: the arithmetic

Measured 2026-11-06 on ComfyUI v0.44.0's SDXL UNet, built on the meta device and run with attention replaced by a
shape-recording stub (`bench/oracle/comfyui/sdxl_budget.py`, record
[2026-11-06-sdxl-budget](../../bench/results/2026-11-06-sdxl-budget/README.md)):

| Latent (image) | Conv | Linear | Attention | One UNet call |
| --- | ---: | ---: | ---: | ---: |
| 64 x 64 (512 px) | 203 G | 564 G | 28 G | 794 GMAC |
| **96 x 96 (768 px)** | 457 G | 1,236 G | 128 G | **1,821 GMAC** |
| 128 x 128 (1024 px) | 812 G | 2,177 G | 392 G | 3,381 GMAC |

The UNet has 2,567,463,684 parameters (5.13 GB in f16). Not counted: GroupNorm, SiLU, softmax, LayerNorm, the VAE and the
text encoders (the VAE decode count failed on ComfyUI's attention memory probe and was not retried; the VAE has
83,653,863 parameters, and CLIP-L and CLIP-G text towers come to about 123 M and 695 M from their configs).

A 25-step render with classifier-free guidance is 25 x 2 = 50 UNet calls: **91 TMAC** at 768 x 768 (assuming CFG; a
render without it is half).

| Rate | Source | Time for 91 TMAC |
| --- | --- | ---: |
| 0.12 GMAC/s | Kotoba today: conv2d 3 x 3 and matmul kernels, one thread, busy host (2026-11-06) | **about 8.6 days** |
| 40 GMAC/s | S3's target, one core | 38 min |
| 50 GMAC/s | S7's first slice, plain Metal loop (the Q8 kernel, not this op mix) | 30 min |
| 108 GMAC/s | llama.cpp Metal on a whole Q8 model (a different op mix) | 14 min |
| about 1,260 GMAC/s | ComfyUI, M4 16 GiB, 768 x 768, 25 steps, warm, 72 s (`image_job.cljk`), if it ran CFG | 72 s |

Consequences:

1. The Kotoba CPU kernels of I1/I2 are correctness references, not a render route. Even S3's target leaves a render at
   about 40 minutes per image.
2. Matching ComfyUI's 72 s needs about 1.3 TMAC/s, which on Apple silicon is matrix-unit territory (simdgroup matrix
   multiply in f16). The inference roadmap plans simdgroup *reduction* (ADR 0373) and plain loops (S7), not matrix
   multiply. **I3 therefore needs a stage the inference roadmap does not have** (M below).
3. Weights in f16 total about 6.94 GB (UNet 5.13, CLIP-L 0.25, CLIP-G 1.39, VAE 0.17). That fits a 16 GiB node; ComfyUI
   was measured stable at 768 x 768 there and swapping at 1024 x 1024 (`image_job.cljk`, 2026-09), so 768 stays the target.

## What I3 needs, and where each need stands

State read 2026-11-06 from amu `origin/main` (`f6c6ea215`) and JUN-804.

| Need | Status | Where |
| --- | --- | --- |
| f32 arithmetic, FP register homes | merged (S1) | amu ADR 0404 |
| f16 *storage* (checkpoints are f16) | **absent**: no half-float type in `seed/20-names`, `21-check` or `30-lower` (grep: none; the `f16` hits in `seed/41-a64gen` are 16-bit integer pattern helpers); S1 left it a language decision | JUN-804 S1/S2 |
| Byte arrays / typed loads (`:bytes`) | not started (S2) | JUN-804 |
| Read a multi-GB file in a guest (`:model/read`, read-only map) | not started (S4); the Mac CLI gives guests no filesystem and a 5 s `run` | JUN-804, `native-public-entry.md` |
| More than one input file (checkpoint + CLIP `vocab`/`merges`) | **open**: S4 as written maps one file | this ADR, decision D3 |
| SIMD lanes (`sdot`, `fmla`) | not started (S3) | JUN-804 |
| Threads | not started (S6) | JUN-804 |
| GPU: one hand-written MSL kernel through dyld | merged (S7 slice 1, ADR 0405) | amu#1822 |
| GPU: Amu KIR v2 -> MSL, batching a step's dispatches | not started (rest of S7) | JUN-804 |
| GPU: simdgroup *matrix* multiply (f16 MMA) | **GEMM, the stride-1 3 x 3 convolution (implicit GEMM) and attention, merged** (stage M slices 1 to 3: bit-exact against integer twins, 3.1 to 3.6 TMAC/s for GEMM and 1.1 to 1.9 TMAC/s for convolution on an M1 Max under load). Fused attention (slice 3: within 3.1e-4 of a double reference, 0.4 to 1.4 TMAC/s) is merged too. Convolution epilogues (bias, residual, SiLU) and the stride-2 / 1 x 1 / 4-channel cases (slice 4, exact, 0.66 to 0.85 TMAC/s) are merged too. Norms and activations on the GPU, per-UNet-call batching and KIR v2 emission: not started | this ADR, stage M; `bench/kernels/gemm-metal/`, `bench/kernels/conv-metal/` |
| Kernels | conv, GroupNorm, LayerNorm, attention, GEGLU, upsample, timestep embedding, matmul, ResBlock, SpatialTransformer: done on CPU (I1/I2) | `bench/kernels/` |
| CLIP BPE tokenizer | not written in Kotoba (comfyui-clj has one in `.cljk`, as an oracle) | I3b |
| Label-embedding MLP, input/output block wiring with skip concat, middle block, sampler, tiled VAE, PNG writer | not written | I3c-I3e |
| A pinned SDXL checkpoint on this Mac | **none** (fleet checkpoints live on offline nodes) | decision D1 |

## Sub-stages and gates

Each gate keeps the project's form: an exact or tolerance-bounded comparison against a reference, plus a mutant that must
fail, plus a recorded number. References are one-off local runs of ComfyUI's Python (principle 2 of the roadmap).

| Stage | What | Gate | Needs |
| --- | --- | --- | --- |
| I3a · admit | Parse a safetensors header, catalog tensors (name, dtype, shape, offset), detect SDXL base from tensor names (as comfyui-clj `diffusion/architecture` does), verify the file's sha256 before reading | catalog equals Python `safetensors` metadata on the real file; a one-byte-changed copy is refused | S4, D1 |
| I3b · text | CLIP BPE tokenizer; CLIP-L and CLIP-G towers (causal mask, quick-GELU / GELU, pooled output); the 2816-wide `adm` vector from pooled CLIP-G plus size/crop ids | token ids equal ComfyUI's for a prompt set; context (77 x 2048) and `y` (2816) within a stated tolerance | I3a, D3, f16 |
| I3c · UNet | label and timestep MLPs, 9 input blocks, middle, 9 output blocks with skip concat, transformer depths 0/2/10 and 10 in the middle, final norm and conv | one UNet call on a 64 x 64 latent (512 px) vs ComfyUI fp32 CPU: max error stated and the dtype gap measured; a dropped skip is caught | I3a, I3b, kernels at scale |
| I3d · sampler | euler (then karras sigmas), CFG, seeded noise as an input vector | latent after N steps vs ComfyUI; same seed gives the same latent run to run | I3c |
| I3e · decode | tiled VAE decode (8 x upsample, 4 -> 3 channels), PNG out | image vs ComfyUI's: per-pixel bound set from ComfyUI's own fp16-vs-fp32 deviation | I3d |
| I3f · record | one end-to-end 768 x 768 render; wall time, peak memory, per-stage time | recorded next to ComfyUI's 72 s | all, plus the compute tier |
| **M · matrix tier** | f16 simdgroup-matrix GEMM and implicit-GEMM conv in MSL emitted from KIR v2; command-buffer batching per UNet call | conv and matmul kernels vs the CPU Kotoba kernels within f16 tolerance; GMAC/s recorded | S7 rest, D2 |

Order: I3a and I3b need only S4 and f16, so they can start before the compute tier; I3c can be developed at 64 x 64 on
the CPU reference path for correctness (hours per call), and M decides whether I3f is minutes or days. Nothing here is
scheduled; the stages are independent enough that M and I3a-I3b can proceed in parallel.

## Numerics

- Checkpoints are f16. The plan keeps f16 as storage and does arithmetic in f32 on the CPU tier, f16 inputs with f32
  accumulation on the matrix tier. Both are choices to be written into the language spec (JUN-804's "spec before
  intrinsics"), not code-generation accidents.
- The reference for numerics is ComfyUI in fp32 on CPU (slow but exact enough). ComfyUI's own fp16 (MPS) output differs
  from its fp32 output; I3e's image tolerance is set from that measured difference, not guessed.
- The I2 gate numbers (7.6e-6 and 3.6e-6 at C = 32-64) do not transfer to depth-10 transformers at 1,280 channels; I3c
  measures the real figure.

## Decisions

- **D2 · the compute tier: decided 2026-11-06 (owner): build the matrix tier (M).** Slice 1 is merged
  (`bench/kernels/gemm-metal/`, [record](../../bench/results/2026-11-06-gemm-metal/README.md)): an f16 `simdgroup_matrix` GEMM reached
  from Amu selfhost through dyld, checksum equal to an integer C twin's on four shapes up to 4096 x 1024 x 4096, a wrong B
  stride detected, and best-of-20 rates of 3.1 to 3.6 TMAC/s on an M1 Max at load average 17 to 20 (command-buffer floor
  included). That is 2.4 to 2.8 times the 1.3 TMAC/s derived above from ComfyUI's 72 s, so the matrix tier looks
  sufficient *for the GEMM share*. Not yet measured, and what decides whether I3f takes minutes or an hour: convolution as
  implicit GEMM, attention (a softmax between two GEMMs), norms and activations, and the roughly 275 us command-buffer
  floor of each unbatched dispatch. The slice uses integer-valued f16 data, so f16 rounding on model data, f16 storage as
  a language type, and the 16 GiB M4 minis are untouched. Slice 2 is merged too (amended 2026-11-06): the 3 x 3 convolution as an implicit GEMM, bit-exact on five shapes, 1.1 to 1.9 TMAC/s
  ([record](../../bench/results/2026-11-06-conv-metal/README.md)); only the stride-1, multiple-of-32/64-channel case. Slice 3 is merged too (amended 2026-11-06): fused attention, within 3.1e-4 of a
  double-precision reference on five shapes, 0.4 to 1.4 TMAC/s ([record](../../bench/results/2026-11-06-attention-metal/README.md)); approximate by
  construction, head dim 64 only. Slice 4 is merged too (amended 2026-11-06): convolution with bias / residual / SiLU epilogues and the 1 x 1, stride-2 and 4-channel cases, exact against an integer reference, 0.66 to 0.85 TMAC/s ([record](../../bench/results/2026-11-06-conv-epilogue-metal/README.md)); slower than slice 2's plain kernel, not tuned. Next in M: norms and activations on the GPU, retuning the convolution, batching a whole UNet
  call, emission from KIR v2.

### Still open for the owner

- **D1 · which checkpoint.** A pinned SDXL file must exist locally for I3a, with revision and sha256, and a licence that
  allows the use. Candidates: SDXL base 1.0 (about 6.9 GB; CreativeML OpenRAIL++-M), or the fleet's own
  animagine-xl-4.0 / waiREALMIX (on offline nodes; their licences and provenance are not recorded in this repository). A
  download needs your explicit approval (file, source, size).
- **D3 · more than one file into a guest.** The checkpoint plus the CLIP vocabulary and merges are at least two inputs;
  S4 maps one. Options: extend `:model/read` to a small set of named, digest-pinned files, or embed the tokenizer tables
  in the program (the 1 MiB source limit forbids it today).
- **D4 · where I3f runs.** Every fleet node is offline (2026-11-06). Mac-only development works for I3a-I3e; I3f's
  16 GiB memory claim needs a Mac mini with that memory.

## Not claimed

No render and no checkpoint read exists, and the only Metal kernel is the GEMM of stage M slice 1 (no convolution,
attention, norm or activation on the GPU yet). The MAC table counts Conv2d, Linear and attention
products only; the times are arithmetic on those counts and on rates measured for other operations, not forecasts.
The 72 s ComfyUI figure is a 2026-09 measurement on another model (waiREALMIX_v11) and assumes two UNet calls per step.
