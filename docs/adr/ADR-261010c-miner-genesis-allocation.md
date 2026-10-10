# ADR-261010c: Miner Genesis Allocation — reserved KUMO for Bitcoin miners

- Status: proposed (5% size and Bitcoin-aligned halvings accepted 2026-10-10)
- Date: 2026-10-10
- Amends: ADR-261010b §2 (supply) and §10 (no premine → no insider allocation)
- Depends: ADR-261010 (Proof of Inference, onprem tiers), ADR-261010b (KUMO)

## Context

murakumo wants Bitcoin miners to commit power early. A subsidy that starts at
genesis rewards whoever shows up. It gives a miner no reason to sign a power
pledge today, and no stake to skip the probation tier on day one.

Miners trust Bitcoin partly because it had no premine. An allocation therefore
has to satisfy four conditions, or it costs more trust than it buys:

1. **Nothing goes to the team, investors or the treasury.** The only
   pre-allocation goes to people who bring power to the network.
2. **It comes out of the existing 2.1B cap.** It is never new supply.
3. **It is earned by delivered capacity, not by past hashrate alone.** Past
   hashrate decides who may reserve and how much. Only murakumo capacity
   actually delivered unlocks it.
4. **Unclaimed reservations return to the subsidy**, never to the treasury.

## Decision

### 1. Size and source

**Miner Genesis Allocation (MGA): 5% of the cap = 105,000,000 KUMO**, carved
out of the 2.1B cap. The block subsidy covers the remaining 1.995B
(ADR-261010b §2 is amended accordingly; era 0 per-block subsidy follows §9).

For scale, era 0 of the subsidy emits about 997.5M KUMO. The whole MGA is
about a tenth of that.

### 2. Three tranches

| Tranche | Share of cap | KUMO | For | Earned by |
|---|---|---|---|---|
| **A. Hashrate reservation** | 2.5% | 52.5M | Miners with proven Bitcoin hashrate | Pledging MW to murakumo and delivering it |
| **B. Season 0 work** | 1.5% | 31.5M | Any node operator, miners included | VCU delivered before genesis on the credits economy |
| **C. Pool program** | 1.0% | 21.0M | Hashers inside Bitcoin mining pools | Opting in through a partner pool and delivering capacity |

Tranche C exists because most hashrate sits in pools. Small miners can't prove
hashrate on their own, but a pool can attest for them.

### 3. Tranche A: hashrate reservation

**Proof of hashrate.** A miner proves trailing 12-month average hashrate with
one of:

- Message signatures (BIP-322) from addresses that received coinbase outputs
  or pool payouts
- Pool attestation of the account's hashrate history, signed by the pool
- For listed and large private miners, published production reports plus
  site attestation

The 12-month average defeats short-term rented hashrate.

**Weighting.** Weights favour smaller miners without ignoring scale. With `h`
in PH/s (12-month average):

```
w(h) = h                       for h ≤ 50 PH/s   (≈ 1 MW at 20 J/TH)
w(h) = 50 + √(50 · (h − 50))   for h > 50 PH/s
```

No single entity may reserve more than **5% of tranche A**. Related entities
are aggregated under one stake key and one KYC record.

**Reservation.** `R_i = 52.5M × w_i / Σw`, computed once at the close of the
registration window. Each reservation comes with a **power pledge**: MW the
miner commits to murakumo (tier A host, B onprem or C hybrid) and a delivery
deadline (genesis + 12 months).

**Unlock.** The reservation unlocks only against delivered capacity:

- **10%** when the first onprem unit or hosted MW passes capacity challenges
  (ADR-261010b §5)
- **90%** linearly over 24 months, and each month's tranche pays only if the
  miner's delivered capacity that month meets at least 80% of the pledge and
  passes verification

A miner who delivers less unlocks proportionally less. Undelivered reservation
returns to the block subsidy (§7).

### 4. Tranche B: Season 0

