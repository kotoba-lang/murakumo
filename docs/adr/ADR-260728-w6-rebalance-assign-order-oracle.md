# ADR-260728: W6 rebalance seat-order assignment pure oracle

- Status: Accepted
- Date: 2026-07-28

## Context

#65 landed placement scalars (workers-count, move-needed, reason codes).
Node-id vectors remain host, but the **pool order** and **slice bounds** used
by target-allocation assignment are pure.

## Decision

Extend `infer_rebalance_core.kotoba`:

| function | notes |
|---|---|
| `seat-order-pack` / `order-nth` | sort pools by seats desc (tie → lower index) |
| `take-end` / `take-count` | pure `(take k rem)` bounds |
| `pipeline-note` | text-pool pipeline note string |
| `rebalance-reason-detail` | full demand-shift reason with move count |
| `pool-code` / `pool-name` | pool id ↔ string |

### Still cljc

Worker id vectors / concrete take+drop assignment / moves-between maps.

## Evidence

- `test/murakumo/infer_rebalance_kotoba_parity_test.clj`
