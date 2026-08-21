# ADR-260821: burst-billed options for Qwen3.8-27B, and the one we had not priced

- Status: superseded in its recommendation by the owner's decision below; the
  measurements stand. ADR-260821e/f supersede the PCIe/NVL estimates below.
- Date: 2026-08-21
- Depends: ADR-260820b, ADR-260820c, ADR-260820d

## Context

Asked what else exists beside Modal for request- and burst-shaped billing.
Answering it surfaced a category this whole series had skipped: **Qwen3.8-27B is
already served by seven providers as a token-billed API**, and every ADR before
this one priced only "rent a GPU and run it yourself".

## The three shapes, priced

**1. VM by the hour or minute — Hyperstack and similar.** Cheap, and not burst
at all. Hyperstack bills per minute, has no scale-to-zero and no serverless
inference. H100 $2.50/hr, H100 NVLink $2.60, H200 SXM $3.99, A100 80GB $1.35,
RTX PRO 6000 SE $1.85, L40 $1.00. Reserved is ~30% off; spot H100 PCIe $2.00.
As resident capacity this beats both Modal and RunPod. As burst it does nothing.

**2. Serverless containers — Modal, RunPod Serverless, Baseten, Cerebrium,
Beam, Replicate, fal, Koyeb, Inferless.** You bring the model, it scales to
zero, billing is per second. RunPod Serverless flex: H100 $4.79/hr, RTX PRO
6000 $3.49, A100 $2.72, L40S $1.75, RTX 4090 $1.10 — all above the same
vendor's pod rates, which is the price of scale-to-zero.

**Every platform in this category pays our measured ~7-minute engine build on
every start** (ADR-260820b), and we could not make Modal's GPU snapshot restore
it. The category is structurally poor for a 27B with this cold start.

**3. Token-billed API — the one we had not priced.** Via OpenRouter, seven
upstreams serve this exact model: Chutes, Reka AI, Venice, Parasail, AkashML,
io.net, and Alibaba Cloud International. **$0.40/M input, $3.00/M output**
(range $0.40–0.575 and $3.00–3.45), cache reads $0.04/M, **1M context**, tool
calling and structured output, best observed latency 0.40 s.

## What that does to every number in this series

Our measured self-hosted cost, all-in, against $3.00/Mtok:

| | $/Mtok at concurrency 1 | $/Mtok batched | tok/s needed 24/7 to beat the API |
|---|---|---|---|
| Modal H200 + MTP | $5.43 | $0.231 | 479 |
| Modal H100 + MTP | $5.82 | $0.297 | 424 |
| RunPod H100 SXM | $3.42 | $0.175 | 249 |
| RunPod H100 PCIe* | $4.21 | $0.215 | 184 |
| Modal A100 40GB | $5.96 | $0.672 | 215 |

**At concurrency 1 every card we measured is more expensive than buying the
tokens.** Self-hosting only wins batched — and a rented GPU is paid for all 730
hours whether or not anything is in flight, so the comparison that matters is
rent against tokens actually produced:

| output tokens / month | API at $3.00/Mtok | RunPod H100 PCIe resident | ratio |
|---|---|---|---|
| 3M | ¥1,426 | ¥230,253 | **161x** |
| 5M | ¥2,378 | ¥230,253 | **97x** |
| 10M | ¥4,755 | ¥230,253 | 48x |
| 50M | ¥23,775 | ¥230,253 | 10x |
| **484M** | ¥230,253 | ¥230,253 | **1x — break-even** |

**The break-even is 484M output tokens a month, or 184 tok/s sustained around
the clock.** Below that, renting a GPU costs more than buying the output, and
for one operator's agent traffic it costs one to two orders of magnitude more.

## Decision

**Self-host. Owner's decision, 2026-08-21, after the numbers below were put in
front of them.** The token route is not taken.

This ADR originally recommended buying tokens on cost. That recommendation is
withdrawn, not because the arithmetic changed — at 3–10M output tokens a month
renting is still 48–161x the API bill — but because cost was never the only
axis, and two of the three counterweights below are decisive on their own:
prompts stay in the building, and self-hosted latency is ~2.8x the best hosted
provider. **The break-even table stays because it is the honest price of the
decision, not an argument against it.**

The concrete build follows in "Self-hosting: what to run and where".

## Why self-hosting was chosen anyway — the three counterweights

- **Latency.** Best throughput observed across the hosted providers is **94
  tok/s**; we measured **264 tok/s** on H200 with MTP. Self-hosting is ~2.8x
  faster per stream. If interactive latency is the product, that is worth
  paying for.
- **Data.** Prompts leave the building. OpenRouter's page does not state
  whether upstreams log or train on them, and this workspace's traffic carries
  actor DIDs, business data, and tool definitions. **This is a sufficient
  reason on its own and it is not a cost question.**
