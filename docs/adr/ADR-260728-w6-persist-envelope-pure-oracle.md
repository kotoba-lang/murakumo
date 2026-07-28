# ADR-260728: W6 persist envelope/curl pure oracle expansion

Status: accepted after reconcile flags pure (#140)

## Decision

Expand `kotoba/persist_core.kotoba` with residual pure field helpers for
repo.write envelopes and curl argv assembly:

| export | role |
|---|---|
| `operation-create` | envelope `:operation` |
| `write-ok-marker` | write-ok? substring SSoT |
| `auth-bearer-prefix` / `auth-header` | Authorization header |
| `content-type-json-header` | content-type header |
| `curl-timeout-s` / `curl-method-post` | curl `-m` / `-X` |
| `xrpc-repo-write-path` | shared path for repo-write-url |

Host dual-source via `:persist`. Graph-cid hashing and envelope map assembly
stay host.

## Evidence

- regenerated `persist_core.kir.edn`
- persist unit + kotoba parity + authority green

## Related

- murakumo#109 persist oracle authority
- murakumo#140 reconcile flags pure
