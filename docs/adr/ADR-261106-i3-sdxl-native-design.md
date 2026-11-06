# ADR-261106: I3, a real SDXL render on Kotoba / Amu selfhost, designed

- Status: **design** (nothing in it is implemented). Linear JUN-927.
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
[2026-11-06-sdxl-budget](../bench/results/2026-11-06-sdxl-budget/README.md)):

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
| GPU: simdgroup *matrix* multiply (f16 MMA) | **absent from every plan** | this ADR, stage M |
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

## Decisions for the owner

- **D1 · which checkpoint.** A pinned SDXL file must exist locally for I3a, with revision and sha256, and a licence that
  allows the use. Candidates: SDXL base 1.0 (about 6.9 GB; CreativeML OpenRAIL++-M), or the fleet's own
  animagine-xl-4.0 / waiREALMIX (on offline nodes; their licences and provenance are not recorded in this repository). A
  download needs your explicit approval (file, source, size).
- **D2 · the compute tier.** Build the f16 matrix tier (M) on the Metal route, or accept CPU-only kernels as
  references and stop I3 at I3c's 64 x 64 correctness gate. Without M a render takes days; with M it is a new body of
  work on top of S7.
- **D3 · more than one file into a guest.** The checkpoint plus the CLIP vocabulary and merges are at least two inputs;
  S4 maps one. Options: extend `:model/read` to a small set of named, digest-pinned files, or embed the tokenizer tables
  in the program (the 1 MiB source limit forbids it today).
- **D4 · where I3f runs.** Every fleet node is offline (2026-11-06). Mac-only development works for I3a-I3e; I3f's
  16 GiB memory claim needs a Mac mini with that memory.

## Not claimed

No render, no checkpoint read and no Metal kernel for these ops exists. The MAC table counts Conv2d, Linear and attention
products only; the times are arithmetic on those counts and on rates measured for other operations, not forecasts.
The 72 s ComfyUI figure is a 2026-09 measurement on another model (waiREALMIX_v11) and assumes two UNet calls per step.
