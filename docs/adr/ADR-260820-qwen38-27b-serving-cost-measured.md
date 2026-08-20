# ADR-260820: Qwen3.8-27B — measured serving cost on Modal, and what it does to the RunPod / own-hardware comparison

- Status: accepted
- Date: 2026-08-20
- Depends: RULES.md rule 8 (caching weights does not prove execution)

## Context

Qwen3.8-27B was released 2026-08-14 (Apache 2.0). The question put to the fleet
was: what does it cost per month, and what does it do, on **RunPod**, on
**Modal**, and on **our own hardware**.

A first pass answered that from published vendor rates plus other people's
llama.cpp benchmarks (RTX 3090/4090/5090, Apple M5 Max, GB10), and filled every
datacentre-GPU cell by scaling memory bandwidth. That pass produced a
recommendation table. **Every datacentre number in it was a guess wearing the
same typeface as the measured ones**, which is exactly the failure mode
RULES.md rule 8 names for weights-vs-execution. So we ran it.

`tools/modal-bench/qwen38_27b_bench.py` serves `Qwen/Qwen3.8-27B-FP8` under
vLLM 0.27.1 and measures decode throughput at concurrency 1/8/32/64 and at 4k
and 32k prompts. Raw output is checked in under `docs/adr/data/`. Total spend
for the whole verification, failed attempts included, was about **$3.6** —
computed as container wall-clock x published rate, not read off an invoice,
and inside Modal's $30/month Starter credit either way.

## What the model is

27.78B parameters, **dense** — not MoE. 64 layers, hidden 5,120, vocab 248,320.
Hybrid attention: Gated DeltaNet linear-attention blocks interleaved with full
gated-attention blocks. 262,144 native context. Text + image + video. Built-in
MTP draft head. BF16 ≈ 56 GB, FP8 ≈ 28 GB, Q4 ≈ 16.7 GB on disk.

## Measured

Modal, vLLM 0.27.1, `Qwen/Qwen3.8-27B-FP8`, `--kv-cache-dtype fp8`,
`max_model_len=65536`, `max_num_seqs=128`, greedy, `min_tokens == max_tokens`
so no early stop skews the rate. 2026-08-20.

| | L40S 48GB | H100 SXM 80GB |
|---|---|---|
| VRAM visible | 44.39 GiB | 79.18 GiB |
| single stream, 256 in / 512 out | **18.8 tok/s** | **78.2 tok/s** |
| concurrency 8 | 155.2 | 259.5 |
| concurrency 32 | 477.3 | 1,727.5 |
| concurrency 64 | **476.2** (saturated) | **2,705.1** |
| 4k prompt, decode | 17.4 | 72.2 |
| 32k prompt, decode | 10.8 | 42.2 |
| engine init, caches cold | 579 s | 534 s |
| engine init, caches warm | — | **343 s** |

H100 was run twice. Reproducibility is within 2% (78.2 / 76.5 single stream;
2,705 / 2,696 at concurrency 64), so these are the machine's numbers, not one
lucky container's.

**Cost per million output tokens**, from the same runs at each vendor's
published rate:

| | Modal L40S | Modal H100 | RunPod L40S (comm.) | RunPod H100 SXM (comm.) |
|---|---|---|---|---|
| $/hr | 1.95 | 3.95 | 0.79 | 2.69 |
| at concurrency 1 | $28.83 | $14.03 | $11.68 | $9.56 |
| at concurrency 32 | $1.14 | $0.64 | $0.46 | $0.43 |
| at concurrency 64 | $1.14 | **$0.41** | $0.46 | **$0.28** |

## What the measurement changed

Four things, all of which had been asserted the other way:

1. **L40S is the wrong card, at any budget.** It is cheaper per hour and
   *more expensive per token* — $0.46/Mtok against H100's $0.28 on RunPod,
   $1.14 against $0.41 on Modal. The estimate had L40S as the value pick for
   part-time use. It is not: H100 is 4.2x faster single-stream and 5.7x faster
   batched, against 2.0x the hourly rate on Modal and 3.4x on RunPod.
2. **L40S saturates at concurrency 32.** 477 tok/s at 32, 476 at 64 — adding
   load buys nothing. H100 was still climbing at 64 (1,728 → 2,705). Any
   throughput plan that assumed L40S scales was wrong.
3. **The estimates were optimistic by 1.5x–5x.** Predicted $0.19/Mtok for H100
   (measured $0.28 on the same vendor and rate), $0.24 for L40S (measured
   $0.46). The bandwidth-scaling method got *single-stream* roughly right
   (H100 predicted 85 tok/s, measured 78) and *batched* badly wrong, because
   batched throughput is not a bandwidth question.
