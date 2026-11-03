# Roadmap: an inference-first Kotoba and Amu, on Apple silicon

Owner direction, 2026-11-03: plan on the premise that the Kotoba language spec
and the Amu compiler are themselves optimized for inference, with Kotoba and
Amu selfhost, built and run directly on this Mac's architecture
(`aarch64-macos`) by Amu selfhost. No Linux VM, no C loader, no JVM or nbb in
the path.

Status: plan. Nothing below is implemented by this document. The numbers in
"Where it stands" are measured; every later number is a target.

## Where it stands (measured 2026-11-03, MacBook Pro M1 Max)

Record: [bench/results/2026-11-03-m1max](../bench/results/2026-11-03-m1max/README.md).
Qwen2.5-0.5B-Instruct, 512-token prompt, decode in tokens per second.

| Engine | Decode | What it means for this plan |
| --- | ---: | --- |
| MLX 8-bit (GPU) | 249.4 | GPU target |
| llama.cpp Metal, Q8_0 | 218.4 | GPU target; AWAI-18 claim gate G1 |
| llama.cpp CPU, 1 thread | 90.7 | single-thread target: ≈ 44.8 G weight-MACs/s, ≈ 48 GB/s of Q8_0 from one core |
| llama.cpp CPU, 8 threads | 49.3 | multi-thread target (low here: loaded host) |
| murakumo Kotoba GPU kernels, 2026-10-19 | 71.2 | the last Kotoba number on this Mac; its C loader is gone |
| **Kotoba via `bin/amu` (Amu selfhost Mac CLI), one Q8_0 kernel** | **3.6** (kernel-only bound) | 1.76 G MACs/s: 25× below llama.cpp's one thread, 1.7× below scalar C |

The Kotoba kernel is correct (bit-exact with C, mutant caught) and its
scalar code is within 1.7× of `cc -O2`. The 25× gap is everything a scalar,
single-threaded, file-less, float-less language cannot express.

## What stops a Kotoba engine on this Mac

Each row was confirmed in Amu `1abb17f30` source or by running `bin/amu`.

