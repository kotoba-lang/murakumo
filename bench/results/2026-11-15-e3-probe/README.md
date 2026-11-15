# E3, probes after the third unit: where the remaining prefill time is bound (2026-11-15)

Follow-up to [the third E3 record](../2026-11-15-e3-split/README.md), Linear AWAI-2922. No engine change was kept.
Program as merged in #686, amu `f759f4f74`, `amu-program-metal`, M1 Max, load average about 25 (a busy host;
differences under about 1 ms are noise). The ms below are one isolated stage (all other per-layer stages stubbed,
the stubbed run itself about 2 ms), five warm pp512 runs each.

## GEMM bounds

| Variant | gate\|up ms | down ms | Reading |
| --- | ---: | ---: | --- |
| as merged | 30.9–31.7 | 16.0–16.9 | |
| no device reads (constants in place of activations and weights) | 28.9–29.4 | 15.1–15.6 | device reads and dequantization cost about 2 ms and 1 ms |
| compute only (no reads, no threadgroup stores, no barriers in the k loop) | 26.1–26.3 | 13.4–13.9 | the fragment-load + multiply-accumulate loop alone runs at about 8.8 TFLOPS (gate\|up, 214 GFLOP) |

So of about 29 ms net for gate|up, about 24 ms is the inner loop at its current shape (8 fragment loads per 16
multiply-accumulates per simdgroup) and about 5 ms is staging (reads, dequantization, threadgroup stores, two barriers
per Q8_0 block).

| Variant tried | gate\|up ms | Kept |
| --- | ---: | --- |
| two Q8_0 blocks per barrier pair (24 KB threadgroup memory) | 33.4–34.0 | no: occupancy |
| 128 x 128 tile, 32 x 64 per simdgroup (8 + 8 fragment loads per 32 multiply-accumulates) | 32.5–33.3 | no |
| threadgroup order with the m tile varying fastest (weight tile shared by consecutive threadgroups) | 30.7–31.3 vs 30.7–32.2 | within noise |

## Attention

| Variant tried | attention-only ms | Kept |
| --- | ---: | --- |
| as merged (8 query rows per threadgroup) | 6.6–7.7 | |
| 16 query rows per threadgroup (K and V fragments shared by two row tiles) | 6.7–7.6 | no change: not bound by K/V reads |
| `fast::exp` in place of `precise::exp` (timing only) | 6.2–9.6 | no measurable change |

## Reading

With hand-written MSL on this M1 Max, the GEMM is close to its inner-loop bound and attention is not load bound.
What remains to beat llama.cpp (about 58.6 ms against our about 62 ms) would need a different loop structure, or
reduced-precision storage. f16 KV cache and f16 dequantized weights are both untried. Either would change the
numerics checked by the exact and long gate rows, so it is an owner-visible decision. The same comparison on an idle
host is also still open.
