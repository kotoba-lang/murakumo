# ADR: T5.3 (rebalance) — pool-demand, class-demand, seat-order become records

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after seats (#193) and task assign (#203)
- Superproject: ADR-2607299400 P2

## Context

After seats became a record, rebalance still packed:

- pool weights as base-65536 `pack3` (`pool-demand-pack`)
- five class counts as base-4096 (`demand-pack5` / `demand-inc`)
- seat-order permutation as base-4 (`seat-order-pack`)

These were the last base-N packs in murakumo pure-planner oracles.

## Decision

```
:rebalance/lanes  — pool weights (reuse seats shape: text/media/postproc)
:rebalance/demand — class counts text/image/video/audio/postproc
:rebalance/order  — pool-code permutation i0/i1/i2
```

`lane-base` / `pack3` / `lane-*` / `demand-base` deleted. Host product path
still builds demand maps in cljc.

## Evidence

- rebalance parity: 10 tests / 91 assertions / 0 failures
- KIR regenerated; live == resource
