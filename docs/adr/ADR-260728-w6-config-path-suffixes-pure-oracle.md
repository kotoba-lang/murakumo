# ADR-260728: W6 config path-suffix tokens pure oracle expansion

Status: accepted after overlay keyring seps pure (#185)

## Decision

Expand `kotoba/config_core.kotoba` with residual **path suffix tokens**,
dual-sourced on `murakumo.config`:

| export | role |
|---|---|
| `kotoba-dir-suffix` | default checkout under HOME |
| `bin-suffix` | `/bin` pinned bin dir |
| `release-bin-suffix` | cargo release bin dir |
| `wit-suffix` / `runtime-wit-suffix` | WIT dir suffixes |
| `kotoba-server-suffix` / `kotoba-cli-suffix` | binary path suffixes |
| `build-edn-suffix` | `/BUILD.edn` |

Path builders recompose from those tokens. `kotoba-bin` unpinned branch uses
`default-kotoba-cli-bin`. EDN/env/fs probes stay host.

## Evidence

- regenerated `config_core.kir.edn`
- config parity + authority green

## Related

- ADR-260728-w6-tunnel-config-oracle-authority
- murakumo#185 overlay keyring seps pure
