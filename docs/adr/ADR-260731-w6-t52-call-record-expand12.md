# ADR-260731: T5.2 call-record expand wave 12 (close-out)

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo#261–#275 (waves 1–11)
- WBS: T5.2 remainder — positional projection expansion **close-out**

## Decision

Convert **all remaining non-empty** host `oracle/call` / `o` arg vectors to
`oracle/call-record` (structural host map → positional guest projection):

| Host | Residual converted |
|------|--------------------|
| `cloud.plan` | 40 single-arg CLI title/line/flag helpers |
| `reconcile.plan` | 11 flag/action/watch helpers |
| `tunnel` | 10 ssh/scp option builders |
| `deploy.plan` | 10 path/url/probe helpers |
| `provision.plan` | 8 residual path/log helpers |
| `config` | 8 path builders |
| `dash.state` | 6 probe/parse helpers |
| `token` | 4 claim/signing residual |
| `fleet.inventory` | 3 residual |
| + others | component-authority, persist, identity, join, moe, crypto, driver |
| `infer.schedule` / `task.plan` | `eligible?` / `task-eligible?` with eligibility `oracle/record` as `:raw` + scalar free/min |

**After this wave, product-shell host boundaries with non-empty guest args use
`call-record` exclusively.** Zero-arg token/constants remain `oracle/call []`.

## Non-claims

- Guest export signatures unchanged (still positional scalars / record+scalars).
- Native guest `[:record …]` *parameter* wire is separate (exports already use
  T5.3 record values via `oracle/record` + `:raw` projection where needed).
- T8.3 nested kit EDN remains W4-gated.

## Evidence

- `oracle-call-record-test` + cloud/provision/tunnel/deploy/reconcile/config/
  dash/token/schedule/task suites green
