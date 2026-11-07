# Roadmap: an inference-first Kotoba and Amu, on Apple silicon

The single plan is now [engine-plan.md](engine-plan.md) (2026-11-07, JUN-1010). This page keeps the S0–S8 detail.

Owner direction, 2026-11-03: plan on the premise that the Kotoba language spec
and the Amu compiler are themselves optimized for inference, with Kotoba and
Amu selfhost, built and run directly on this Mac's architecture
(`aarch64-macos`) by Amu selfhost. No Linux VM, no C loader, no JVM or nbb in
the path.

Status: plan. Nothing below is implemented by this document. The numbers in
"Where it stands" and "Measured since" are measured; every later number is a target.

## Where it stands (measured 2026-11-03, MacBook Pro M1 Max)

Record: [bench/results/2026-11-03-m1max](../bench/results/2026-11-03-m1max/README.md).
Qwen2.5-0.5B-Instruct, 512-token prompt, decode in tokens per second.

| Engine | Decode | What it means for this plan |
| --- | ---: | --- |
| MLX 8-bit (GPU) | 249.4 | GPU target |
| llama.cpp Metal, Q8_0 | 218.4 | GPU target; AWAI-18 claim gate G1 |
| llama.cpp CPU, 1 thread | 90.7 | single-thread target: ≈ 44.8 G weight-MACs/s, ≈ 48 GB/s of Q8_0 from one core |
| llama.cpp CPU, 8 threads | 49.3 | multi-thread target (low here: loaded host) |
| murakumo Kotoba decode guest (GLSL kernels), 2026-10-19 | 71.2 | the last murakumo token rate on this Mac; its C loader is gone |
| **Kotoba via `bin/amu` (Amu selfhost Mac CLI), one Q8_0 kernel** | **3.6** (kernel-only bound) | 1.76 G MACs/s: 25× below llama.cpp's one thread, 1.7× below scalar C |

The Kotoba kernel is correct (bit-exact with C, mutant caught) and its
scalar code is within 1.7× of `cc -O2`. The 25× gap is everything a scalar,
single-threaded, file-less language with slow float code cannot express.

Correction, same day: this document first listed surface floats as refused. They are not. The checker admits
36 named float operations (`f32-add`, `f32-mul`, `f32-lt`, `f32-sqrt`, `i64-to-f32-rounded`, … ; amu
`seed/21-check.kotoba` `ck-fl-code`, rung r6j). What it refuses is the generic `+`/`*` on floats (E2104) and the
spelling `f32+`, which does not exist (E2101): the probe used the wrong names. Gap 1 and S1 below are rewritten
accordingly.

## Measured since

