# ADR-260821b: Qwen3.8-27B serving — state of the whole investigation, and where to resume

- Status: accepted
- Date: 2026-08-21
- Index for: ADR-260820, ADR-260820b, ADR-260820c, ADR-260820d, ADR-260821,
  ADR-260821c, ADR-260821d, ADR-260821e, ADR-260821f,
  ADR-260822, ADR-260823,
  `docs/modal-gpu-reference.md`
- Reproduce with: `tools/modal-bench/qwen38_27b_bench.py`,
  `tools/modal-bench/qwen38_27b_snapshot.py`,
  `tools/modal-bench/modal_gpu_reference.py`,
  `tools/hyperstack-bench/qwen38_27b_bench.py`,
  `tools/runpod-serverless-bench/`. **36 raw result files in
  `docs/adr/data/`**, 9 of which are `could-not-measure` and kept deliberately.

## The question, and what it cost to answer

"What does Qwen3.8-27B cost on RunPod, Modal, and our own hardware." Answered
first from vendor rates plus third-party llama.cpp benchmarks, with every
datacentre cell filled by scaling memory bandwidth. Then measured. **Roughly
$40 of Modal compute over two days, all inside free credits.**

## Decisions, in force

1. **Self-host.** Owner, 2026-08-21, after seeing that buying tokens is 48–161x
   cheaper at this volume. Cost was not the deciding axis; prompts staying in
   the building and 2.8x lower latency were (ADR-260821).
2. **Model: `Qwen/Qwen3.8-27B-FP8`, dense, MTP on.** Not the 35B-A3B MoE,
   which is 2.2x faster on the same card but 11.1 points behind on
   Terminal-Bench — the work this workspace actually runs (ADR-260820d).
3. **Modal is for bursty work under a $300 workspace budget; resident goes
   elsewhere** (ADR-260820c, ADR-260820d).
4. **Not on the Mac fleet.** Dense 27B reads ~9x the bytes per token of the
   ~3B-active MoE the head runs today (ADR-260820).
5. **For bursty dense-27B work, use Modal rather than generic RunPod
   Serverless Flex.** Their active H100 rates are effectively equal, but Modal
   completed the full plan while the RunPod handler was still not ready at the
   deliberate 25-minute limit (ADR-260822).
6. **The public FastMTP GGUF route stays on Modal RTX PRO 6000 pending demand
   data.** H100 improved a 256-token one-shot run by 24% but not a short run;
   A100 40GB was the best derived warm single-request $/token but 18% slower
   than RTX. Keep one llama.cpp slot per GPU and scale replicas for real
   parallel latency (ADR-260823).

## Measured (all Modal, vLLM 0.27.1, FP8 unless noted)

| card | MTP off, single | **MTP on, single** | best batched | best $/Mtok |
|---|---|---|---|---|
| L40S 48GB | 18.8 | 60.4 | 511 | $1.40 |
| RTX PRO 6000 96GB | 45.5 | 114.6 | 2,670 | $0.381 |
| A100 40GB (W4A16) | — | 108.1 | 959 | $0.672 |
| H100 SXM 80GB | 78.2 | **218.8** | 4,280 | $0.297 |
| H200 SXM 141GB | 93.9 | **264.4** | 6,232 | **$0.231** |
| B200 180GB | — | 261.9 | 7,746 | $0.247 |
| H100 + 35B-A3B MoE | — | 491.4 | 11,440 | $0.101 |

Config, every value traceable to one of the above:

```python
LLM(model="Qwen/Qwen3.8-27B-FP8", kv_cache_dtype="fp8",
    max_model_len=65536, max_num_seqs=256, gpu_memory_utilization=0.92,
    speculative_config={"method": "mtp", "num_speculative_tokens": 3})
```

## Eight things measurement overturned

Kept together because the pattern is the point: **every one of them was
asserted confidently first.**

1. **MTP is worth more than any GPU upgrade** — 2.8x single-stream on H100,
   3.2x on L40S. Buying an H200 instead buys 1.20x.
2. **Cheap cards are expensive.** $/token follows memory bandwidth, not the
   inverse of price. L40S and RTX PRO 6000 lose to H100 and H200.
3. **The bandwidth estimates were 1.5–5x optimistic**, and got single-stream
   roughly right while getting batched badly wrong — batched throughput is not
   a bandwidth question. Realised bandwidth is 55–80% of spec and **falls as
   the card gets faster**.
4. **`enforce_eager=True` with MTP** saves 25% of start time and costs 6x per
   token. CUDA graphs are what the draft-verify loop runs on.
5. **The GPU-snapshot experiment was invalid** — `modal run` creates an
   ephemeral App and silently disables snapshots. It reported
   `restored_from_snapshot: false`: true about something never attempted, in
   the shape of a negative result. Re-run deployed, snapshots were created but
   **never restored across four probes**; unresolved, not refuted.
