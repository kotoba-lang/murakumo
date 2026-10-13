# ADR-261010d: Home miners — join with the murakumo client on a home PC

- Status: proposed
- Date: 2026-10-10
- Depends: ADR-261010 (Proof of Inference), ADR-261010b (KUMO economy),
  ADR-261010c (Miner Genesis Allocation)
- Existing surface: `murakumo node init / doctor / check / join`,
  `release/install.sh`, relay overlay (`cloud.edn`), and the node's
  load-based job refusal

## Context

Tiers A–C in ADR-261010 assume a site with MW of power. Most Bitcoiners are not
site operators. They run a gaming PC, a Mac, a few Bitaxe-class home miners, or
a hashrate share in a pool. If buying an onprem server is the only way in,
they never join. That loses the community that made Bitcoin mining credible.

murakumo already runs on ordinary machines. A home PC with Ollama, llama.cpp,
MLX or ComfyUI can join today with `murakumo node join`. This ADR makes that
the first-class entry path for individuals and fits it into the KUMO economy.

## Decision

### 1. Three ways in for an individual

| Path | What the person does | Upfront cost | Time to first earnings |
|---|---|---|---|
| **H1. Home PC client** | Installs the murakumo client on a PC they already own | None | Same day |
| **H2. Home miner bridge** | H1, plus proves existing Bitcoin hashrate (home ASIC, Bitaxe, or a pool account) | None | Same day, with a Season 0 multiplier |
| **H3. onprem at home** | Buys an onprem V or S unit | One unit | After delivery |

H1 is the default. H3 is the upgrade path once a person sees their earnings.

### 2. H1: the home PC client

**Install.**

```
curl -fsSL https://murakumo.cloud/install.sh | sh     # existing release/install.sh
murakumo node init
murakumo node doctor          # detects GPU/unified memory, recommends a model pack
murakumo node join --name my-pc
```

A desktop app (built from murakumo-studio, Tauri) wraps the same steps for
people who don't use a terminal. It shows a start/stop switch, earnings, and
the electricity break-even.

**Model packs by hardware.** `doctor` picks one:

| Hardware | Pack | Typical jobs |
|---|---|---|
| Apple Silicon, 16–32 GB | text-s | 8–14B text, embeddings |
| Apple Silicon 64 GB+ / M5 Max | text-m, image | 30B-class MoE text, image generation |
| NVIDIA 12–16 GB (3060–4070 class) | image, text-s | Image generation, small text |
| NVIDIA 24–32 GB (3090/4090/5090 class) | video-s, image, text-m | Short low-resolution video (LTX / Wan 5B class), image, text |
| Strix Halo / 128 GB unified | text-l (distributed) | Large MoE text spanning nodes |

**Home-friendly operation.**

- **Idle-only mode** (default): take jobs only when the PC is idle. Stop
  within seconds when the user returns. This extends the node's existing
  rule of refusing new jobs above half-core CPU load to GPU and input activity.
- **Availability windows**: the node declares when it is usually on (for
  example, nights and weekends). Capacity challenges are issued only inside
  declared windows, so a home PC is never penalised for being off.
- **Profit switch**: the client compares expected earnings per hour with the
  user's electricity price (entered once, in ¥/kWh or $/kWh). It pauses when
  inference does not cover electricity. For H2 users it also compares against
  their hashing revenue, which works like NiceHash-style profitability
  switching.
- **NAT traversal**: home routers need no port forwarding. The node connects
  out through the existing relay overlay (jp-tyo-1, us-sjc-1; QUIC / WebRTC).

### 3. How home nodes earn

Home nodes are ordinary nodes under ADR-261010b. There is no separate economy.

- **Fees**: PPLNS by default, because home availability is irregular.
  Switching to PPS is allowed after 30 days of reliable windows.
- **Subsidy**: the customer pool for customer jobs, and the capacity pool for
  passing challenges inside declared windows.
- **Season 0 points** (ADR-261010c tranche B): home nodes earn genesis points
  like every other node. This is the main way an individual gets a share of
  the 5% allocation.

**Stake.** Home nodes start in the probation tier with `p = 20%` and a stake
requirement of zero. Each node's probation capacity cap is one consumer GPU or
one 128 GB unified-memory box. Matured subsidy auto-locks as stake. When
the requirement for the established tier is met, the node moves up
automatically. Nobody buys KUMO to start.

### 4. H2: the home miner bridge

A person who already mines Bitcoin at home or in a pool can prove it:

- BIP-322 signature from an address that received pool payouts (including
  Lightning-payout pools via a signed payout record), or
- Pool attestation through a tranche C partner pool (ADR-261010c §10)

Any 90-day average above 1 TH/s qualifies, so a single Bitaxe is enough. The
reward is a **Season 0 points multiplier of 1.5× for the first 6 months**,
applied only to verified VCU. It multiplies real work, not idle hashrate, so it
cannot be farmed without delivering inference. One proof binds to one
identity, and an identity's multiplier applies to at most 2 nodes.

The tranche A reservation weighting still applies for those who want it. At
home scale it is small, and the multiplier is the meaningful incentive.

### 5. Payouts for small balances

| Method | Minimum | Identity check | Status |
|---|---|---|---|
| murakumo credits (use murakumo's own services) | None | None | Launch |
| Bank transfer (JPY/USD) | ¥5,000 / $35 | KYC on first cash-out | Launch |
| Lightning (BTC) | 1,000 sats | KYC on first cash-out | After legal review |
| KUMO | Per block, matured after 144 blocks | Same as above | After KUMO's legal gate |

The client produces an annual earnings statement. Earnings are taxable
income, typically 雑所得 in Japan.

### 6. Customer data on home PCs

Home PCs cannot attest that the operator does not inspect jobs. So:

- Home nodes serve the **community tier**: customers who choose a lower price
  in exchange for running on unattested nodes. Home nodes also serve the
  public-goods queue, canaries and verification replays.
- Confidential customers are never placed on home nodes. They go to TEE or
  attested onprem tiers.
- Model servers run in a sandbox (container or app sandbox). The client never
  reads the user's files and only talks to the local model server and the
  relay.
- Transport uses X25519 + ML-KEM-768 (ADR-261010 §9).

### 7. Illustrative earnings (assumptions, not measurements)

An RTX 4090-class PC at home draws 0.45 kW at the wall and renders 25
output-seconds of 720p video per hour. It is busy 60% of the time it is on, and
the node receives $0.025 per output-second. Electricity is ¥31/kWh (≈ $0.21).

```
fees        25 × 0.6 × $0.025       = $0.375 / hour on
electricity 0.45 kWh × $0.21        = $0.095 / hour on
net                                  ≈ $0.28 / hour on
12 hours a day                       ≈ $100 / month, before subsidy and Season 0 points
```

On a 16 GB Mac doing small text jobs, earnings are much lower but so is the
power draw, so the profit switch decides.

## Consequences

- The client gains idle-only mode, availability windows, the profit switch and
  an earnings view. The desktop app wraps the CLI.
- Placement gains a community tier and keeps confidential jobs off unattested
  nodes.
- The hashrate-proof verifier from ADR-261010c is reused for H2.
- Payout rails: credits and bank transfer at launch; Lightning after legal
  review.

## Non-goals

- Running inference on Bitcoin ASICs or Bitaxe hardware.
- Paying for idle PCs that have not answered a challenge in their window.
- Routing confidential customer data to home PCs.

## Open questions

- The minimum hardware for joining (proposal: 16 GB unified memory or a
  12 GB GPU).
- Community-tier pricing relative to the attested tiers.
- Whether the H2 multiplier should taper by hashrate size.
