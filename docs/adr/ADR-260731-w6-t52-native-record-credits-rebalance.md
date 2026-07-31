# ADR-260731: T5.2 native guest record wire — credits + rebalance inputs

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#277 (eligibility pilot), T5.2 call-record close-out
- WBS: T5.2 native guest record wire expansion

## Decision

Second product pilot of **single-arg guest records** for multi-scalar pure
inputs (same pattern as eligibility free/min fold-in):

| Module | Export | Input record |
|--------|--------|--------------|
| `infer-credits-core` | `memory-time-weight` | `:credits/mt-work` (est-bytes, duration-ms, span) |
| `infer-credits-core` | `charge-allow?`, `balance-after-spend` | `:credits/charge` (balance, cost) |
| `infer-rebalance-core` | `seats-of-*`, `seats-total` (public) | `:rebalance/seats-in` (total, text-w, media-w, postproc-w, floor); internal `seats-record` stays scalar |

Host builds `oracle/record` once and projects via `call-record` `[:in :raw]`
(or `[:work :raw]` / `[:charge :raw]`). Internal `pool-seats-of-*` builds
`seats-in` via `seats-in-from-lanes` from lanes record + total/floor.

## Non-claims

- CLI/report string builders stay multi-arg positional.
- Nested EDN codec still W4-gated.
- Does not claim all multi-arg pure exports converted.

## Evidence

credits/rebalance parity + focused authority + unit suites green; KIR regen for
`infer_credits_core` + `infer_rebalance_core` only.
