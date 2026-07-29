# ADR-260728: W6 identity JWT payload fragments pure oracle expansion

Status: accepted after overlay stream type tokens pure (#187)

## Decision

Expand `kotoba/identity_core.kotoba` with residual **JWT payload fragments** and
dual-source header/sig/cid/graph tokens on `murakumo.identity`:

| export | role |
|---|---|
| `jwt-payload-sub-prefix` | `{"sub":"` |
| `jwt-payload-exp-mid` | `","exp":` |
| `jwt-payload-exp-val` | sentinel `9999999999` |
| `jwt-payload-close` | `}` |
| `jwt-header-json` / `op-token-sig-seg` | host dual-source const |
| `cid-b-prefix` / `graph-name-fleet` | host dual-source const |

`jwt-payload-json` recomposes from fragments. SHA-256 / b64url stay host.

## Evidence

- regenerated `identity_core.kir.edn`
- identity parity + authority green

## Related

- ADR-260728-w6-identity-seps-pure-oracle
- murakumo#187 overlay stream type tokens pure
