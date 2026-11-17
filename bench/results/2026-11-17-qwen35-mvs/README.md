# Qwen3.5-4B: a small-batch matvec with the weights in registers (2026-11-17, not merged)

Linear AWAI-3055 (under AWAI-2938). Follow-up to [the verify-pass record](../2026-11-17-qwen35-verify/README.md).
Every variant tried there kept the weight tile in threadgroup memory, and the 8-token pass stayed at about 25.6 ms
(llama.cpp pp8 about 23.9 ms). This experiment is a matvec for 3–8 tokens that never puts weights in threadgroup
memory. It was slower, so it was not merged. The code is on branch `qwen35-mvs` (commit 83776331).

## The kernel (`mvs`)

- **Weights in registers.** For each 256-value step of a row, lane l dequantizes the 8 values 8l .. 8l + 7 of each
  of its simdgroup's rows into registers (`deq8`: Q8_0, Q4_K, Q5_K, Q6_K).
- **Activations staged once per step.** The threadgroup stages the 8 tokens' 256 activations of that step once
  (8 × 256 floats), and its 8 simdgroups share them.
- **Sums in registers.** Each lane keeps one sum per (row, token) and reduces it across the simdgroup at the end.
- **No weight tiles, no simdgroup matrices.** Rows that are too few to fill the GPU split along k, into the same
  partials `mmr` adds.

It is lossless: every variant gave the same tokens, and the 5-token gate prompt was exact.

## Result

A 64-token prompt in 8-token passes, warm passes, interleaved with main. Raw data: [`raw/passes.txt`](raw/passes.txt).

| Build | 8-token pass |
| --- | ---: |
| main (`mm8`, simdgroup matrices) | 25.4–25.9 ms |
| `mvs`, 4 rows per simdgroup | 65.7–66.2 ms |
| `mvs`, 2 rows per simdgroup | 44.4–46.0 ms |
| `mvs`, 2 rows, half activations in threadgroup memory | 40.6–40.9 ms |
| `mvs`, 2 rows, activations read from device memory (no barriers) | 52.2–53.7 ms |

Each weight value meets 8 tokens. A lane fetches those 8 activations from threadgroup memory or L1, and it can
reuse them only across its few rows. More rows per simdgroup run out of registers: 4 rows was the slowest variant.

Simdgroup matrices reuse each loaded operand across a whole 8 × 8 tile, so `mm8` keeps less data moving per
multiply. On this GPU, the per-lane design is held back by how fast it can read activations, not by its arithmetic.

## Where this leaves the verify pass

- `mm8` stays.
- The verify pass of a DFlash cycle remains about 25 ms for 8 tokens, near llama.cpp's.
- Beating it would need, for example, simdgroup matrices fed straight from dequantized registers, which MSL does
  not expose.
- The other route is fewer matvec bytes per pass, such as a lower-bit main model. That changes the model, not the
  kernel.