S1 (merged 2026-11-04, kotoba-lang/amu#1813, amu ADR 0404, JUN-804): in an ordinary leaf function, f32 locals live in
s16..s31 and `f32-from-bits` is an inline range check, so a kernel that reads f32 values from a vector stays a leaf.
Results stay bit-exact with C twins built with `-ffp-contract=off`. Evidence: amu `docs/evidence/s1-fp-homes-20261103/`.
Diagnostic only (loaded host, CPU time, 2026-11-03).

- A 4,864 × 896 f32 matvec takes 19.9 ms with one accumulator (from 32.2) and 14.6 ms with four (from 26.1), against 6.2
  and 2.1 ms in C.
- S1's target of 2× of `cc -O2` is not met. ADR 0404 attributes the rest of the gap to vector access and the
  per-element range check, which S2 addresses; S2 has not been measured.

S7, first slice (2026-11-04, kotoba-lang/amu#1822, amu ADR 0405, JUN-819): a binary built by `bin/amu` runs the Q8_0
kernel on this Mac's GPU through Metal, with no C loader. Its answer equals the CPU and C twins at 4,864 × 896, and a
mutated kernel differs. Evidence: amu `docs/evidence/macos-metal-20261104/`. Diagnostic only (shared host, load average
19 to 24), and no model ran, so there is no token rate here.

- One 4,864 × 896 product takes about 300 µs, against 2.42 ms for the Kotoba scalar kernel and 1.40 ms for C.
- About 275 µs of that is the floor of one command buffer. Above the floor the plain loop runs at about 50 G MACs/s, a
  kernel-only bound of about 101 tok/s for Qwen2.5-0.5B. llama.cpp Metal's 218.4 tok/s is about 108 G MACs/s over the
  whole model.
- The floor can matter more than the slope: for illustration, 100 dispatches in separate command buffers would cap
  decode near 36 tok/s. A decode step has to batch its kernels, and that cost is not measured yet.

## What stops a Kotoba engine on this Mac

Each row was confirmed in Amu `1abb17f30` source or by running `bin/amu`; row 7 was updated for `15aef330f`, rows 1 and
2 for `9ca2f3421`.

| # | Gap | Where | Effect on inference |
| --- | --- | --- | --- |
| 1 | Floats are spelled as named operations (`f32-add`, `f32-mul`, …); the generic `+`/`*` refuse them (E2104). `f32-from-bits` no longer makes a call (S1), but its 32-bit range check still runs per element | amu `seed/21-check.kotoba` `ck-fl-code`, `ck-r6f-t-f32`; `seed/41-a64gen.kotoba` `gn-range-wrapper` | the Q8_0 kernel used fixed-point scales only because the probe used the wrong names |
| 2 | Only f32 locals in ordinary leaf functions live in FP registers (S1). Everywhere else, including f64 and any function that makes a real call, float code moves both operands through `d0`/`d1` with `fmov` for every operation | amu `seed/41-a64gen.kotoba` `fh-admit`, `gn-fl-bin` | outside those leaves every float op still costs three to five instructions, and a binding whose value comes from an `if` stays in a general register |
| 3 | No SIMD types or NEON lowering | spec and `41-a64gen` | M1 has FEAT_DotProd (`sdot`: 16 int8 MACs per instruction) and FEAT_FP16; the kernel unpacks each byte with two shifts |
| 4 | No byte arrays; weights live in `:vector-i64` words | spec | 8 bytes per word plus unpacking; no `ldrsb`/`ld1` |
| 5 | The Mac CLI host gives guests no filesystem, a 5-second `run` deadline and a 1 MiB source limit; the full-root route reads whole files only (wire 35, no range) | amu `examples/foundation-starter/MACOS-ARM64.md`, `docs/native-public-entry.md` | a 531 MB GGUF cannot reach a guest |
| 6 | One thread | Mac runtime | 1 of 8 performance cores |
| 7 | GPU on macOS reaches one kernel only. The default profile traps at wire 42 and the C loader is gone (amu #1459/#1460); the opt-in `amu-program-metal` profile reaches Metal through dyld and one `boot` function, and its kernel is MSL text written by hand | amu ADR 0375, ADR 0405 | one command buffer per dispatch and no model. MSL emission and simdgroup reduction exist (ADR 0373, AWAI-41) but are not connected to this route |
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
| S1 · scalar floats | `41-a64gen` keeps f32 locals in FP registers (FP homes, s16..s31) and writes f32 results into them in place; `f32-from-bits` becomes an inline range check. `:f16` storage and a fused `f32-fma` stay language decisions. **Merged 2026-11-04:** kotoba-lang/amu#1813 (ADR 0404), with Mac core adoption and native ARM/x86 CI passing. Diagnostic: 4864 × 896 f32 matvec 32.2 → 19.9 ms (one accumulator) and 26.1 → 14.6 ms (four), C 6.2 / 2.1 ms | f32 matvec (1 and 4 accumulators), RMSNorm and an FP-home path corpus against C twins (`-ffp-contract=off`), bit-exact; mutants; `f32-from-bits` traps out of range; integer programs keep identical bytes | f32 matvec within 2× of `cc -O2`: not met; the rest of the gap is vector access and the per-element range check (S2) |
| S2 · bytes | `:bytes` and `:vector-i8`/`:vector-u8` views with typed loads (`ldrsb`, `ldr q`); bounds checks hoisted out of counted loops whose range the checker proves | Q8_0 kernel checksum unchanged | ≤ 1.45 ms (scalar C) |
| S3 · SIMD | fixed-width lane types (`:i8x16`, `:i32x4`, `:f32x4`, `:f16x8`) and forms that lower one-to-one: `dot-i8x16` → `sdot`, `fma-f32x4` → `fmla`, horizontal add → `addv` | Q8_0 and f32 kernels exact against scalar Kotoba and C | ≥ 40 G MACs/s on one core (llama.cpp's one thread: 44.8) |
| S4 · model access | a `:model/read` capability naming one file and its sha256, granted per run; the Mac runtime maps it read-only through libSystem (`mmap`) and exposes it as `:bytes`; granted engines leave the 5-second `run` deadline | GGUF header and tensor-table admission on the real file (port of the AIUEOS `qwen35-gguf-*` objects to `qwen2`); a wrong digest is refused | 531 MB mapped, zero copies |
| S5 · first token | Qwen2.5-0.5B forward pass in Kotoba on one core: embedding, RMSNorm, RoPE, GQA attention with a KV cache, SwiGLU, tied output head, argmax | prompt ids `760 6511 314 9338 369` → greedy `314 746 600 220`, the f64 oracle and llama.cpp continuation recorded on 2026-10-19; a mutant changes a token | ≥ 1-thread llama.cpp, 90.7 tok/s |
| S6 · threads | a structured `parallel-rows` form: rows partitioned across performance cores, no shared mutable state, a reduction order fixed by the spec | identical tokens on 1 and 8 threads | ≥ 8-thread llama.cpp on an idle host |
| S7 · Metal | ADR 0375 route B on macOS, through dyld (amu ADR 0405, owner decision 2026-11-04, in place of a Kotoba Mach-O loader). **First slice merged:** one hand-written MSL kernel, exact against its C twin. Still to do: dispatch Amu's KIR v2 → MSL kernels, and batch a decode step's kernels into few command buffers | kernels vs the f64 oracle; same tokens as S5 | ≥ llama.cpp Metal 218 and MLX 249 tok/s decode (AWAI-18 G1) |
| S8 · murakumo serves it | `murakumo node join` with the Kotoba engine on a Mac: `/v1/chat/completions` from the S5–S7 binary, no external model server | the node's answer equals S5's tokens | JUN-740 on Apple silicon |

S1–S3 are compiler and spec work with no model; S4–S5 need the host
capability; S6–S7 need a new runtime surface in the Mac host. S1 is merged;
S2 and S3 can run in parallel. The Linux route shares the spec and the checker,
but its plain emitter (amu `seed/80`) needs its own lowering of each stage;
kotoba-lang/amu#1667 (inline vector access) removes gap 8 first.

## Not in the stages yet

The engines in [benchmarks.md](benchmarks.md#other-engines-as-published), read on 2026-11-06, use techniques that S0–S8
do not plan. None of these has a stage, a gate or a target; whether to add them is an owner decision.

| Technique | Published by | What it would take here |
| --- | --- | --- |
| Speculative decoding: a drafter proposes tokens and one pass of the large model checks them | Kapitsa (KFlash, a tree of drafts), TensorFold (MTP heads, DFlash2) | a drafter on top of S5, and a gate that the output equals plain decoding, the rule both projects state; S5's greedy continuation gives that gate a reference |
| Several requests in shared rounds | TensorFold (`--parallel`), vLLM, SGLang | a scheduler above S5 for S8 to serve through; S6 covers threads inside one request only |
| Prefix cache | TensorFold (`--prompt-cache-gib`), vLLM, SGLang (RadixAttention) | reusing S5's KV cache across requests that share a prompt prefix |

Two more bear on existing stages, as places to look rather than plans:

- S7's batching: Flare captures CUDA graphs before serving starts. Capturing a decode step's dispatches ahead of time
  might lower the command-buffer cost in "Measured since"; nobody here has measured that on Metal.
- S4's model access: Strata and Kapitsa stream MoE experts from the SSD on Macs with too little memory to hold them. S4
  as written maps one file and does not address this.

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
| GPU | dyld, Metal dispatch of KIR v2 → MSL | amu ADR 0375 route B, ADR 0405 |

## Related work

- AWAI-18: brand and claim gate. AWAI-12: same-host comparison.
- JUN-701 / JUN-740: Amu-native engine through route C on the Xavier, and
  murakumo serving it.
- JUN-377 (ADR 0375): GPU through Amu selfhost; S7 is its macOS route B.
- JUN-804: this roadmap, and S1 (ADR 0404).
- JUN-819 (ADR 0405): S7's first slice.
- KOTOBA-168 (kotoba-lang/amu#1667): inline vector access for the plain
  emitter, which removes gap 8.
- KOTOBA-161: f64 admission on static targets, the Linux side of S1.
