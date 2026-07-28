# ADR-260728: W6 pure-planner oracle — overlay peer + driver scalars

Status: accepted overlay pure cutover extension

## Decision

| artifact | notes |
|---|---|
| `overlay_peer_core.kotoba` | choose-via path preference (host-projected flags) |
| `overlay_driver_core.kotoba` | endpoint-kind (driver.cljc), blank?, dial-ok-reason |

catalog/remember maps and parse-argv loop stay cljc.

## Evidence

- `test/murakumo/overlay_peer_kotoba_parity_test.clj`
- `test/murakumo/overlay_driver_kotoba_parity_test.clj`
