# ADR-260728: W6 pure-planner oracle — overlay keyring/stream/runtime scalars

Status: accepted low-priority overlay pure cutover slice

## Decision

| artifact | notes |
|---|---|
| `overlay_keyring_core.kotoba` | epoch + key preimages (hash stays host) |
| `overlay_stream_core.kotoba` | window, advance-seq, ack flag |
| `overlay_runtime_core.kotoba` | ports, adapter registry, endpoint-kind, host parse |

## Evidence

- `test/murakumo/overlay_keyring_kotoba_parity_test.clj`
- `test/murakumo/overlay_stream_kotoba_parity_test.clj`
- `test/murakumo/overlay_runtime_kotoba_parity_test.clj`
