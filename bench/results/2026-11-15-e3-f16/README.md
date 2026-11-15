# E3, fourth unit: f16 copy of the GEMM weights, Qwen2.5-0.5B Q8_0, M1 Max (2026-11-15)

Follow-up to [the probes](../2026-11-15-e3-probe/README.md), Linear AWAI-2922. Same program and gate
(`bench/kernels/qwen2-metal/`), amu `f759f4f74`, `amu-program-metal`.

## pp512, same host, interleaved, five rounds

Load averages 53.8–70.7: a very busy host (other sessions). Round 3 was disturbed (llama.cpp ±794). Raw outputs are
in [`raw/`](raw/); the argv is as in [the third record](../2026-11-15-e3-split/README.md).

| Engine | Round 1 | Round 2 | Round 3 (disturbed) | Round 4 | Round 5 |
| --- | ---: | ---: | ---: | ---: | ---: |
| **murakumo** (3 runs per round) | **9087 / 9084 / 8995** | **9081 / 9031 / 8874** | 8469 / 8495 / 8586 | **8574 / 8540 / 8627** | **8593 / 8597 / 8649** |
| llama.cpp Metal | 9124.8 ±42.1 | 9052.3 ±51.2 | 7504.2 ±793.8 | 8537.1 ±74.1 | 8667.8 ±40.6 |
| MLX 8-bit (prompt_tps) | 7421.4 | 6825.3 | 5973.3 | 5938.0 | 6029.8 |

murakumo is now **level with llama.cpp within about ±1 %**. It is ahead in round 4 and up to 2 % behind in rounds 1,
2 and 5. Against MLX it is about 1.2–1.4x faster. The E3 target (≥ llama.cpp) is reached in some rounds and missed in
others, so on this host it is not shown as met. A quiet-host comparison is needed to settle it.

## What changed

- **The prefill GEMMs read an f16 copy of the Q8_0 weights.** At load, the `deq` kernel converts every layer's q, k,
  v, o, gate, up and down weights into a private 715,653,120-byte buffer (row-major, `half(d * q)`). That is the same
  value the GEMM computed when it dequantized in place, so **the logits are unchanged**: prefill-check gives the same
  0.0103216 as before. The GEMM now stages 16 halves per thread with four `half4` loads instead of dequantizing.
- The conversion costs 52–66 ms once per process, printed on stderr as `f16-weights-ns`. It is outside the prefill
  timing, the same way loading the model is.
- Decode is unchanged. It still runs the Q8_0 matvec on the file's weights.
- GPU memory grows by about 716 MB, on top of the 531 MB model file.

## Measured on the way (isolated stage ms; see the probe record for the method)

| Variant | gate\|up | down | Kept |
| --- | ---: | ---: | --- |
| as of #686 | 31.3–33.1 | 16.4–17.1 | |
| f16 weights read straight from device memory into simdgroup matrices (8 x 8 blocked layout, timing only) | 34.4–35.6 | 17.8–18.3 | no |
| f16 weights staged through threadgroup memory as before, no dequantization (timing only) | 29.7–30.8 | 15.6–16.0 | yes: implemented |
| whole prefill, f16 weights, against #686 interleaved | 58.4–59.7 vs 61.7–64.5 | | yes |

An f16 KV cache was not implemented. The 16-row attention probe showed attention is not bound by K/V reads, and
the change would round K and V, which the decode rows and the 129-token pinned continuation check.

## Correctness

All gate rows pass ([`gate.txt`](gate.txt)). They include prefill-check (512 tokens, argmax 198 both ways, max |logit
difference| 0.0103, unchanged), exact, long (129 tokens), every earlier mutant, and a new **deq-mutant**: the
load-time conversion with neighbouring values swapped changes the tokens (detected).
