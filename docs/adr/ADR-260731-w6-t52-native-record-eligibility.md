# ADR-260731: T5.2 native guest record wire — eligibility single-arg

- Status: accepted
- Date: 2026-07-31
- Depends: T5.3 eligibility records (murakumo#205/#206), T5.2 call-record waves #261–#276
- WBS: T5.2 remainder — **native guest record parameter wire** (first product pilot)

## Decision

Fold remaining scalar free/min arguments into the eligibility **record
parameter** so `eligible?` / `task-eligible?` take a **single** guest
argument — a named record — instead of `(record free min)`.

| Guest export | Before | After |
|--------------|--------|-------|
| `infer-schedule-core/eligible?` | `(e free min)` | `(e)` with `:free-bytes` / `:min-free` on `e` |
| `task-plan-core/task-eligible?` | `(e mem min)` | `(e)` with `:mem-bytes` / `:min-mem` on `e` |

Host builds one `oracle/record` and calls via `call-record` with a single
`:raw` field (no separate i64 projection).

This is the T5.2 remainder path called out after call-record positional
close-out (wave 12): **native guest record wire** for product map
boundaries that were still multi-arg only to carry free/min scalars.

## Non-claims

- Does not convert all multi-arg pure exports to records (tokens/CLI lines stay positional).
- `holds-warm?` still takes eligibility record (now with unused free/min fields — fine).
- T8.3 nested EDN still W4-gated.

## Evidence

- schedule/task parity + schedule unit + oracle-call-record focused suites green
- KIR regenerated for `infer_schedule_core` + `task_plan_core` only
