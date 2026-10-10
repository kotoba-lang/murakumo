# ADR-261010b: The murakumo economy — a Bitcoin-shaped monetary system for inference

- Status: proposed
- Date: 2026-10-10
- Refines: ADR-261010 §4 (hybrid reward) and §5 (difficulty analogue)
- Amended by: ADR-261010c (Miner Genesis Allocation, 5% of cap)
- Depends: `:infer-credits` (`protocol-num/den`, `head-num/den`,
  `memory-time-weight`, `settle-pool-shares-2`, `ledger-violations`)
- Hardware figures are design assumptions (accepted as such, 2026-10-10)

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
| BTC, hard cap 21M | **KUMO**, hard cap 2.1B |
| Block every 10 min | **Settlement block per Bitcoin block** (anchored) |
| Hash (SHA-256 work) | **VCU** (ADR-261010 §1, verified PFLOP) |
| Block subsidy, halving every 210,000 blocks (~4 y) | Subsidy, halving **on the same blocks as Bitcoin** (1,050,000, 1,260,000, …) |
| Transaction fees | **Inference fees** (customer payments) |
| Difficulty adjustment (keeps block time) | **Subsidy split rule** (keeps subsidy tied to real work) and published inference hashprice |
| Coinbase maturity, 100 blocks | **Reward maturity, 144 blocks (24 h)**, the slashing window |
| Mining pools (PPS/FPPS/PPLNS) | murakumo PPS pool for fees, third-party pools allowed |
| Miner signalling (BIP 9) | **VCU-weighted signalling** for parameter changes |
| Fair launch, no premine | **No insider allocation, no sale**. 5% is reserved for miners and earned by delivered capacity (ADR-261010c). Treasury takes a sunsetting share of subsidy |
| ECDSA/Schnorr | **Ed25519 + ML-DSA-65 hybrid** on every receipt and block |

## Decision

### 1. Two units, kept apart

- **Credits**: what customers pay with. They are USD-denominated, prepaid and
  non-transferable. Fees settle in credits, and nodes cash them out in fiat.
  This is the existing `:infer-credits` ledger.
- **KUMO**: what the subsidy is paid in. It is used for stake and slashing, and
  optionally for paying fees. Its supply is fixed by §2.

Keeping two units means the fee economy works on day one without any token. KUMO
can stay non-transferable until legal review clears it (§10), and launching or
delaying it never touches customer billing.

### 2. Supply schedule

```
hard cap         2,100,000,000 KUMO
  miner genesis allocation   105,000,000  (5%, ADR-261010c)
  block subsidy            1,995,000,000  (95%)
block            one settlement block per Bitcoin block
halving          at Bitcoin halving heights: 1,050,000, 1,260,000, 1,470,000, …
era 0            genesis → first Bitcoin halving height after genesis (L0 blocks)
era 0 subsidy    R0 = 1,995,000,000 / (L0 + 210,000), fixed at genesis
era n ≥ 1        R0 / 2^n per block, 210,000 blocks each
smallest unit    1e-6 KUMO (micro-KUMO; the 2.1e15 total stays exact below 2^53,
                 so JS numbers and EDN carry every amount on every host)
```

Example with genesis at about BTC height 1,013,000 (mid-2027), so L0 ≈ 37,000
and R0 ≈ 8,077. Cumulative supply includes the 105M MGA:

| Era | Bitcoin heights | ≈ Years | Subsidy / block | Era emission | Cumulative supply | Share of cap |
|---|---|---|---|---|---|---|
| 0 | 1,013,000–1,050,000 | 2027–2028 | 8,077 | 0.299B | 0.404B | 19.2% |
| 1 | 1,050,000–1,260,000 | 2028–2032 | 4,038 | 0.848B | 1.252B | 59.6% |
| 2 | 1,260,000–1,470,000 | 2032–2036 | 2,019 | 0.424B | 1.676B | 79.8% |
| 3 | 1,470,000–1,680,000 | 2036–2040 | 1,010 | 0.212B | 1.888B | 89.9% |
| 4 | 1,680,000–1,890,000 | 2040–2044 | 505 | 0.106B | 1.994B | 94.9% |
| 5 | 1,890,000–2,100,000 | 2044–2048 | 252 | 0.053B | 2.047B | 97.5% |

The halving blocks are Bitcoin's own (decided in ADR-261010c §9). Miners
already plan capex, financing and treasury around that cycle, and a shared
calendar needs no explanation. Inference hardware depreciates faster than
ASICs; the §4 capacity pool answers that by paying for proven capacity while
demand is still small. The accepted cost is that BTC and KUMO subsidies drop
on the same block.

### 3. What a block contains

```
header
  height, prev_hash, time
  receipts_root      Merkle root of VCU receipts settled in this block
  verdicts_root      Merkle root of verification results (pass / slash)
  challenges_root    Merkle root of capacity challenges issued and answered
  subsidy            S(h), and its split (§4)
  fees_usd           total customer fees settled
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
  customer pool   P_c = min(P, λ · fees_usd / price_ref)
  capacity pool   P_k = P − P_c
```

- **Customer pool** (`P_c`): paid pro-rata to VCU from customer jobs in the
  block. It is capped so that the subsidy attached to customer work is worth
  at most `λ` times the fees that work paid (`λ = 1`). `price_ref` is a 7-day
  TWAP of KUMO in USD. While KUMO is non-transferable, `price_ref` is a published
  accounting price. Nobody can sell a non-transferable token, so nobody gains
  by manipulating that price.
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
- **Paying in KUMO** (optional): a customer may pay fees in KUMO at `price_ref`
  with a discount. A base portion of those KUMO is burned and the rest goes to
  the node. This works like EIP-1559's base-fee burn: usage removes supply only
  when customers choose KUMO.
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
| 3 | A chain with post-quantum signature support (own or existing) | Open set, VCU- and stake-weighted | KUMO becomes transferable after legal review |

Anchoring to Bitcoin gives miners a reason to trust phase 1: the history
they're paid from is committed to the chain they already secure.
`ledger-violations` in `:infer-credits` becomes the per-block validity check
that every federation member runs.

### 10. Legal boundaries

- No sale, no insider allocation, no buyback. KUMO is distributed only for
  verified work: the block subsidy, and the miner allocation that unlocks only
  against delivered capacity (ADR-261010c).
- KUMO stays **non-transferable** until counsel clears Japan's Payment Services
  Act (crypto-asset classification, exchange listing) and FIEA
  (security-token risk), plus each target jurisdiction.
- Credits are prepaid instruments. Unused-balance thresholds under the Payment
  Services Act decide when registration and deposit obligations apply.
- The treasury share is time-limited and published per block, so it is never
  an undisclosed allocation.

### 11. Parameters and governance

| Parameter | Launch value | Hard bounds | Changed by |
|---|---|---|---|
| Hard cap, block cadence, halving heights | 2.1B, one per Bitcoin block, Bitcoin halving heights | **Immutable** | — |
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

- `infer_credits_core` grows a block-settlement surface: `subsidy-at`,
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

- `price_ref` before KUMO is transferable: who publishes the accounting price,
  and on what basis.
- Contents and licensing of the public-goods queue.
- Federation membership criteria for phase 2.
- Whether the operator soft cap should apply per legal entity or per stake key.
