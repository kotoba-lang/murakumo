# ADR-260728-w6-task-unsched-pure-oracle

- Status: Accepted
- Date: 2026-07-28

## Context

`murakumo.task.plan` already dual-sources slots/eligible/wave/task-id via
`task_plan_core`. The reject detail string (`why-unschedulable`) remained host-only
string assembly with `pr-str` / join.

## Decision

Port pure composition into `unschedulable-detail`:

```
"no node satisfies placement=" + placement
  [+ " excluding=" + excluding]
  [+ " min-mem-bytes=" + min-mem-str]
```

Host projects:

- placement → `(pr-str (:placement task))`
- excluding → joined exclude names or `""`
- min-mem-str → `(str min-mem-bytes)` or `""`

## Evidence

- `kotoba/task_plan_core.kotoba` + regenerated KIR
- parity + authority call-match

## Related

- ADR-260728-w6-task-oracle-authority
