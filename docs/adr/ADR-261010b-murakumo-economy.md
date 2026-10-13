# ADR-261010b: The murakumo economy — a Bitcoin-shaped monetary system for inference

- Status: proposed
- Date: 2026-10-10
- Refines: ADR-261010 §4 (hybrid reward) and §5 (difficulty analogue)
- Amended by: ADR-261010c (Miner Genesis Allocation, 5% of cap)
- Depends: `:infer-credits` (`protocol-num/den`, `head-num/den`,
  `memory-time-weight`, `settle-pool-shares-2`, `ledger-violations`)
- Hardware figures are design assumptions (accepted as such, 2026-10-10)

> **Superseded 2026-10-13 by root ADR-2610131800 (owner decision, later the same day).**
> KUMO is the 2.1B-cap reward token after all: tradable as a fixed-supply ERC-20 on Base,
> converted through THORChain, with sale, foundation/team allocation and liquidity
> provision allowed (buyback still not). Prepaid credit has no unit name; it is the
> murakumo.cloud USD prepaid balance. The reward-pool revision below (f181488b) is
> withdrawn and this ADR is to be restored to the fixed-cap model of 2026-10-10 and
> aligned with ADR-2610131800 §3-§4. `poi/core` already implements that model.

> **Revised 2026-10-13 (owner decision): the subsidy is a KUMO reward pool, not a second
> unit.** KUMO is the unit of root ADR-2610031000 (murakumo's compute unit; credits fold
> into it 1:1; purchased KUMO is not redeemable, earned KUMO is). The first draft of this
> ADR used "KUMO" for a 2.1B-cap halving token kept apart from credits; that is withdrawn.
> What remains is the Bitcoin-shaped *schedule and split*: a funded pool of KUMO pays a
> per-block release that halves on Bitcoin's halving blocks, split between customer work,
> proven capacity, verification and a sunsetting treasury share. No new token is minted.
> Sections 1, 2, 7, 9, 10 and 11 are rewritten; 3–6, 8 and 12 hold with "subsidy" read as
> "release from the pool". Root ADR-2610101900 decision 7 (planes meter, they do not mint).

## Context

ADR-261010 decided that miners are paid by a fee stream plus a halving subsidy.
That decision leaves the monetary system itself open: supply, block cadence,
how subsidy is split, what stops a node from paying itself, where the ledger
lives and how parameters change. Miners trust Bitcoin because those answers are
fixed, public and simple. This ADR fixes them for murakumo.

The one place inference differs from hashing decides most of the design.
**Hashing work is a puzzle that nobody pays for, so it cannot be faked by
paying yourself. Inference work is a customer job, so a node can buy jobs from
itself to farm subsidy (wash inference).** Every rule below that has no Bitcoin
counterpart exists because of this.

## Bitcoin ↔ murakumo

| Bitcoin | murakumo |
|---|---|
| BTC, hard cap 21M | **KUMO reward pool**: funded, never minted; it can only pay out what was paid in |
| Block every 10 min | **Settlement block per Bitcoin block** (anchored) |
| Hash (SHA-256 work) | **VCU** (ADR-261010 §1, verified PFLOP) |
| Block subsidy, halving every 210,000 blocks (~4 y) | Release rate from the pool, halving **on the same blocks as Bitcoin** (1,050,000, 1,260,000, …) |
| Transaction fees | **Inference fees** (customer payments) |
| Difficulty adjustment (keeps block time) | **Subsidy split rule** (keeps subsidy tied to real work) and published inference hashprice |
| Coinbase maturity, 100 blocks | **Reward maturity, 144 blocks (24 h)**, the slashing window |
| Mining pools (PPS/FPPS/PPLNS) | murakumo PPS pool for fees, third-party pools allowed |
| Miner signalling (BIP 9) | **VCU-weighted signalling** for parameter changes |
| Fair launch, no premine | **No insider allocation, no sale, no new token**. A capacity-unlocked miner reservation is carved from the pool (ADR-261010c). Treasury takes a sunsetting share of the release |
| ECDSA/Schnorr | **Ed25519 + ML-DSA-65 hybrid** on every receipt and block |

## Decision

### 1. One unit: KUMO

- **KUMO** is what customers pay with and what nodes earn (root ADR-2610031000: credits
  fold into it 1:1; topups accrue it; purchased KUMO is not redeemable; earned KUMO is,
  through the existing redeem-request / redeem-settle path; transfers are not earnings).