4. **Cold start is 5.7 minutes even with every cache warm**, and 9 minutes
   cold. On H100 that is **$0.38 of GPU time before the first token**, every
   time a container starts. Scale-to-zero was recommended for bursty use on
   the assumption that idle is free. Idle is free; *waking* is not. Above
   roughly one wake per hour, keeping a container warm is cheaper than
   letting it sleep.

## Two constraints that only appear when you run it

**Gated DeltaNet allocates one recurrent state block per decode sequence**, out
of the same pool as the KV cache. On a 48 GiB L40S at 64k context there are
**167** of them, and vLLM's default `max_num_seqs=256` exceeds that — so the
engine *refuses to start*, with `max_num_seqs (256) exceeds available Mamba
cache blocks (167)`. This is a concurrency ceiling, not a context ceiling, and
it is invisible in every VRAM table published for this model, all of which
reason about weights plus KV. Set `max_num_seqs` explicitly.

**FlashInfer JIT-compiles at engine init and calls nvcc**, at three separate
sites (sampler, decode plan, prefill module). On an image without a CUDA
toolkit the engine dies in `profile_run` with `Could not find nvcc and default
cuda_home='/usr/local/cuda' doesn't exist`, four frames down, on any GPU.
Turning the sites off one environment variable at a time does not converge —
the base image has to be a CUDA **devel** image.

## Consequences for this fleet

**Do not put Qwen3.8-27B behind `murakumo-main` on the Mac minis.** The current
head model is `qwen3.6-35b-a3b` — MoE, ~3B active, so decode reads ~1.9 GB per
token at Q4 and the fleet is tuned around that. Qwen3.8-27B is dense: **16.7 GB
per token at Q4, about 9x the traffic.** The nominal parameter count is
*smaller* than what the head runs today, and it will be roughly an order of
magnitude slower. "35B runs fine, so 27B will" does not hold across the
MoE/dense boundary. This is rule 8's shape again — same weights class, entirely
different execution.

The one node with 48 GB unified memory could hold Q4, at an estimated 11–12
tok/s (bandwidth-derived, **not measured** — the fleet was not benchmarked for
this ADR). `gad` and `xavier` are worse: `xavier` already measured 2.7x slower
than `gad` on decode for the *MoE*, and dense widens that.

Ring/tensor-parallel sharding across Mac minis does not help. Dense decode is
bandwidth-bound and the model fits one 48 GB node; splitting it over Ethernet
only adds hops.

**If we do serve it, serve it batched on H100, not L40S.** At concurrency 1 the
economics are poor at any vendor — $9.56/Mtok on the cheapest measured
configuration — so a single-user agent path is not the case that justifies
renting. The case that justifies renting is many concurrent requests, where
H100 at 2,705 tok/s reaches $0.28/Mtok.

## Not measured

Named so nobody reads their absence as a pass:

- **RTX PRO 6000, H200, A100, B200** — the harness has entry points for H200
  and A100; neither was run.
- **MTP speculative decoding.** The `--mtp` flag exists and was not exercised.
  Upstream reports a meaningful gain (124 → 132 tok/s single-stream on a 3090),
  so every number here is a floor for a correctly-configured deployment.
- **The OpenAI server path.** These are offline `LLM.generate()` numbers; HTTP,
  tokenizer round-trip, and scheduler behaviour under real arrival patterns are
  not in them.
- **Our own fleet.** No Mac mini, `gad`, or `xavier` number in this ADR is
  measured. The 11–12 tok/s figure is derived from memory bandwidth and should
  be treated as an estimate until someone runs it.
- **Consumer cards.** The 3090/4090/5090/M5 Max figures quoted in the original
  comparison are third-party llama.cpp Q4 results, not ours, and are not
  reproduced here.
- **Quality.** Throughput only. FP8 with `kv-cache-dtype fp8` and a default
  1.0 KV scaling factor may cost accuracy; vLLM warns about exactly this at
  startup and we did not check.

## How to re-run

```bash
modal run tools/modal-bench/qwen38_27b_bench.py --gpu H100 \
  --max-model-len 65536 --max-num-seqs 128 --out h100.json
```

Exit code 2 means the harness could not answer — distinct from both pass and
fail. It refuses to print a result when nothing was measured, which is how the
five failed attempts behind this ADR stayed legible as failures instead of
arriving as zeros.