Season 0 runs from the opening of the credits fee economy until genesis. Every
VCU delivered and verified in Season 0 earns **genesis points**. At genesis,
`31.5M × points_i / Σpoints` KUMO is credited to each node. It vests over 12
months, and vesting continues only while the node stays active.

Season 0 runs before any token exists, so its fees and PPS payouts are real
money from day one. The points are the only forward-looking element.

### 5. Tranche C: pool program

A partner Bitcoin pool adds a murakumo opt-in for its hashers. The pool attests
each opted-in hasher's 12-month hashrate. That hasher then reserves from
tranche C under the tranche A rules, with tranche A's weighting and the same
unlock-by-delivery terms. The pool receives no KUMO. Its incentive is retaining
hashers and offering a second revenue line. This keeps the program from
becoming a payment to pool operators.

### 6. Unlocked KUMO is stake first

Unlocked MGA KUMO **auto-locks as stake** (ADR-261010b §8) until the node's
capacity stake requirement is met. Only the excess becomes free balance. This
is the functional reason the MGA exists: a pledged miner arrives at genesis
already staked and skips probation (`p = 20%` → established tier), so its first
MW earn at full rate.

Slashing applies to MGA-funded stake the same way it applies to any other stake.

### 7. Where unclaimed KUMO goes

Undelivered tranche A/C reservations, forfeited vesting and unregistered
remainders return to the **block subsidy**. They are spread evenly over the
remaining blocks of the current era. They never go to the treasury, and never
to other MGA holders. Everyone who mines afterward shares them.

### 8. Transferability and legal

- MGA KUMO is non-transferable until the same legal gate as all KUMO
  (ADR-261010b §10).
- It is never sold, and no payment is accepted for a reservation.
- Reservations above a threshold (e.g. 10 MW pledged) require KYC and
  sanctions screening, because miners operate across many jurisdictions.
- Reservations are published per entity (stake key, reserved, unlocked,
  returned) in the block log.

### 9. Genesis timing: halvings on Bitcoin's halving blocks

**Decision (2026-10-10): KUMO halves on the same blocks as Bitcoin.**

- One KUMO settlement block per Bitcoin block. ADR-261010b §9 already anchors
  each settlement block to Bitcoin.
- KUMO halvings happen at Bitcoin heights **1,050,000 (≈ April 2028),
  1,260,000 (≈ 2032), 1,470,000 (≈ 2036), …**
- Era 0 runs from genesis to the first Bitcoin halving height after genesis.
  Its per-block subsidy is fixed at genesis so the subsidy still totals 1.995B:

  ```
  R0 = 1,995,000,000 / (L0 + 210,000)       L0 = next_btc_halving_height − genesis_height
  ```

  For example, genesis at about BTC height 1,013,000 (mid-2027) gives
  L0 ≈ 37,000 and R0 ≈ 8,077 KUMO per block, which halves to ≈ 4,038 at block
  1,050,000. If genesis slips past 1,050,000, era 0 runs to 1,260,000 instead.

Miners plan capex, financing and treasury around one calendar, and the pitch is
simple: "KUMO halves on the same block as Bitcoin."

The accepted trade-off is that a miner's BTC subsidy and KUMO subsidy drop on
the same day. The capacity pool (ADR-261010b §4) and fee growth are what
cushion that drop. The rejected alternative was a mid-cycle schedule (halvings
at 1,155,000, 1,365,000, …).

Season 0 does not depend on genesis. It starts as soon as the credits fee
economy is open.

### 10. Launch partner pools for tranche C (researched 2026-10-10)

**Selection criteria:**

1. Many small or independent hashers (not a captive single-company pool)
2. Per-account hashrate history that the pool can sign as an attestation
3. Existing interest in AI/HPC diversification
4. A jurisdiction and compliance posture compatible with §8
5. Protocol or firmware hooks for an opt-in (Stratum V2, firmware)
6. Community tolerance for a token

