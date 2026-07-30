# ADR-260731: report/join/reconcile label flags are profile-5 :bool

- Status: accepted
- Date: 2026-07-31
- Depends: compiler#451, murakumo#230 (flags3), #229 (bump ok-for)

## Decision

Convert residual **product-surface 0/1 i64 flags** that still force hosts to
project `(if x 1 0)` into real `:bool` parameters:

| Export | Module | Change |
|--------|--------|--------|
| `provision-result-line` | report | `peered :bool` |
| `online-label` / `ssh-label` | report | online/ok `:bool` |
| `nodes-row` | report | online + ssh-ok `:bool` |
| `artifact-node-status` | report | ok `:bool` |
| `cid-display` | report | present `:bool` |
| `action-detail` | report | running-empty `:bool` |
| `needs-relay?` | join | inbound `:bool` |
| `better-target?` / `first-of-2` / `pick-targets-2-record` | reconcile | name-before `:bool` |
| `:reconcile/name-order` | reconcile | n01/n02/n12 `:bool` fields |

Hosts pass Clojure booleans directly (no `as-i64` 0/1 projection).

## Still numeric by design

- Queue / load / fill / pick **codes** and seat indices
- `task-score-code` ternary ordinal
- plan/rebalance bump **indices** (which seat was chosen)
- Tier codes (0 browser / 1 wasm / 2 native)

## Evidence

- KIR regenerated for report / join / reconcile
- Focused parity + report/join/reconcile suites
