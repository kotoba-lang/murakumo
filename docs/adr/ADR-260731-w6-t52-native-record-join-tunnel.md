# ADR-260731: T5.2 native guest record wire — join + tunnel

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through relay/persist/moe (#282 tip)
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs on join and tunnel into single guest records:

| Module | Export | Schema | Fields |
|--------|--------|--------|--------|
| `infer_join_core` | `can?` | `:join/can` | tier, kind |
| `infer_join_core` | `needs-relay?` | `:join/relay` | tier, inbound |
| `infer_join_core` | `clamp-resident` | `:join/clamp` | mem `[:option :i64]`, tmax |
| `infer_join_core` | `eligible-for-work?` | `:join/work` | can-kind, max-res, res |
| `tunnel_core` | `scp-dest` | `:tunnel/scp` | host, dest |
| `tunnel_core` | `forward-spec` | `:tunnel/ports` | local-port, remote-port |
| `tunnel_core` | `ensure-forward-command`, `replace-forward-command` | `:tunnel/forward` | local-port, remote-port, host |
| `tunnel_core` | `pick-exit` | `:tunnel/exit` | has-rc, rc, ssh-exit |

Host builds `oracle/record` + `call-record` `:raw`.

`clamp-resident` keeps Profile-5 `[:option :i64]` for mem inside the record
(same pattern as `infer_plan` node-cap wired field).

## Non-claims

- Single-arg residual (tier max-resident, wrap-cmd, remote-curl, constants) stay scalar.
- Multi-arg option-only residual elsewhere (token claim-exp, etc.) still positional.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for join + tunnel only
- Focused parity + authority + call-record + unit tests green