- **The reward pool** is an account of KUMO, not a unit. It pays the per-block release of
  §2, split by §4. Rewards from the pool are earnings (redeemable under the same rules),
  so a node is paid in the one unit it already understands.
- There is no second token, no cap to sell against, and nothing to list. The fee economy
  and the reward economy are the same ledger (`:infer-credits` / `cloud-murakumo.kumo`).

### 2. Funding and release schedule

```
pool inflows     protocol cut share  p_pool · c · fees     (§7; published per block)
                 treasury deposits   operator-funded, published, never withdrawn back
                 forfeits            slashed stake (§8), expired miner reservations (261010c)
pool outflow     release R(h) = r(h) · B(h)                 B(h) = pool balance at block h
block            one settlement block per Bitcoin block
rate             r(h) = r0 / 2^era(h); era changes at Bitcoin halving heights
                 1,050,000, 1,260,000, 1,470,000, ...
smallest unit    1e-6 KUMO (exact below 2^53 for any plausible balance)
```

- **The pool can never pay more than it holds.** The release is a fraction of the
  current balance, so the liability is bounded by what was paid in. Nothing is promised
  ahead of funding, which is what made a fixed-cap token schedule a legal and
  accounting problem.
- **Halving keeps the Bitcoin calendar** (decided in ADR-261010c §9): the release
  *rate* halves on Bitcoin's own halving blocks, so early capacity earns a larger share
  of the same pool than late capacity. Miners already plan around that calendar.
- `r0` and `p_pool` are launch parameters (§11). Example: with `r0` = 1/200,000 per
  block, a balance that receives no new funding releases about 65% of itself in era 0's
  first 210,000 blocks; each later era releases at half the rate.
- The pool balance, inflows and every block's release are public (the block log of §9),
  so the schedule is auditable the same way Bitcoin's supply is.

### 3. What a block contains

```
header
  height, prev_hash, time
  receipts_root      Merkle root of VCU receipts settled in this block
  verdicts_root      Merkle root of verification results (pass / slash)
  challenges_root    Merkle root of capacity challenges issued and answered
  subsidy            S(h), and its split (§4)
  fees               total customer fees settled (KUMO)
  params_hash        hash of the active parameter set
  signatures         block signers, Ed25519 + ML-DSA-65 each
body
  receipts, verdicts, challenges, reward credits per node
```

A **VCU receipt** carries: job id, model class, VCU, producing node DID(s) with
head/worker split, the latent or logit commitment root, a hash of the
requester's identity (not the identity), fee, and the node's hybrid signature.

Multi-node jobs split VCU across participants with the existing `head-num/den`
and `memory-time-weight`, so distributed inference over onprem S boxes earns
the same way a single box does.

### 4. Splitting the subsidy

Each block's subsidy `S(h)` splits in this order:

```
treasury      τ · S          τ = 10% for the first 210,000 blocks after genesis, then 0
verification  v · S          v = 5%, permanent
production    P = S − τS − vS
  customer pool   P_c = min(P, λ · fees)
  capacity pool   P_k = P − P_c
```

- **Customer pool** (`P_c`): paid pro-rata to VCU from customer jobs in the
  block. It is capped so that the subsidy attached to customer work is worth
  at most `λ` times the fees that work paid (`λ = 1`). Fees and rewards are both
  KUMO (§1), so the cap is in one unit and needs no exchange rate (the first draft's
  `price_ref` is gone).
- **Capacity pool** (`P_k`): paid for **protocol-issued work** that no customer
  chooses (§5). It cannot be wash-traded.
- **Verification** (`v`): paid to verifiers per replay performed. It is
  permanent because verification has to be paid when customer demand absorbs
  the whole production share.

This rule plays the role of difficulty adjustment. In the early network demand
is small, so `P_c` is small and most of the subsidy pays for proving capacity,
just as early Bitcoin paid for hashrate with no transactions. As fees grow,
`P_c` absorbs the production share and `P_k` falls to zero. The network then
pays subsidy only for customer work, and later pays only fees. It is the same
path Bitcoin follows, with the switch driven by real demand instead of a
calendar.

### 5. Capacity challenges (the PoW-like part)

The protocol issues challenge jobs to every node at random times, at a rate
proportional to the node's declared capacity. It also sets a latency bound
from the node's declared hardware class. Passing a challenge earns its VCU in
the capacity pool. Challenge work is drawn, in order of preference, from:

