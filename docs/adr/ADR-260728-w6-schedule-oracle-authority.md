# ADR-260728: W6 product-shell oracle authority — infer.schedule pure path

Status: accepted after dash.state (#93) product-shell cutover

## Decision

Wire media job-scheduler pure helpers to kotoba SSoT:

| layer | role |
|---|---|
| `kotoba/infer_schedule_core.kotoba` | SSoT eligible?/score/queue-inc |
| `resources/murakumo/oracle/infer_schedule_core.kir.edn` | precompiled KIR |
| `murakumo.kotoba.oracle` catalog `:infer-schedule` | load + execute |
| JVM `eligible?` / `score` / assign queue inc | delegate to oracle |

### Host remains

- engines/checkpoints **set membership** → bit flags projection
- `pick` stable `sort-by score` (warm-first pool) — not tournament fold
  (tournament later-index on score ties differs from stable sort first-wins)
- assign atom map fold over jobs

## Evidence

- `kotoba_oracle_authority_test` schedule suite
- existing `infer_schedule_test` + parity

## Related

- inventory Next: expand catalog (schedule/task/engine)
- murakumo#86–#93 product-shell pattern
