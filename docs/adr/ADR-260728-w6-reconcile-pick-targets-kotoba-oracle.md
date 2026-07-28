# ADR-260728: W6 pure-planner oracle — reconcile pick-targets

Status: accepted after action-name scalar core

## Decision

Extend `reconcile_plan_core.kotoba` with pure `pick-targets` for fixed 2/3
free candidates:

| export | role |
|---|---|
| `better-target?` / `first-of-2` / `first-of-3` | sort by [load, name] |
| `pick-targets-2-pack` | take n≤2 from 2 candidates (packed indices) |
| `pick-targets-3-first` | first of 3; host re-calls pair for n>1 |
| pack getters | first / second / count |

Name order projected by host (`name-a-before-b` / name-bits).

### Not ported

- eligible-nodes / observed-hosts set algebra
- variable-length candidate sort
- reconcile-app map assembly

## Evidence

- `test/murakumo/reconcile_plan_kotoba_parity_test.clj` (3 tests / 22 assertions)
