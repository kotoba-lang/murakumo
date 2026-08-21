# ADR-260820d: which model to serve resident under ¥300,000/month, and on whose hardware

- Status: accepted
- Date: 2026-08-20
- Depends: ADR-260820b (Modal configuration), ADR-260820c (the $300 ceiling plan)

Follow-up: ADR-260821e/f measured the previously proxied Hyperstack PCIe and
RunPod NVL cards. Hyperstack remains the resident value pick; RunPod NVL does
not deliver the bandwidth-scaled batched throughput assumed below.

## Context

The ceiling moved: up to **¥300,000/month** (about **$1,893** at ¥158.5/USD),
and the question changed with it — not "how do we avoid over-billing" but
"**resident** on Modal, which model has the best cost-performance given tokens,
MTP and concurrency, and how does RunPod compare".

Resident means 730 hours, so the arithmetic is **$2.59/hour**. Two things fall
out of that immediately, and they reframe everything below.

## Finding 1: for resident serving, Modal is the wrong vendor

| | Modal (2 core / 16 GiB) | RunPod Community | ratio |
|---|---|---|---|
| L40S 48GB | **$1,587/mo** (¥251k) | **$577/mo** (¥91k) | **2.75x** |
| H100 80GB | $3,045/mo (¥483k) — over | $1,453–1,964/mo (¥230–311k) | 1.6–2.1x |
| H200 141GB | $3,476/mo (¥551k) — over | $2,621/mo (¥415k) — over | 1.33x |

**Within ¥300,000 resident, Modal can host an L40S and nothing above it.**
RunPod can host an H100. The gap is not a discount — it is structural: Modal
bills CPU and memory separately from the GPU, and its GPU rates are higher.

This does not contradict ADR-260820c. Modal's advantages are scale-to-zero and
a **workspace budget that actually stops workloads**; RunPod has neither (its
only mechanism is the prepaid balance running out, and at $0 a Pod without a
network volume is destroyed). **Modal is for bursty work under a hard cap.
RunPod is for resident work.** Choosing by vendor rather than by workload is
what produces a bad answer here.

## Finding 2: on identical hardware the MoE is 2.2–4.3x the dense model

Measured, vLLM 0.27.1, FP8, MTP on, greedy, `min_tokens == max_tokens`:

| card | model | single | c8 | c32 | c64 | c128 |
|---|---|---|---|---|---|---|
| L40S 48GB | Qwen3.8-27B dense (64k) | 60.4 | 370.5 | 511.3 | 510.1 | 509.1 |
| L40S 48GB | **Qwen3.6-35B-A3B** (32k) | **201.0** | 757.8 | 1,897.3 | 1,938.1 | **2,166.9** |
| H100 SXM 80GB | Qwen3.8-27B dense (64k) | 218.8 | 1,125.3 | 3,640.1 | 3,431.7 | 4,279.5 |
| H100 SXM 80GB | **Qwen3.6-35B-A3B** (64k) | **491.4** | 1,281.5 | 6,791.8 | **11,439.8** | 10,060.9 |

**L40S: 3.3x single-stream, 4.3x batched. H100: 2.2x single, 2.7x batched.**
The multiplier is larger on the weaker card, for the same reason MTP's is: the
MoE activates 3B parameters per token against the dense model's 27B, so it reads
roughly a ninth of the bytes, and bandwidth is what the cheap card is short of.

Monthly token capacity if resident and saturated: **30.1 Btok** for the MoE on
H100 against **11.2 Btok** for the dense model.

**Qwen3.6-35B-A3B does not run on RTX PRO 6000 at all** — vLLM 0.27.1 dies at
init with `Assertion error (deepgemm layout.hpp:60): Unknown SF transformation`.
That removes RunPod's cheapest 96 GB option (¥196k) from consideration for this
model. Measured, recorded in `docs/adr/data/`.

## Finding 3: the MoE is cheaper per token and worse at the work

This is the part cost-per-token cannot answer, and it decides the recommendation.

Third-party benchmark figures (**not our measurements**):

| | SWE-bench Pro | Terminal-Bench 2.1 | GPQA Diamond |
|---|---|---|---|
| Qwen3.8-27B dense | **61.7** | **73.0** | 89.2 |
| Qwen3.6-27B dense | 53.5 | 63.4 | — |

and within the Qwen3.6 family, the dense 27B leads the 35B-A3B by **3.8 points
on SWE-bench** and **11.1 points on Terminal-Bench**.

So the MoE we measured is behind on two counts at once — MoE rather than dense,
and one generation older. **The gap is widest on Terminal-Bench, which is
exactly the agentic multi-step work this workspace runs.** A model that fails
the task wastes every token it spent, so a 2.7x token advantage does not
survive contact with a 11-point success-rate deficit unless the workload is
bulk generation rather than agency.

## Decision

**Two answers, because there are two workloads.**

**Agentic work — Qwen3.8-27B dense, resident on a RunPod H100.**
$1,453/mo (¥230k) on H100 PCIe 80GB with headroom, or $1,891/mo (¥300k) on
H100 NVL 94GB at the line. Measured on H100 SXM: 218.8 tok/s single-stream,
4,279 batched, $3.42/Mtok single and $0.175 batched at SXM rates.

**Bulk generation, classification, embedding, synthetic data — Qwen3.6-35B-A3B
on the same box.** 2.7x the tokens for the same rent, and the quality gap
matters much less when the task is not multi-step.

They are 28 GB and 37.5 GB in FP8, so **both fit in a 94 GB H100 NVL at once**
(65.5 GB, leaving ~28 GB for two KV caches) — routing by task rather than
choosing. **Untested**; the KV budget may not survive contact.

**Do not serve resident on Modal.** Keep Modal for the bursty path under the
$300 workspace budget of ADR-260820c; the two are complementary, not rivals.

## Named as not measured

- **The RunPod cards that actually fit the budget.** Every H100 number here is
  **H100 SXM 80GB**, measured on Modal. RunPod's under-¥300k options are
  **H100 PCIe 80GB** (~2 TB/s against SXM's 3.35 TB/s — expect materially
  slower decode) and **H100 NVL 94GB** (3.9 TB/s — expect faster). RunPod's
  H100 SXM is ¥311k, **3.7% over the line**. The table above uses SXM figures
  as a proxy for both and that proxy is the weakest step in this ADR.
- **Quality.** Every benchmark figure in Finding 3 is third-party. We measured
  throughput and nothing else, on either model.
- **The MoE at long context on L40S.** 37.5 GB of weights in 44.4 GB usable
  capped it at **32k**, against 64k for the dense model. Not a like-for-like row.
- **Two models resident in one box.**
- **RunPod itself.** Not one number in this series was measured on RunPod
  hardware; all of them are Modal measurements re-priced at RunPod's published
  rates. Throughput is a property of the card, but availability, Community
  Cloud reclaim behaviour, and host CPU/disk are not.
- **RunPod has no budget cap.** Under ¥300k there is prepaid balance and
  nothing else, and a Pod without a network volume is destroyed when it drains.
  A network volume is mandatory, not optional, if this route is taken.
