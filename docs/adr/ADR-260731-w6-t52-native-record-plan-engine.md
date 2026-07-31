# ADR-260731: T5.2 native guest record wire — plan + engine inputs

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#277–#279 (eligibility/token/credits/rebalance pilots)
- WBS: T5.2 native guest record wire expansion

## Decision

Third product pilot of single-arg guest records for multi-scalar pure inputs:

| Module | Export | Input record |
|--------|--------|--------------|
| `infer-plan-core` | `usable-bytes`, `usable-gib-milli` | `:plan/node-cap` (mem, os, head, wired option) |
| `infer-plan-core` | `choose-strategy-name` | `:plan/strategy-in` (link-gbps, ranks, experts, kv-heads) |
| `infer-engine-core` | `endpoint` | `:engine/endpoint` (host, port) |
| `infer-engine-core` | `rpc-server-cmd` | `:engine/rpc-server` (bin-dir, port, device, cache, cache-dir) |
| `infer-engine-core` | `head-cmd-front/middle/tail` | `:engine/head-front|middle|tail` |

Host builds `oracle/record` (with `[:option :i64]` field projection) and
`call-record` `[:… :raw]`.

## Non-claims

- embed/mlx/opt-flag cmd fragments stay multi-arg for now.
- CLI report lines stay positional.
- Nested EDN still W4-gated.

## Evidence

plan/engine parity + unit + call-record focused suites green; KIR regen for
`infer_plan_core` + `infer_engine_core`.
