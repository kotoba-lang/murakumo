# ADR-260728: W6 task unschedulable seps pure oracle expansion

Status: accepted after report CSV pure (#165)

## Decision

Expand `kotoba/task_plan_core.kotoba` with residual **unschedulable-detail
join/prefix fragments**, dual-sourced on `murakumo.task.plan`:

| export | role |
|---|---|
| `exclude-join-sep` | `,` between exclude-nodes names |
| `unsched-placement-prefix` | `no node satisfies placement=` |
| `unsched-excluding-prefix` | ` excluding=` |
| `unsched-min-mem-prefix` | ` min-mem-bytes=` |

`unschedulable-detail` recomposes those prefixes. Host `why-unschedulable`
dual-sources the exclude CSV join; pr-str projection stays host.

## Evidence

- regenerated `task_plan_core.kir.edn`
- task unit/parity + authority green

## Related

- ADR-260728-w6-task-oracle-authority
- murakumo#165 report CSV pure