1. **The verification queue**: replaying other nodes' steps (ADR-261010 §2)
2. **The public-goods queue**: open evaluation sets, open synthetic datasets
   and public benchmark renders, published openly
3. **Synthetic benchmarks**, only when both queues are empty

So the capacity pool buys useful work wherever it can, and burns compute on
pure puzzles only as a last resort. The latency bound is what turns this into
a proof of real hardware: a node cannot answer a diffusion step for a 0.8 kW
V-Pro class in time on a lesser box.

### 6. Why wash inference does not pay

Placement is random among eligible nodes. A requester cannot pick which node
runs its job, so a self-dealer with network capacity share `s` gets back only
a fraction `s` of what its own jobs pay out. With fee `F` per VCU, protocol cut
`c` and customer-pool subsidy `e` per VCU, washing is unprofitable when:

```
s · ((1 − c)·F + e)  <  F
⇔  e  <  F · (1/s − (1 − c))
```

The §4 cap gives `e ≤ λ·F`. With `λ = 1` and `c = 10%`, washing loses money for
any `s < 1/1.9 ≈ 52.6%`. A soft cap on any single operator's share of
customer-pool VCU (25% per block; the excess flows to the capacity pool) keeps
`s` well below that bound. The capacity pool cannot be washed because the
protocol, not the node, chooses that work.

### 7. Fees

- The customer pays a fee `F`, priced per model class in USD per VCU.
- The node receives `(1 − c)·F`. `c = protocol-num/protocol-den`, 10% at launch.
- The protocol cut funds the **PPS reserve** and operations. murakumo does not
  buy KUMO back with fee revenue. Doing so would make KUMO look like a claim on
  murakumo's business (see §10).
- **Fees are KUMO** (§1). The protocol cut `c` is split: `p_pool · c` funds the reward
  pool (§2) and the rest funds the PPS reserve and operations. A fee-funded buyback does
  not exist because there is nothing to buy back. Fees for work with a provider are moved
  by `:kumo/settle-run` (purchaser → providers + treasury), never burned (root
  ADR-2610031000 §2).
- **Pools**: murakumo runs a PPS pool, which pays a fixed USD per VCU from the
  reserve. Third-party pools may aggregate small onprem V/S operators and pay
  them however they choose. The protocol pays each node directly, and pools
  are optional, as in Bitcoin.

### 8. Stake, maturity and slashing

- **Maturity**: subsidy and fee credits mature 144 blocks (24 h) after the
  block that settled them. Until then a failed verification voids them. This
  is Bitcoin's coinbase maturity used as a slashing window.
- **Stake**: a node posts KUMO stake proportional to declared capacity. The
  stake must satisfy `stake > G / p` (ADR-261010 §2), where `p` is the node's
  sampling rate.
- **Bootstrapping stake**: new nodes have no KUMO. They join a probation tier
  with a capacity cap and a high sampling rate (`p = 20%`). Their matured
  subsidy auto-locks as stake until the requirement is met. Nobody has to buy
  KUMO to start mining.
- **Sampling rate by trust tier**: probation 20% → established 5% → long
  record 2% → TEE attested 1% → bit-exact murakumo silicon 0.5%. Bit-exact
  silicon also replaces tolerance checks with hash equality.
- **Slashing**: a failed check burns 50% of the stake and voids immature
  rewards. Repeat failures eject the node.

### 9. Ledger and consensus, phased

Building a new chain on day one would be the riskiest possible start. Instead:

| Phase | Ledger | Block signers | Trust model |
|---|---|---|---|
| 1 | murakumo settlement ledger (kotoba records), public block log | murakumo coordinator | Centralised but auditable. Each block's header hash is **anchored to Bitcoin** (OP_RETURN, batched hourly), so history cannot be rewritten silently |
| 2 | Same ledger | **Federation** of independent verifiers and operators, k-of-n hybrid signatures | Like Liquid: no single party can sign a block |
| 3 | awai (root ADR-2610092330) or an existing chain with post-quantum signature support | Open set, VCU- and stake-weighted | Any change to KUMO's transferability is a separate owner decision after legal review |

Anchoring to Bitcoin gives miners a reason to trust phase 1: the history
they're paid from is committed to the chain they already secure.
`ledger-violations` in `:infer-credits` becomes the per-block validity check
that every federation member runs.

### 10. Legal boundaries

- No sale, no insider allocation, no buyback, no new token. Rewards are paid only for
  verified work, from a pool that holds KUMO paid in beforehand.
