# ADR-260821: burst-billed options for Qwen3.8-27B, and the one we had not priced

- Status: accepted
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

**For internal agent traffic, buy tokens. Do not rent a GPU for it.**

The prior ADRs are not wrong about which GPU or which vendor; they answered
"how do we self-host this well" and the answer stands. They never asked whether
to self-host, and at this volume the answer is no.

## When self-hosting still wins — three real reasons, one of them decisive here

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