| Priority | Pool | Why | Caution |
|---|---|---|---|
| 1 | **Luxor** (US) | Already sources GPUs and AI servers for miners and offers financing and hedging against hashrate. Runs the Tenki compute marketplace and publishes Hashrate Index, a natural publisher for the inference hashprice. Named by SBI Crypto as a migration target | Tenki overlaps with murakumo. Position murakumo as the inference workload and onprem hardware inside Luxor's sourcing, not as a rival marketplace |
| 1 | **Braiins Pool** (CZ) | Originated Stratum V2, and Braiins OS has a large installed base among small miners, so an opt-in can live in firmware. Home-mining hardware (Forge) matches onprem V/S in homes. Named by SBI Crypto as a migration target | Smaller share (~3%, sources vary) |
| 1 | **Former SBI Crypto hashers** (JP) | SBI Crypto closed its pool on 2026-07-31, displacing ~21 EH/s (~2.2%). It was a Japanese base that murakumo can reach directly. They moved mostly to Braiins, Luxor and NeoPool, so reach them through those pools plus direct outreach | No pool to partner with. SBI Holdings (which is acquiring bitbank) is a separate, later conversation about KUMO's legal and exchange path |
| 2 | **OCEAN** (US) | Non-custodial TIDES payouts; its decentralisation-minded community fits the no-insider-allocation design | Parts of this community are hostile to tokens. Lead with the fee economy |
| 2 | **DEMAND** (SV2-native) | Small and new, so a partnership is easier and the opt-in fits SV2 Job Declaration | Very small share. Confirm the official domain before integrating |
| 2 | **NeoPool** (Seychelles/Dubai) | ~17 EH/s, growing, and explicitly trying to close the gap on bigger pools, so it is motivated to differentiate | Offshore jurisdiction and mostly promotional coverage. Do full compliance diligence first |
| 3 | **Foundry USA** (~30%) | Institutional KYC onboarding makes attestation easy | Its clients are mostly large. Approach them directly under tranche A instead |
| 3 | **AntPool, ViaBTC, F2Pool** | Huge reach to small miners in Asia and Russia | Sanctions and jurisdiction review, especially for Russia-heavy hashrate. Approach after the pilot |

Captive pools (for example MARA Pool) are tranche A conversations, not
tranche C.

**Recommended pilot:** Luxor and Braiins, with Japanese outreach to former SBI
Crypto hashers through both.

Per §5, pools receive no KUMO. A partner pool may instead earn a **fiat
referral fee** on onprem hardware sales and hosting it originates. That gives
the pool a revenue line without turning the allocation into a payment to pool
operators.

Sources: Hashrate Index (Top 10 pools 2026, Q1 2026 heatmap), CryptoSlate
(pool consolidation, June 2026), The Block (Luxor GPU expansion, 2025-12),
Cointelegraph / Crypto Briefing (SBI Crypto shutdown, 2026-07), D-Central and
Spark (SV2/DATUM status, 2026), bitcoinminingstock.io (NeoPool).

## Consequences

- ADR-261010b §2 changes: the subsidy totals 1.995B, and the halvings sit on
  Bitcoin's halving blocks (§9). §10's "no premine" becomes "no insider
  allocation; 5% earned miner allocation".
- New services are needed: a hashrate-proof verifier (BIP-322, pool
  attestations), a registration window, a pledge tracker against capacity
  challenges, and genesis-points accounting in Season 0.
- `infer_credits_core` gains `mga-weight`, `mga-unlock` and `mga-return`,
  pure and parity-tested.

## Non-goals

- Any allocation to the team, investors, advisors or the treasury.
- Rewarding past hashrate without delivered murakumo capacity.
- Selling reservations, or accepting payment for priority.

## Open decisions

- The registration window length and the KYC threshold.
- Pilot terms with Luxor and Braiins (attestation format, opt-in surface,
  referral fee).
