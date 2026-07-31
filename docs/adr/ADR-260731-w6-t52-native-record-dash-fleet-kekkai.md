# ADR-260731: T5.2 native guest record wire — dash + fleet + kekkai

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through report-core
- WBS: T5.2 native guest record wire expansion

## Decision

Fold remaining multi-scalar pure inputs into single guest records:

### dash_state_core

| Export | Schema | Fields |
|--------|--------|--------|
| `join-append` | `:dash/join` | acc, sep, next |
| `hosted-append` | `:dash/hosted` | acc, next |
| `clamp-at` | `:dash/clamp` | requested-at, history-count |
| `take-last-start` / `append-new-len` / `cap-count` / `recent-take-n` | `:dash/pair-i64` | a, b |

### fleet_inventory_core

| Export | Schema | Fields |
|--------|--------|--------|
| `selector-wants-name?` | `:fleet/selector-name` | sel, name |

### kekkai_gate_core

| Export | Schema | Fields |
|--------|--------|--------|
| `denial-line-of` | `:kekkai/denial` | name, status |

Host builds `oracle/record` + `call-record` `:raw`.

## Non-claims

- Single-arg residual stay scalar.
- `resolve-port` stays positional (option residual).
- Internal digit scanners stay multi-arg.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for dash_state / fleet_inventory / kekkai_gate only
- Focused parity + authority green
