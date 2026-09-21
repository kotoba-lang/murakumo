# ADR-260921 — LLaDA 2.2 single-B300 diffusion serving and Kotoba adaptation baseline

**Status:** accepted and closed (experimental qualification, 2026-09-21 JST)

**Model:** `inclusionAI/LLaDA2.2-flash`

**Hardware:** one NVIDIA B300 SXM6 AC, 275,040 MiB

**Evidence:** [`data/ADR-260921-llada22-b300-evidence.json`](data/ADR-260921-llada22-b300-evidence.json)

## Context

We needed to establish whether LLaDA 2.2 Flash can be hosted on one B300,
measure concurrency and aggregate throughput, test CUDA Graph and online FP8,
freeze a leakage-resistant executable Kotoba coding evaluation, and train a
first adapter without deleting artifacts required by later quantization or
fine-tuning work.

This ADR records measured state separately from estimates and external results.
It qualifies an experimental configuration; it does not declare a production
Murakumo route, deployment, or live service.

## Decision

1. Retain BF16 SGLang v0.5.20 eager execution as the qualified experimental
   serving configuration.
2. Use concurrency 8 as the measured short-response operating point.
3. Keep CUDA Graph disabled. Capture completed, but replay caused NVIDIA Xid 31
   and an MMU/illegal-memory-access failure that terminated serving.
4. Do not select SGLang online FP8 for aggregate serving. It improved c1 and
   reduced weight memory, but regressed c8 aggregate throughput and slightly
   regressed the retained coding baseline.
5. Retain the rank-8 Kotoba LoRA as an experimental artifact only. It is
   loadable through a dedicated patched SGLang/CSGMV path, but is not
   quality-qualified.
6. Keep train/validation and executable test data physically separated by
   Modal Volume. Future training must not read the eval-only Volume.
7. Preserve all model, result, dataset, quantization, and adapter Volumes.

## Measured serving evidence

Conditions for the qualified base run: SGLang v0.5.20, `JointThreshold`, block
size 32, M2T threshold 0.5, T2T editing threshold 0.0, maximum post-edit steps
16, FDFO enabled, BF16, 64 requested output tokens, CUDA Graph disabled, and
three repetitions per concurrency.

| Concurrency | Aggregate tok/s | Per-request tok/s |
|---:|---:|---:|
| 1 | 79.10 | 79.10 |
| 2 | 157.60 | 78.80 |
| 4 | 323.34 | 80.83 |
| 8 | **687.20** | **85.90** |
| 16 | 643.00 | 40.19 |

Concurrency 8 is the measured knee. SGLang logged no tuned B300 fused-MoE
configuration for `E=256,N=1024`; the default kernel was used. This is an
operating point, not a B300 hardware ceiling.

### Experimental LoRA serving

SGLang v0.5.20 explicitly disables LoRA for diffusion LLM inference. For this
bounded experiment, the serving adapter names were converted from
`query_key_value/dense` to `qkv_proj/o_proj`, the guard was bypassed in a
dedicated image, and CSGMV was used. Logs confirmed loading of both target
modules.

| Concurrency | Base aggregate tok/s | LoRA aggregate tok/s | LoRA per-request tok/s |
|---:|---:|---:|---:|
| 1 | 81.07 | 84.36 | 84.36 |
| 2 | 149.42 | 160.78 | 80.39 |
| 4 | 331.14 | 348.36 | 87.09 |
| 8 | 681.52 | **713.49** | **89.19** |
| 16 | 643.25 | 630.12 | 39.38 |

The small apparent LoRA advantage through c8 reflects a different denoising
trajectory and output, not negative adapter overhead. At c16 it was 2.0%
slower. A separate 1,088 tok/s observation was not reproduced by the
same-process A/B run and is rejected as a stable claim.

### Online FP8

| Concurrency | BF16 aggregate tok/s | Online-FP8 aggregate tok/s |
|---:|---:|---:|
| 1 | 79.10 | 98.49 |
| 2 | 157.60 | 156.53 |
| 4 | 323.34 | 309.63 |
| 8 | 687.20 | 339.48 |

Online FP8 reduced reported weight memory from about 191.7 GiB to 99.51 GB,
but failed the aggregate-throughput gate. The held-out dependency/reference
accuracy was 48.44%, versus 49.12% for BF16. This result does not reject a
future calibrated offline ModelOpt FP8 or NVFP4 checkpoint.

