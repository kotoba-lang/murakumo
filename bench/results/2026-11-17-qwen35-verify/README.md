# Qwen3.5-4B: the verify pass (2026-11-17)

Linear AWAI-3031 (under AWAI-2938). A DFlash cycle with blocks of 8 spends about 30 of its 39–40 ms in the verify pass:
an 8-token pass of the main model plus its head. This record measures that pass, tries six changes, and keeps one.
Raw data: [`raw/passes.txt`](raw/passes.txt).

## Where an 8-token pass goes

A prompt-pass probe gives warm 8-token passes of 25.8–26.6 ms. Dropping parts (stubs that give wrong tokens):

| Part dropped | Pass time |
| --- | ---: |
| matvecs (`mm8`) | 3.5–7.7 ms, so the matvecs are about 21 ms of it |
| Gated DeltaNet dispatch (`dnfn`) | 24.5–25.0 ms (about 1.4 ms) |
| the multiply inside `mm8` | 17.4–19.8 ms |
| the dequantization inside `mm8` | 17.5–17.9 ms |

Either half of `mm8` costs about 8 ms. The two run one after the other, not overlapped.

The matvecs read the 2.7 GB of weights at about 125 GB/s. One-token decode reaches about 190 GB/s.

For scale, llama.cpp b11669 on the same GGUF in the same hour:

| Batch | Tokens/s | One batch |
| --- | ---: | ---: |
| pp4 | 172.7 | about 23 ms |
| pp8 | 334.6 | about 23.9 ms |
| pp16 | 397.5 | about 40 ms |

Its 4-token batch costs about the same as its 8-token batch. murakumo's 8-token pass is within about 7 % of
llama.cpp's.

## Tried

| Change | 8-token pass | 16-token pass |
| --- | ---: | ---: |
| main (before) | 25.8–26.6 ms | 37.0–37.9 ms |
| k step of 128 (half the barriers, twice the tile memory) | 41.5–42.1 | 52.5–53.3 |
| activations stored k-major (no transposed loads) | 26.0–26.8 | 35.0–36.7 |
| double-buffered tiles (dequantize step s + 1 while multiplying step s) | 33.1–34.1 | 54.5–67.0 |
| per-lane matvecs (`mvq`/`mvn`) for 8 tokens | 348–356 (register spill) | — |
| per-simdgroup weight slices, activations read from device memory (no threadgroup barriers) | 25.5–25.9 | 37.3–37.6 |
| half activations (half × half multiply) | 26.0–27.0 | 34.4–35.6 |
| **kept: the results go through the weight tiles' memory** | **25.4–26.5** | **34.6–35.0** |

Every change that added threadgroup memory per threadgroup made the pass slower, so fewer threadgroups resident per
core is the likely cost. The kept change removes the separate result buffer: 2–4 KB less per threadgroup. It helps
the 16-token pass (the prompt) and leaves the 8-token pass where it was.

All the probes that ran the real model gave the same tokens.

## Result

- The 8-token verify pass is unchanged.
- It already sits at llama.cpp's level for this batch size on this Mac.
- A lighter verify pass needs a different matvec design for small batches, one that keeps the weights out of
  threadgroup memory without spilling registers. None of the variants above did that.
- The cheaper levers left in the cycle are elsewhere:
  - the drafter's head over the full 248,320-token vocabulary (about 3–4 ms per cycle);
  - drafts with low acceptance (tree verification).

## Correctness

The gate ([`gate.txt`](gate.txt)) passes every row.
