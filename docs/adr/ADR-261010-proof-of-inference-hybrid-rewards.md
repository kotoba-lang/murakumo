# ADR-261010: Proof of Inference — hybrid rewards for miners moving power to inference

- Status: proposed
- Date: 2026-10-10
- Depends: `:infer-credits` (ADR-260728 identity + credits oracle authority,
  ADR-260730 T5.3 share record), RULES.md rule 8 (caching weights does not prove
  execution)
- Audience: Bitcoin miners evaluating murakumo as a second use of their power
  contracts and sites

## Context

Bitcoin mining draws on the order of 12 GW. Miners own three things AI
infrastructure is short of: cheap contracted power with grid interconnect, sites
with cooling and electrical plant, and 24/7 operations that already curtail
load against power prices. Their ASICs cannot run inference, so a move to
inference is a reinvestment of power and site, not a hardware repurpose.

A miner stays in PoW because of five properties. Inference has none of them by
default:

| PoW property | Inference by default |
|---|---|
| Reward arrives without customers | Revenue only exists when demand exists |
| Work is cheap to verify | Re-running inference costs as much as running it |
| Difficulty adjustment balances supply | No supply/price feedback |
| Reward is liquid immediately | Invoice cycles |
| One comparable index (hashprice, $/PH/day) | No common unit across models and modalities |

The target workload is **video and image generation first, text second**. Video
diffusion is compute-bound, latency-tolerant when batched, and pays by output
second. That suits remote stranded-power sites better than interactive chat.

murakumo's distinguishing property is that it does **not** need datacentre
GPUs (H100/B200/B300). It already serves from Mac minis, Strix Halo, RTX
3090/4090/5090 and RTX 6000 boxes. Video diffusion jobs are independent per
clip, so no InfiniBand fabric is needed; large text models span nodes through
murakumo's distributed inference. The hardware sold to miners is therefore
**murakumo onprem**: air-cooled commodity servers that drop into existing
ASIC containers and racks.

Rough economics per MW (2026-09-28 hashprice $39.87/PH/day, 15 J/TH; 720p
distilled video at $0.025 per output-second to the node, 60% utilisation; all
hardware figures are design assumptions, not measurements):

| | kW all-in | unit price | output-s / unit-hour | capex / MW | gross / MWh |
|---|---|---|---|---|---|
| BTC ASIC | — | $15/TH | — | ~$1.3M | ~$110 |
| onprem V-Pro (96 GB workstation GPU) | 0.8 | $10,500 | 40 | ~$13.6M | ~$750 |
| H100 datacentre (reference) | 1.4 | $28,000 | 100 | ~$22M | ~$1,070 |
| murakumo silicon (target) | 0.4 | $4,000 | 120 | ~$10.5M | ~$4,500 |

The revenue gap, the capex gap and the demand risk are what this design has to
answer.

## Decision

### 1. One work unit across modalities: the Verified Compute Unit (VCU)

`1 VCU = 1 PFLOP of verified, useful forward compute`, computed from the job
manifest, not self-reported:

- text decode: `2 · P_active · T_out`; prefill: `2 · P_active · T_in · c_prefill`
  (`c_prefill < 1`, prefix-cache hits discounted per ADR-260907)
- diffusion (video/image): `2 · P_dit · N_latent_tokens · steps · cfg_mult`,
  plus VAE decode as a fixed per-frame term
- `memory-time-weight` (existing settle weight) stays as the occupancy term for
  long-context and resident-model holding

`:infer-credits` settles in VCU. `settle-pool-shares-2` already returns
`:credits/shares2`; the share inputs change from raw tokens to VCU.

### 2. Proof of Inference: asymmetric spot verification

PoW is valuable because verification is far cheaper than work. Each modality
gets an asymmetric check:

- **Diffusion step replay.** The node commits a Merkle root over the hashes of
  every intermediate latent `x_t` (and seed, sampler, kernel set). The verifier
  picks a random `t`, fetches `x_t`, recomputes one denoising step and compares
  `x_{t-1}` within a tolerance. Cost is about `1/steps` of the job.
- **Teacher-forced prefill for text.** Decode is sequential, but checking a
  finished output is one parallel prefill pass over prompt+output. The verifier
  compares top-k logprobs at sampled positions within a tolerance.
- **Canaries.** Jobs with known outputs, indistinguishable from customer jobs.
- **Memory-bandwidth timing.** Latency distribution per step has to be
  consistent with the claimed GPU and HBM. This is the counter to fake GPUs.
- **TEE attestation (optional tier).** H100/B200 confidential computing for
  datacentre participants; it raises the trust tier and lowers the sampling rate.

The sampling rate `p` is set per node from reputation. A node posts stake `S`
with `S > G / p`, where `G` is the gain from faking one sampling window. A
failed check slashes stake and voids unsettled VCU. zkML is out of scope until
it is economical at this model size.

### 3. Pool payout: PPS and PPLNS

murakumo acts as the pool:

- **PPS**: a fixed $/VCU per epoch, paid from the fee reserve. The pool carries
  demand variance. The protocol cut (`protocol-num/den`) funds the reserve.
- **PPLNS**: actual fee revenue split pro-rata over the last N epochs. The node
  carries variance for a higher expected value.
- **Availability reward**: a small per-epoch payment for holding a model
  resident and answering random challenges within latency. This is the
  substitute for "reward without customers".

