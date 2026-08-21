# ADR-260821b: Qwen3.8-27B serving — state of the whole investigation, and where to resume

- Status: accepted
- Date: 2026-08-21
- Index for: ADR-260820, ADR-260820b, ADR-260820c, ADR-260820d, ADR-260821,
  ADR-260821c, ADR-260821d, `docs/modal-gpu-reference.md`
- Reproduce with: `tools/modal-bench/qwen38_27b_bench.py`,
  `tools/modal-bench/qwen38_27b_snapshot.py`,
  `tools/modal-bench/modal_gpu_reference.py`. **31 raw result files in
  `docs/adr/data/`**, 8 of which are `could-not-measure` and kept deliberately.

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

## Open, in priority order

**1 — the load-bearing gap. Nothing has ever been measured on RunPod or
Hyperstack hardware.** Every ¥ figure for them is a Modal measurement
re-priced, and the cheapest options are **PCIe** parts scaled ×0.60 from
bandwidth — by the very method item 3 above says over-predicts. Two rented
hours close it.

| candidate | ¥/mo | status |
|---|---|---|
| Hyperstack H100 spot | 231,410 | **estimate** |
| RunPod H100 PCIe Community | 230,253 | **estimate** |
| RunPod H100 NVL 94GB Community | 299,676 | **estimate**, best est. $/token |
| RunPod H100 SXM Community | 311,246 | **measured throughput**, 3.7% over ceiling |

**2 — blocked on the owner, 2 steps.** Hyperstack signup and card. The safety
floor forbids the agent entering passwords or card details, and permits
purchases only against a payment method already on file; a new account has
none. **This is a rule, not a judgement call, and the standing authorisation in
CLAUDE.md explicitly does not override it.** Modal is currently the only GPU
vendor with a payment method on file — `runpod`, `hyperstack` and `agentmail`
were all absent from kagi and env when checked.

**3 — asset ready for that signup.** `murakumo-gpu-bench@agentmail.to`,
registered and OTP-verified 2026-08-21, free tier. Key in kagi as
`AGENTMAIL_API_KEY` (compartment `personal`). Its OTP arrived at
`hyperstack@mail.murakumo.cloud` and was read through Resend, so **the whole
verification loop is agent-operable** for the Hyperstack signup too.

The three smaller gaps are closed in ADR-260821c and ADR-260821d: Modal
documents H100→H200 auto-upgrades as billed at H100 rates; 2c/16GiB loaded and
ran the full H100+MTP plan with no material throughput loss; and a physical
A100-40GB showed W4A16 beating FP8 by 1.56x single-stream and 2.88x at
concurrency 128.

## Resume point

```bash
# after the owner has added a card to Hyperstack:
#   1. poll murakumo-gpu-bench@agentmail.to for the verification mail
#   2. issue an API key, store it in kagi as HYPERSTACK_API_KEY
#   3. provision a spot H100 PCIe, port the harness off Modal, run it, DESTROY IT
# if the card is not coming, the decision that needs no further measurement is:
#   RunPod H100 SXM Community, ¥311,246/mo, 3.7% over the ceiling, no estimates
```

## Standing caveats

- **Quality was never measured.** Throughput only, on either model. Every
  benchmark score quoted is third-party. FP8 KV cache with a default 1.0
  scaling factor may cost accuracy; vLLM warns at startup and we did not check.
- **Batched figures are n=1 and vary ±40% at concurrency 8–64** on shared
  hardware (single-stream is ±2%). Differences under ~30% there are not
  resolvable from this data.
- **Every price except Modal's and RunPod's is a published rate we read, not
  one we were billed.**
- **Concurrency-128 numbers are ceilings, not forecasts** — offline batches
  with every request present at t=0.
