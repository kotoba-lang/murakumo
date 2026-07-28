# ADR-260728: W6 component-authority op/event tokens pure oracle expansion

Status: accepted after kekkai denial/status tokens pure (#183)

## Decision

Expand `kotoba/component_authority_core.kotoba` with residual **op / event name
tokens**, dual-sourced on `murakumo.component-authority`:

| export | role |
|---|---|
| `op-place` / `op-revoke` / `op-unknown` | command op bare names |
| `event-placed` / `event-revoked` | event kind bare names |
| `format-v1` / `algorithm-ed25519` | host dual-source (was try-oracle only) |

`command-op` / `event-kind` recompose from those tokens. Host place/revoke pass
`op-place`/`op-revoke` into `event-kind`. Event map assembly + ed25519 stay host.

## Evidence

- regenerated `component_authority_core.kir.edn`
- component-authority parity + authority green

## Related

- ADR-260728-w6-component-authority-kotoba-oracle
- murakumo#183 kekkai denial/status tokens pure
