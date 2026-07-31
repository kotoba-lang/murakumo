# ADR-260731: T5.2 native guest record wire — config + connect

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through join/tunnel
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs on config and connect into single guest records:

| Module | Export | Schema | Fields |
|--------|--------|--------|--------|
| `config_core` | `kotoba-dir-from` | `:config/kotoba-dir` | override, home |
| `config_core` | `kotoba-bin` | `:config/kotoba-bin` | user-dir, pinned-exists |
| `config_core` | `resolve-local-bin` | `:config/local-bin` | user-dir, kotoba-dir, pinned-exists, murakumo-bin |
| `config_core` | `resolve-wit-dir` | `:config/wit-dir` | user-dir, kotoba-dir, pinned-wit-exists |
| `connect_core` | `node-class-name` | `:connect/node-class` | node-class, default-class |
| `connect_core` | `serves-plane?` | `:connect/plane` | plane, http?, common? |

Host builds `oracle/record` + `call-record` `:raw`.

## Non-claims

- Single-arg residual (default-class-name, path suffixes, single-path builders) stay scalar.
- Multi-arg option residual (token claim-exp, etc.) still positional.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for config + connect only
- Focused parity + authority + call-record + unit tests green
