# ADR-260820c: serving Qwen3.8-27B under a $300/month ceiling that a mistake cannot exceed

- Status: accepted
- Date: 2026-08-20
- Depends: ADR-260820-qwen38-27b-serving-cost-measured,
  ADR-260820b-qwen38-27b-modal-efficient-configuration

## Context

The owner's constraint (2026-08-20): the monthly bill must stay **under $300
even if something is left running by mistake**. This is a ceiling requirement,
not a budget estimate — the interesting question is not what it costs when it
works, but what it costs when it goes wrong.

That constraint eliminates GPU choice as the mechanism. $300/month over 730
hours is **$0.411/hour**. Modal's *cheapest* GPU, a T4, is $0.59/hour before
CPU and memory and **$893/month** resident. **No Modal GPU can be left resident
for a month under $300.** The ceiling has to come from somewhere else.

## Decision

**Serve on Modal H200 with MTP, scale-to-zero, and set the Modal workspace
budget to $300.** The budget is the ceiling; the configuration is the
optimisation. Do not attempt to buy the ceiling by choosing a slower GPU — that
loses on both axes.

### Why the budget, and not RunPod

Modal has a **workspace budget** that caps monthly spend and stops workloads
when it is reached, enforced before credits, reset each billing cycle, settable
only by Workspace Owners/Managers under Usage & Billing.

**RunPod has no equivalent.** Its only mechanism is the prepaid balance running
out, and at $0 a Pod **without a network volume is terminated and its data is
unrecoverable**. Its documented $80/hour cap is an *hourly* limit — $58,400 a
month — which is not a ceiling in any useful sense. RunPod is cheaper per hour
and cannot be made safe by configuration; Modal is dearer per hour and can.

### The configuration

```python
@app.cls(
    gpu="H200",
    scaledown_window=60 * 7,   # ~= one engine build; see ADR-260820b
    max_containers=1,          # no fan-out; one runaway, not N
    timeout=60 * 10,           # a hung call dies in minutes, not an hour
    # never min_containers / buffer_containers
)
```
```python
LLM(model="Qwen/Qwen3.8-27B-FP8", kv_cache_dtype="fp8",
    max_model_len=65536, max_num_seqs=256,
    speculative_config={"method": "mtp", "num_speculative_tokens": 3})
```

Plus, once: **workspace budget $300** in Modal's Usage & Billing settings.

## The arithmetic

Measured H200 all-in rate: **$0.001437/s = $5.17/hour**. A $300 budget buys
**58 GPU-hours a month**. One cold start (429 s) is **$0.62**. Output tokens are
**$5.43/Mtok at concurrency 1** and **$0.231/Mtok at 128**.

| scenario | monthly | % of ceiling |
|---|---|---|
| 100 wakes + 3M output tokens | $78 | 26% |
| 150 wakes + 5M output tokens | **$120** | 40% |
| 300 wakes + 10M output tokens | $239 | 80% |
| **container left resident by mistake** | **$300, then stopped** | 100% |

The failure mode is worth stating precisely: a container accidentally held open
burns $5.17/hour and would reach $300 in **58 hours — 2.4 days**. Without the
budget it would reach **$3,776** by month end. The budget does not prevent the
mistake; it converts an unbounded one into a bounded one, and 2.4 days is long
enough that a daily glance at usage catches it first.

**Wakes dominate light use, not tokens.** At 150 wakes and 5M tokens the split
is $93 of starts against $27 of generation. The highest-leverage habit is
therefore batching work into fewer, longer sessions — `scaledown_window` of 7
minutes means a session whose gaps stay under 7 minutes costs one wake, not one
per prompt.

Volume storage is free at this size: Modal gives 1 TiB/month, and the weights
plus compile cache are ~30 GB.

## Alternatives rejected, with the reason

**A cheaper Modal GPU.** Does not reach the ceiling (T4 is $893/month resident)
and loses on cost per token anyway — measured, the ranking by $/token follows
memory bandwidth, so the cheap cards are dearer per token in both regimes
(ADR-260820b).

**RunPod RTX 4090 Community pod, $0.34/hour = $248/month.** This is the only
configuration measured or quoted here that is *structurally* under $300 while
resident. It is rejected because: it costs $248 whether or not it is used,
against ~$120 expected on Modal; 24 GB forces Q4 instead of FP8 and caps
context near 64k; Community Cloud instances can be reclaimed; and **we have not
measured this model on a 4090** — the nearest datapoint is a third party's
RTX 3090 under vLLM at 114 tok/s single-stream, which is not the same card, the
same quantisation, or our measurement.

**Own hardware.** A used RTX 3090 box is roughly $105/month all-in (36-month
amortisation plus power at ¥35/kWh) and **cannot over-bill at all** — the
ceiling is physical rather than contractual. The same third-party measurement
puts it at 114 tok/s single-stream and 1,094 tok/s at 64 concurrent under vLLM
with int8, which is in the same class as Modal's RTX PRO 6000 with MTP (114.6
tok/s, measured by us). It is not the primary recommendation only because it is
Q4/int8 at 24 GB rather than FP8 at 141 GB, and because it is capital rather
than usage. **If the ceiling matters more than the model quality, this is the
better answer**, and it fits the existing fleet — `gad` is already a Linux GPU
box.

## Do this before relying on the ceiling

1. **Set the workspace budget and confirm the number it accepted.** Modal's
   documentation says the maximum settable budget "depends on prior successful
   charges for the Workspace" — a workspace that has only spent free credits
   may not be allowed to set $300 yet. **Unverified for this workspace.**
2. **Confirm what the budget does to a running container.** Modal's docs say
   it "stops workloads that would incur additional out-of-pocket charges" but
   do not state whether an in-flight container is killed or allowed to finish.
   **Unverified.**
3. **Check the Starter plan's $30/month credit interacts as expected.** The
   workspace budget caps usage *before* credits; a separate spend limit caps
   net charges *after* credits. Two different numbers.

Until 1 and 2 are confirmed by observation, the ceiling is a documented
promise, not a measured one — which is the distinction this ADR series exists
to keep.

## Registering it

The endpoint goes behind the `murakumo-main` alias in murakumo KV, so consumers
follow on their next call without redeployment. **Measure before switching**:
the current head model is a ~3B-active MoE and this is a dense 27B, so per-token
latency at concurrency 1 changes character even though the nameplate is smaller
(ADR-260820).
