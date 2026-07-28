# ADR-260728: W6 pure-planner oracle — tunnel + report string cores

Status: accepted ops-shell pure contract slice (SSH remains host-forever)

## Decision

| artifact | notes |
|---|---|
| `kotoba/tunnel_core.kotoba` | wrap-cmd, forward/curl commands, rc marker helpers |
| `kotoba/report_core.kotoba` | constant lines, simple formatters, command-error-line |

SSH execution stays host-forever; pure argv/shell contract is still oracle-eligible so bb/nbb share one dialect.

## Evidence

- `test/murakumo/tunnel_kotoba_parity_test.clj`
- `test/murakumo/report_kotoba_parity_test.clj`
