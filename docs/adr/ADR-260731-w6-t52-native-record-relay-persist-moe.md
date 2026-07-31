# ADR-260731: T5.2 native guest record wire — relay + persist + moe

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through plan/engine (#280 tip)
- WBS: T5.2 native guest record wire expansion

## Decision

Fold multi-scalar pure inputs on relay, persist, and moe into single guest
records:

| Module | Export | Schema | Fields |
|--------|--------|--------|--------|
| `infer_relay_core` | `make-id` | `:relay/id` | prefix, n |
| `infer_relay_core` | `lease-expired?` | `:relay/lease` | now-ms, at-ms, ttl-ms |
| `persist_core` | `snapshot-rkey`, `reconcile-rkey` | `:persist/rkey` | millis, seq-n |
| `persist_core` | `repo-uri` | `:persist/uri` | collection, rkey |
| `infer_moe_core` | `expert-ratio-milli` | `:moe/ratio` | experts, active |
| `infer_moe_core` | `verdict-name` | `:moe/verdict` | experts, active, shared |
| `infer_moe_core` | `resident-est` | `:moe/resident` | weight-bytes, experts, capacity |

Host builds `oracle/record` + `call-record` `:raw`.

## Side fix

Authority live-execute for `infer-engine/head-cmd-front` still used pre-native
2-arg form after plan-engine wave; updated to `:engine/head-front` record.

## Non-claims

- Single-arg residual (`capacity-default`, `auth-header`, msg tokens) stay scalar.
- Option-bearing pure exports still deferred (compiler option-in-record gap).
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for relay + persist + moe only
- 96 tests / 1691 assertions green (parity + call-record + authority)
