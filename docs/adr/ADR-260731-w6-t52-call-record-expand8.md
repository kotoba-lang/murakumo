# ADR-260731: T5.2 call-record expand wave 8

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#271 (waves 1–7)
- WBS: T5.2 remainder — positional projection expansion

## Decision

Expand `oracle/call-record` after wave 7 (#271 kekkai/driver/runtime):

| Host | Export |
|------|--------|
| `task.plan` | `slots`, `unschedulable-detail`, `failed?`, `can-retry?` |
| `infer.moe` | `expert-ratio-milli`, `verdict-name`, `resident-est` |
| `infer.join` | `can?`, `needs-relay?`, `clamp-resident`, `eligible-for-work?` |
| `infer.gc` | `rank-better?`, `need-bytes`, `comfy-evictable?`, `free-after`, `target-met?` |
| `overlay.driver` residual | `dial-ok-reason` |

Each ns keeps private `o-record` (`require-ready!` + `call-record`). Guest
export signatures stay positional scalars; only host projection changes.

## Non-claims

- Guest export signatures unchanged.
- Native guest `[:record …]` wire remains T5.3 path.
- T8.3 nested kit EDN codec remains W4-gated.
- Does not supersede expand7 ADR (kekkai/driver/runtime #271).

## Evidence

- `oracle-call-record-test` + task/moe/join/gc/driver focused suites green