| # | Gap | Where | Effect on inference |
| --- | --- | --- | --- |
| 1 | Surface floats are refused. `:f64` is an opaque carrier; `+` on floats is E2104, `f32+` is E2101 | amu `seed/21-check.kotoba:104`, probes in this run | no norms, softmax, RoPE or scales in user code; the kernel had to use fixed-point scales |
| 2 | Float code moves both operands through `d0`/`d1` with `fmov` for every operation; no value lives in an FP register | amu `seed/41-a64gen.kotoba:2073-2076` | even once admitted, every float op costs three to five instructions |
| 3 | No SIMD types or NEON lowering | spec and `41-a64gen` | M1 has FEAT_DotProd (`sdot`: 16 int8 MACs per instruction) and FEAT_FP16; the kernel unpacks each byte with two shifts |
| 4 | No byte arrays; weights live in `:vector-i64` words | spec | 8 bytes per word plus unpacking; no `ldrsb`/`ld1` |
| 5 | The Mac CLI host gives guests no filesystem, a 5-second `run` deadline and a 1 MiB source limit; the full-root route reads whole files only (wire 35, no range) | amu `examples/foundation-starter/MACOS-ARM64.md`, `docs/native-public-entry.md` | a 531 MB GGUF cannot reach a guest |
| 6 | One thread | Mac runtime | 1 of 8 performance cores |
| 7 | No GPU route on macOS: wire 42 refused (E2115), C loader removed (amu #1459/#1460). ADR 0375 item 5: on macOS, route B is a Kotoba Mach-O loader for libSystem and Metal.framework, kernels in MSL | amu ADR 0374/0375 | MSL emission and simdgroup reduction exist (ADR 0373, AWAI-41) with nothing to launch them |
| 8 | Static Linux images: 64 MiB address space, no `mmap` after start (ADR 0371); vector access through helper calls (≈ 60 ns each, this run) | amu ADR 0371, kotoba-lang/amu#1667 | not the Mac route; listed so Linux nodes follow |

## Principles

1. **Mac native first.** Every stage lands in `bin/amu` (the signed Mach-O
   that Amu selfhost builds) and is measured on this Mac with this
   repository's harness. Linux static images follow the same spec later.
2. **Spec before intrinsics.** A new capability is a type or form in the
   language (kotoba-lang/kotoba-lang `lang/`), checked and lowered by the
   adopted core (amu `seed/20-names`, `21-check`, `30-lower`, `41-a64gen`),
   not a one-off host call. Lane semantics (wrap, saturate, rounding, NaN) are
   written down so CPU, Metal and WebGPU produce the same answer.
3. **Exact gates.** Each stage keeps a C or f64 twin, an exact or
   tolerance-bounded comparison and a mutant that must fail, the way
   `bench/kernels/q8_0-matvec/run.py` does.
4. **A number per stage**, recorded under `bench/results/`, compared with the
   same-day llama.cpp and MLX columns.
5. **No CI changes** while CDCI is stopped; no C, JVM or nbb on the path.
6. Public wording follows the AWAI-18 claim gate.

## Stages

| Stage | Spec / compiler change | Gate | Target on this Mac |
| --- | --- | --- | --- |
| S0 · baseline | — (done: harness and this record) | Q8_0 kernel = C, mutant differs | 2.47 ms per 4,864 × 896 product |
| S1 · scalar floats | `:f32` values, literals, arithmetic, compare and conversion admitted by the checker; `:f16` as a storage type. `41-a64gen` keeps floats in `s`/`d` registers across an expression and emits `fmadd` only where the spec allows contraction | f32 dot and RMSNorm twins vs C (`-ffp-contract=off`), bit-exact; mutant | f32 matvec within 2× of `cc -O2` |
| S2 · bytes | `:bytes` and `:vector-i8`/`:vector-u8` views with typed loads (`ldrsb`, `ldr q`); bounds checks hoisted out of counted loops whose range the checker proves | Q8_0 kernel checksum unchanged | ≤ 1.45 ms (scalar C) |
| S3 · SIMD | fixed-width lane types (`:i8x16`, `:i32x4`, `:f32x4`, `:f16x8`) and forms that lower one-to-one: `dot-i8x16` → `sdot`, `fma-f32x4` → `fmla`, horizontal add → `addv` | Q8_0 and f32 kernels exact against scalar Kotoba and C | ≥ 40 G MACs/s on one core (llama.cpp's one thread: 44.8) |
| S4 · model access | a `:model/read` capability naming one file and its sha256, granted per run; the Mac runtime maps it read-only through libSystem (`mmap`) and exposes it as `:bytes`; granted engines leave the 5-second `run` deadline | GGUF header and tensor-table admission on the real file (port of the AIUEOS `qwen35-gguf-*` objects to `qwen2`); a wrong digest is refused | 531 MB mapped, zero copies |
| S5 · first token | Qwen2.5-0.5B forward pass in Kotoba on one core: embedding, RMSNorm, RoPE, GQA attention with a KV cache, SwiGLU, tied output head, argmax | prompt ids `760 6511 314 9338 369` → greedy `314 746 600 220`, the f64 oracle and llama.cpp continuation recorded on 2026-10-19; a mutant changes a token | ≥ 1-thread llama.cpp, 90.7 tok/s |
| S6 · threads | a structured `parallel-rows` form: rows partitioned across performance cores, no shared mutable state, a reduction order fixed by the spec | identical tokens on 1 and 8 threads | ≥ 8-thread llama.cpp on an idle host |
| S7 · Metal | ADR 0375 route B on macOS: a Kotoba Mach-O loader that binds libSystem and Metal.framework and dispatches Amu's KIR v2 → MSL kernels | kernels vs the f64 oracle; same tokens as S5 | ≥ llama.cpp Metal 218 and MLX 249 tok/s decode (AWAI-18 G1) |
| S8 · murakumo serves it | `murakumo node join` with the Kotoba engine on a Mac: `/v1/chat/completions` from the S5–S7 binary, no external model server | the node's answer equals S5's tokens | JUN-740 on Apple silicon |

S1–S3 are compiler and spec work with no model; S4–S5 need the host
capability; S6–S7 need a new runtime surface in the Mac host. S2 and S3 can
start in parallel with S1. The Linux route shares the spec and the checker,
but its plain emitter (amu `seed/80`) needs its own lowering of each stage;
kotoba-lang/amu#1667 (inline vector access) removes gap 8 first.

## Spec deltas, in one place

| Area | Change | Lives in |
| --- | --- | --- |
| Scalar types | `:f32` arithmetic; `:f16` storage; explicit contraction rule | kotoba-lang/kotoba-lang `lang/`; amu `seed/21-check`, `30-lower`, `41-a64gen` |
| Buffers | `:bytes`, `:vector-i8`, `:vector-u8`, typed element loads | same |
| Lanes | `:i8x16 :i32x4 :f32x4 :f16x8`, `dot`, `fma`, horizontal reductions, exact lane semantics | same |
| Loops | bounds proofs for counted loops, so one check per row instead of per element | amu `seed/21-check`, `41-a64gen` |
| Capabilities | `:model/read` (file + digest, read-only map) and a deadline exemption for granted engines | kotoba-lang/kotoba-lang `lang/capability-catalog.edn`; amu `seed/macos-cli.kotoba` and the Mac runtime |
| Parallelism | `parallel-rows` with a fixed reduction order | language spec; Mac runtime |
| Limits | source and vector-item limits sized for model graphs | `lang/limits.edn`; Mac CLI |
| GPU | Mach-O loader, Metal dispatch of KIR v2 → MSL | amu ADR 0375 route B |

## Related work

- AWAI-18: brand and claim gate. AWAI-12: same-host comparison.
- JUN-701 / JUN-740: Amu-native engine through route C on the Xavier, and
  murakumo serving it.
- JUN-377 (ADR 0375): GPU through Amu selfhost; S7 is its macOS route B.
- KOTOBA-168 (kotoba-lang/amu#1667): inline vector access for the plain
  emitter, which removes gap 8.
- KOTOBA-161: f64 admission on static targets, the Linux side of S1.