### 4. Hybrid reward: fees plus a halving subsidy

The monetary rules (supply, block cadence, subsidy split, anti-wash bound,
ledger phases, governance) are fixed in ADR-261010b.

Rewards have two streams:

1. **Fee stream**: customer payments, settled in fiat or credits as PPS/PPLNS.
2. **Subsidy stream**: a fixed-supply reward token, **KUMO**, emitted once
   per Bitcoin block, halving on Bitcoin's halving blocks, distributed **only by verified VCU** (never by stake or
   idle capacity). Subsidy received is locked for a vesting period and doubles
   as slashing collateral.

The narrative is the same as Bitcoin: subsidy bootstraps supply, and fees become
the main reward over time. The fee stream ships first (credits, fiat
settlement). The token launches only after legal review (Japan: FIEA,
Payment Services Act; plus the target jurisdictions). Until then the subsidy
is accounted as non-transferable credit.

### 5. Difficulty analogue and a public index

Per epoch, the PPS rate adjusts by `demand_VCU / supply_VCU` within a band. The
protocol publishes a **murakumo inference hashprice in $/kW/day** per model
class next to BTC hashprice converted to the same unit.

### 6. Participation tiers

- **A — power host**: the miner provides MW, cooling and ops. GPUs come from
  murakumo or financing partners as onprem units. Revenue is a hosting fee per kW-month plus a
  revenue share.
- **B — node operator**: the miner buys murakumo onprem and earns the full PPS/PPLNS plus
  subsidy.
- **C — hybrid site**: ASICs and onprem under one power cap. The scheduler gives
  onprem power when inference demand peaks and returns it to the ASICs when
  demand drops. Batch video jobs route to stranded-power sites. Interactive
  text routes to well-connected nodes.

### 7. Hardware track: murakumo onprem, then murakumo silicon

Bitcoin mining went CPU → GPU → FPGA → ASIC. murakumo follows the same path:

- **Gen 0 (now)**: murakumo onprem built from commodity parts.
  - V-Pro: 96 GB workstation-class GPU, for video and image.
  - V: 32 GB consumer GPU, for distributed small sites.
  - S: 128 GB unified-memory boxes (Strix Halo / Mac class), for text and MoE
    over distributed inference.
- **Gen 1**: onprem with partner inference accelerators.
- **Gen 2**: murakumo silicon specialised for diffusion matmul/attention and
  memory bandwidth.

The efficiency metric published for miners is **output-seconds per kWh** (the
J/TH analogue).

Constraints on the silicon:

- VCU is hardware-neutral, so new silicon joins without a protocol change, the
  way SHA-256 absorbed ASICs.
- The silicon targets long-lived primitives (matmul, attention, FP8/FP4), not
  one model's graph, because model architectures keep changing and SHA-256 does
  not.
- Bit-exact deterministic execution is a design requirement. Step replay then
  becomes a hash comparison instead of a tolerance check, which brings
  verification close to PoW.

GeForce driver licensing restricts datacentre deployment (blockchain
processing excepted). Dense miner-site deployments use V-Pro or non-NVIDIA
GPUs, or need a licence review. V targets homes, offices and small distributed
sites.

### 8. Capacity forwards

murakumo sells forward capacity (for example, X GPU-hours over 6 months at $Y),
backed by the reserve and enterprise prepayments. A node can pledge the forward
as collateral for GPU financing.

### 9. Post-quantum readiness

Node identity, op-tokens and transport use Ed25519 and X25519 today, and
secp256k1-style chains are exposed in the same way. A cryptographically relevant
quantum computer would forge settlement receipts and payouts.

- Share receipts, payout authorisations and node identity move to a **hybrid
  signature: Ed25519 plus ML-DSA-65 (FIPS 204)**. Both must verify.
- Transport moves to **X25519 plus ML-KEM-768 (FIPS 203)** to defend customer
  prompts and media against harvest-now-decrypt-later.
- The token's settlement chain must support PQ signature migration. This is a
  launch gate.

## Consequences

- `infer-credits` changes its share unit to VCU. The pure oracle surface gains
  `vcu-text`, `vcu-diffusion` and `pps-rate` under the same parity discipline.
- A verifier role appears in placement. Verification jobs are placed on nodes
  other than the producer.
- Diffusion runtimes must expose intermediate latents and deterministic kernels
  for step replay. Runtimes that cannot do this run at the higher sampling rate
  or only in the TEE tier.
- Placement must treat consumer-grade onprem as less reliable than datacentre
  GPUs: failure rates and maintenance enter the PPS reserve and reputation.
- The economics depend on demand. PPS solvency is bounded by the reserve, so
  the PPS rate is capped by the reserve runway.

## Non-goals

- Running inference on Bitcoin SHA-256 ASICs.
- Requiring datacentre GPUs (H100/B200/B300) to participate.
- zkML proofs for frontier-size models.
- Launching the token before legal review.
- Any claim that quantum computing makes SHA-256 mining obsolete. Grover gives
  only a quadratic speedup and is not a practical mining threat.

## Open questions

- Tolerance bands for step replay across GPU generations (FP8 vs BF16 kernels).
- Reserve sizing for PPS at launch.
- The emission curve's starting rate, and whether vesting should scale with
  node reputation.