## Kotoba quality evidence

The fine-tune-before dependency/reference baseline on 1,024 deterministically
sorted held-out questions was 503/1,024 (49.12%), with a 100% parse rate and
33.64 requests/s. The uniform-random expectation was 39.50%. This is not a
generative executable pass@1 result.

The executable set contains 24 real bug-fix commits verified with FAIL-before
and PASS-after controls. Its repository-disjoint split is 17 train, three
validation, and four test tasks. The first LoRA used five complete train and two
complete validation examples fitting the 8,192-token cap, rank/alpha 8/16, and
ten optimizer steps.

Masked validation loss improved from 5.53097 to 2.68317, but held-out generated
code remained 0/2 for both base and LoRA. The other two frozen test cases remain
unmeasured. Loss improvement is therefore only an optimization signal, not an
executable coding improvement.

## External diffusion comparison

Google DeepMind's DiffusionGemma technical report includes a multi-user curve
on one H100, FP8, PG-19, 4,096 input tokens, 1,024 output tokens, and 16 tokens
per forward. These are approximate readings from Figure 12, not a numeric table:

| Concurrency | Aggregate tok/s | Per-user tok/s |
|---:|---:|---:|
| 1 | about 1,300 | about 1,300 |
| 2 | about 1,650 | about 825 |
| 4 | about 2,100 | about 525 |
| 8 | about 2,500–2,550 | about 315–320 |
| 16 | about 2,850–2,950 | about 180 |
| 32 | not numerically published | not numerically published |

The report says autoregressive serving begins to gain a throughput advantage
around 32 concurrent requests, and that its batch-greater-than-one kernels and
sampling were not specifically optimized. These decode-oriented results are
not directly equivalent to the 64-token LLaDA HTTP benchmark.

Sources:

- <https://arxiv.org/abs/2608.00146>
- <https://arxiv.org/pdf/2608.00146#page=18>
- <https://vllm-project.github.io/2026/06/10/diffusion-gemma>

The comparison establishes that diffusion decoding can produce materially
higher aggregate throughput than the current LLaDA path. It does not establish
that the 103B-total LLaDA 2.2 model should match the 25.2B-total/3.8B-active
DiffusionGemma model.

## Rejected and unqualified options

- CUDA Graph on the tested B300/SGLang build: rejected after replay failure.
- Online FP8 as the c8 default: rejected after throughput and quality regression.
- Current LoRA as a selected Kotoba model: unqualified after 0/2 executable passes.
- Standalone 1,088 tok/s LoRA result: rejected as unreproduced.
- General superiority of SGLang, vLLM, Dinfer, or Murakumo: not established.
- DiffusionGemma c32 numeric throughput: not published in the reviewed figure.

## Retained state

No Modal Volume was deleted. Retained Volumes:

- `llada22-flash-bf16-cache`
- `llada22-optimization-results`
- `llada22-quantized-checkpoints`
- `llada22-kotoba-training-data`
- `llada22-kotoba-train-data-v1`
- `llada22-kotoba-eval-data-v1`
- `llada22-kotoba-adapters`
- `llada2-flash-bf16-cache`
- `llada2-optimization-results`

Retained adapters:

- `llada22-kotoba-adapters:/kotoba-exec-v1-attn-r8-steps10`
- `llada22-kotoba-adapters:/kotoba-exec-v1-attn-r8-steps10-sglang`

## Reopening gates

Reopen only for a bounded next phase that:

1. measures 128/1,024 and 1,000/1,000 workloads at c1–c32, separating prefill,
   generation-only, and end-to-end throughput;
2. records denoising forwards, committed tokens per forward, TTFT, p50/p95, and
   executable pass rate alongside TPS;
3. tunes the B300 MoE/sampling path before treating current throughput as a
   hardware limit;
4. expands repository-disjoint training data, emphasizes changed spans, and
   requires held-out executable improvement before adapter selection; and
5. evaluates calibrated offline FP8 and NVFP4 against the retained BF16 quality
   baseline.

## Closure boundary

Closed means the measured experimental investigation and its evidence record
are complete. It does not mean deployed, production-qualified, publicly routed,
or live-verified in Murakumo.