6. **`gpu="A100-40GB"` does not pin the card.** Three requests returned
   `A100-SXM4-40GB`, `A100-SXM4-80GB`, and `A100 80GB PCIe`.
7. **24 GB cards report 22 GiB and cannot hold this model at any quantisation.**
   W4A16 takes 21.11 GiB, NVFP4 **21.92** — FP4 is *bigger*. Body at 4 bits is
   ~13.9 GB; the other ~7 GiB is a 248k-vocab embedding, `lm_head`, vision
   tower and MTP head, which public checkpoints keep at BF16.
8. **Cold start is ~7 minutes and four attempts failed to shorten it.** Idle
   and loading bill identically, so the break-even for staying warm is exactly
   one build time.

## Vendor comparison, now measured

**1 — Hyperstack remains the resident value pick after measuring RunPod.** A
physical RunPod H100 NVL completed the same full 64k/MTP plan at 215.9 tok/s
single and 2,804.9 tok/s at concurrency 128 (ADR-260821f). It is 42.8% faster
than Hyperstack single-stream but only 0.6% faster at c128. At the available
$3.19/hour Secure rate it costs $0.316/Mtok at c128 against Hyperstack's
$0.200. Repriced at the unavailable $2.59 Community rate it is still
$0.256/Mtok. The bandwidth-scaled $0.145 estimate was wrong.

| candidate | ¥/mo | status |
|---|---|---|
| Hyperstack H100 spot | 231,410 | **measured: $0.200/Mtok c128; pick** |
| RunPod H100 PCIe Community | 230,253 | **estimate** |
| RunPod H100 NVL 94GB Community | 299,676 | measured throughput, price-normalised: $0.256/Mtok c128; out of stock |
| RunPod H100 NVL 94GB Secure | 369,099 | **measured: $0.316/Mtok c128; over ceiling** |
| RunPod H100 SXM Community | 311,246 | **measured throughput**, 3.7% over ceiling |

**2 — Vendor account work is complete.** The owner registered and funded
the account. One H100 spot VM was provisioned, measured, and destroyed for
$0.66890681 total; its temporary SSH keypair was also deleted. The API key must
still be rotated because it was passed through the task conversation. One
RunPod Secure H100 NVL was provisioned, measured, and terminated for about
$0.5688; the subsequent Pod list was empty and current spend was $0/hour. Its
API key must also be rotated.

The three smaller gaps are closed in ADR-260821c and ADR-260821d: Modal
documents H100→H200 auto-upgrades as billed at H100 rates; 2c/16GiB loaded and
ran the full H100+MTP plan with no material throughput loss; and a physical
A100-40GB showed W4A16 beating FP8 by 1.56x single-stream and 2.88x at
concurrency 128.

ADR-260822 tested the remaining burst-provider question directly. A fresh
right-sized Modal H100 run completed at 212.7 tok/s single and 4,204.6 tok/s at
c128 after a 374.6-second load. RunPod Serverless Flex allocated a healthy H100
worker, but its custom-image queue handler never became ready inside 1,500
seconds and spent $1.7611 without generating a token. This negative result is
kept; RunPod's proprietary cached-model path remains unmeasured.

ADR-260823 records the subsequent production FastMTP path. The Q4_K_P target
and FastMTP draft are exposed as `qwen3.8-27b-fastmtp-aggressive` through
`api.murakumo.cloud`; the direct Modal origin requires a gateway-held secret.
On the same llama.cpp stack, 256-token decode measured 63.5 tok/s on A100 40GB,
77.5 on RTX PRO 6000, and 96.4 on H100. A100 two-slot execution reduced each
request to about 33 tok/s and aggregate throughput to 60.9 tok/s, so production
remains one slot per GPU. The five-minute idle-retention charge is materially
larger than the approximately 17–22 second cold-load charge.

## Resume point

```bash
# The vendor comparison is complete. Rotate the Hyperstack and RunPod API keys.
# If revisiting resident serving, only a materially cheaper RunPod Community
# NVL offer or an NVL run with NVCC >=12.9 adds evidence. If revisiting burst,
# test RunPod's console-configured cached-model mount; generic Flex is closed.
# For FastMTP Modal economics, first measure public request inter-arrival gaps;
# then choose the warm window, A100 canary, or replica count from that trace.
```

## Standing caveats

- **Quality was never measured.** Throughput only, on either model. Every
  benchmark score quoted is third-party. FP8 KV cache with a default 1.0
  scaling factor may cost accuracy; vLLM warns at startup and we did not check.
- **Batched figures are n=1 and vary ±40% at concurrency 8–64** on shared
  hardware (single-stream is ±2%). Differences under ~30% there are not
  resolvable from this data.
- **Hyperstack's $2.00672043/hour and $0.66890681 run total were returned by
  its billing API.** Other non-Modal/non-RunPod prices remain published rates,
  not bills.
- **Concurrency-128 numbers are ceilings, not forecasts** — offline batches
  with every request present at t=0.
