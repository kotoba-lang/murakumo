# ADR: T5.3 (task) — assign pack families become named records

- Status: Accepted
- Date: 2026-07-30
- WBS: T5.3 residual after reconcile targets (murakumo#201/#202)
- Superproject: ADR-2607299400 P2

## Context

`task_plan_core` used base-65536 packing for:

- 2-node load/fill pairs and `assign-task-step-2` result
- 3-node ok/fill/load triples and `assign-task-step-3` encoded as
  `pick-code + 4 * pack3(loads)`

Same forbidden pattern as the pre-#199 schedule assign packs. Host product
path sorts/picks in cljc and never called these APIs; parity owned them.

## Decision

```
:task/pair    [[:a :i64] [:b :i64]]
:task/triple  [[:v0 :i64] [:v1 :i64] [:v2 :i64]]
:task/assign2 [[:code :i64] [:load0 :i64] [:load1 :i64]]
:task/assign3 [[:code :i64] [:load0 :i64] [:load1 :i64] [:load2 :i64]]
```

Constructors `pair-record` / `triple-record`; field projections
`assign-task-2-*` / `assign-task-3-*`. No base-65536 remains in the module
outside comments.

## Evidence

- task parity: 10 tests / 101 assertions / 0 failures
- KIR regenerated; live == resource