- **⚠ Selling inference is the business.** `murakumo.cloud` sells generation and
  `api.murakumo.cloud` gates it with capability tokens. **If this model is going
  behind that product, none of the above is a buy-versus-rent decision — the
  rent is COGS, and the question is margin against the $3.00/Mtok that seven
  competitors already charge.** Which of the two this is has not been stated,
  and it changes the answer completely. This ADR assumes internal use; **if
  that assumption is wrong, re-read the tables as cost of goods.**

## Self-hosting: what to run and where

**Model: `Qwen/Qwen3.8-27B-FP8`, dense, with MTP.** Not the 35B-A3B MoE,
despite the MoE being 2.2x faster single-stream and 2.7x batched on identical
hardware (ADR-260820d) — third-party benchmarks put the dense model ahead by
**11.1 points on Terminal-Bench**, which is the agentic multi-step work this
workspace runs, and a failed task wastes every token it spent.

**Config, every value of it measured (ADR-260820b, ADR-260820):**

```python
LLM(model="Qwen/Qwen3.8-27B-FP8",
    kv_cache_dtype="fp8",
    max_model_len=65536,
    max_num_seqs=256,
    gpu_memory_utilization=0.92,
    speculative_config={"method": "mtp", "num_speculative_tokens": 3})
```

- **MTP is not optional.** It is worth 2.8x single-stream — more than any GPU
  upgrade in the catalogue.
- **Never `enforce_eager=True` with MTP on.** Measured: it saves 25% of start
  time and costs 6x on every token.
- **Base image must carry nvcc** (CUDA devel). FlashInfer JIT-compiles at three
  sites and the engine will not start without it.
- **Set `max_num_seqs` explicitly.** Gated DeltaNet takes one recurrent state
  block per decode sequence out of the KV pool; vLLM's default 256 exceeds what
  a 48 GiB card holds and the engine refuses to start rather than degrading.
- vLLM 0.27.1.

**Where — resident, priced with measured throughput:**

| vendor | $/hr | ¥/mo | ≤¥300k | dense $/Mtok batched |
|---|---|---|---|---|
| Hyperstack H100 reserved −30% | 1.75 | **202,484** | ✓ | $0.189* |
| RunPod H100 PCIe Community | 1.99 | 230,253 | ✓ | $0.215* |
| Hyperstack H100 spot | 2.00 | 231,410 | ✓ | $0.216* |
| Hyperstack H100 (PCIe) | 2.50 | 289,262 | ✓ | $0.270* |
| **RunPod H100 NVL 94GB Comm.** | 2.59 | **299,676** | ✓ | **$0.145*** |
| RunPod H100 SXM Community | 2.69 | 311,246 | ✗ | $0.175 |
| Modal H100 SXM (all-in) | 4.17 | 482,490 | ✗ | $0.271 |

\* Only the SXM rows use measured throughput. **PCIe is scaled ×0.60 and NVL
×1.16 from bandwidth (2.0 and 3.9 against the measured 3.35 TB/s) — estimates,
and this series has never run a single benchmark on RunPod or Hyperstack
hardware.**

**Pick: RunPod H100 NVL 94GB at ¥299,676**, if the estimate holds — best
cost per token of anything under the ceiling, and 94 GB holds both the dense
model and the MoE at once (28 + 37.5 GB) if task routing is wanted later.
**Hyperstack reserved at ¥202,484 is the cheapest**, at the cost of a
commitment and a slower PCIe part.

**The one experiment that closes this.** Rent one H100 PCIe and one H100 NVL
for an hour each and run the same harness. Every ¥ figure in the top four rows
rests on a bandwidth ratio, and the whole point of this ADR series is that
bandwidth ratios over-predict by 20–45% and over-predict *worse* on faster
cards (ADR-260820b). **Until that is done the cheap rows are arithmetic, not
measurement.**

**Follow-up, completed 2026-08-21.** ADR-260821e measured Hyperstack H100 PCIe
at $0.200/Mtok c128. ADR-260821f measured RunPod H100 NVL at $0.316/Mtok on
the available $3.19 Secure offer, or $0.256/Mtok when price-normalised to the
out-of-stock $2.59 Community offer. The old $0.145 NVL estimate did not hold;
Hyperstack is the measured resident value pick.

**Do not put this on the Mac fleet.** Dense 27B reads ~9x the bytes per token
of the ~3B-active MoE the head runs today; the nameplate is smaller and it will
be roughly an order of magnitude slower (ADR-260820).

## Not measured

- **Every price here except Modal's and RunPod's is a published rate we read,
  not one we were billed.** OpenRouter, Hyperstack, Baseten, Cerebrium, Beam and
  the rest have never been invoked in this series.
- **Output quality of the hosted route.** Seven upstreams serving "the same"
  open-weight model may quantise differently; we did not compare a hosted
  completion against our own.
- **The hosted providers' data policies**, individually. Unstated on the
  aggregator page and unchecked at source.
- **Cold start on the other serverless-container platforms.** The 7-minute
  figure is Modal's, measured. One source reports snapshot restore cutting cold
  starts 71% with the largest gains on vLLM; we could not reproduce that on
  Modal and have not tried elsewhere.