- KUMO keeps root ADR-2610031000's rules: purchased KUMO is a prepaid instrument and not
  redeemable; earned KUMO (including pool rewards) is redeemable through the operator's
  settle step. Unused-balance thresholds under Japan's Payment Services Act decide when
  registration and deposit obligations apply to the prepaid part.
- Any transferable or exchange-listed form of value is out of scope (root
  ADR-2608311800: BOT is the only tradeable token).
- The treasury share is time-limited and published per block, so it is never an
  undisclosed allocation.

### 11. Parameters and governance

| Parameter | Launch value | Hard bounds | Changed by |
|---|---|---|---|
| Block cadence, halving heights, "release is a fraction of the balance" | One per Bitcoin block, Bitcoin halving heights | **Immutable** | — |
| `r0` era-0 release rate | set at launch | > 0, < 1/10,000 per block | Immutable after genesis |
| `p_pool` share of the protocol cut to the pool | set at launch | 0–100% | Signalling |
| `τ` treasury share | 10% | 0–10%, sunsets 210,000 blocks after genesis | Immutable sunset |
| `v` verification share | 5% | 2–10% | Signalling |
| `c` protocol cut | 10% | 5–20% | Signalling |
| `λ` customer-pool cap | 1.0 | 0.5–1.5 | Signalling |
| Operator soft cap | 25% | 10–33% | Signalling |
| Maturity | 144 blocks | 72–1,008 | Signalling |
| Sampling rates | §8 | Floor of 0.5% | Signalling |

**Signalling** works like BIP 9. Nodes set a bit in their receipts, and a change
activates when 90% of VCU over a 2,016-block window (2 weeks) signals for it.
Values can only move inside the hard bounds. The bounds themselves are as
immutable as the cap.

### 12. Attacks and responses

| Attack | Response |
|---|---|
| Wash inference | Random placement, `λ` cap, operator soft cap (§6) |
| Sybil nodes | Stake per declared capacity; a Sybil with real hardware is just more capacity |
| Fake or lesser GPU | Latency-bound challenges, memory-bandwidth timing, canaries |
| Lazy or colluding verifiers | Verifiers are themselves spot-checked by a second verifier; collusion needs control of both random draws |
| Withholding or cartel pricing | Third-party pools are allowed, placement is open, and the operator cap applies |
| Customer data exposure during replay | Replay only within the same trust tier, encrypted transport (X25519 + ML-KEM-768), TEE tier for sensitive customers, canary and public-goods jobs for low-trust verifiers |
| Quantum forgery of receipts or blocks | Ed25519 + ML-DSA-65 on every receipt and block signature; both must verify |
| Coordinator rewriting history (phase 1) | Bitcoin anchoring, then the federation (phase 2) |

## Consequences

- **Code follow-up (not done in the 2026-10-13 revision):** `src/murakumo/poi/core.cljk`
  and `kotoba/poi_core.kotoba` (with its shipped KIR under `resources/murakumo/oracle/`,
  checked by `poi_kotoba_parity_test`) still implement the withdrawn fixed-cap schedule
  (`subsidy-total-base`, `mga-base`, `subsidy-r0`). Moving them to `release-at` and a
  KUMO-denominated customer-pool cap changes the `.kotoba` source and needs the KIR
  regenerated with amu, which was not available on the host that made this revision.
  Until then those functions describe the first draft, not this ADR.
- `infer_credits_core` grows a block-settlement surface over the KUMO ledger: `release-at` (r(h)·B(h)), `subsidy-at` (the same, kept as the split input),
  `split-subsidy`, `customer-pool-cap`, `wash-bound?`, `mature?` and
  `slash`, all pure, under the same kotoba parity discipline as the rest of
  the credits oracle.
- Placement must be random among eligible nodes for customer jobs, with
  eligibility (model, tier, latency class) as the only filter. Any
  requester-to-node pinning feature must forfeit customer-pool subsidy.
- A challenge issuer and a public-goods queue become fleet services.
- Phase 1 needs a Bitcoin anchoring job and a public block explorer.

## Non-goals

- A token sale, an insider allocation or a fee-funded buyback.
- Building our own L1 before phase 3.
- Paying subsidy for idle capacity that has not answered a challenge.

## Open questions

- Launch values of `r0` and `p_pool`, and the size of the first treasury deposit.
- Contents and licensing of the public-goods queue.
- Federation membership criteria for phase 2.
- Whether the operator soft cap should apply per legal entity or per stake key.
