# ADR-260728: W6 connect class/plane tokens pure oracle expansion

Status: accepted after fleet inventory selector/URL tokens pure (#180)

## Decision

Expand `kotoba/connect_core.kotoba` with residual **class / plane name tokens**,
dual-sourced on `murakumo.connect`:

| export | role |
|---|---|
| `class-native` | default node class `"native"` |
| `plane-read` | read plane name `"read"` |
| `plane-live` | live plane name `"live"` |

`default-class-name` / `serves-plane?` recompose from those tokens. Host
`default-class` / `serves-reach?` mirrors and plane projections dual-source the
same tokens. class-transports set intersection stays host.

## Evidence

- regenerated `connect_core.kir.edn`
- connect parity + authority green

## Related

- ADR-260728-w6-connect-cloud-provision-kotoba-oracle
- murakumo#180 fleet inventory selector/URL tokens pure
