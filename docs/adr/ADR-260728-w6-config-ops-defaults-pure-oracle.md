# ADR-260728: W6 config ops defaults pure oracle expansion

Status: accepted after cert-kagi-config-inject (#147)

## Decision

Expand `kotoba/config_core.kotoba` with residual **ops config default
URL/string constants** (exact-name getenv leave values), dual-sourced on
`murakumo.config`:

| export | value |
|---|---|
| `default-cloud-url` | `https://api.murakumo.cloud` |
| `default-api-url` | same as cloud-url |
| `default-text-backend-url` | `http://localhost:11434` |
| `default-image-checkpoint` | `animagine-xl-4.0.safetensors` |
| `default-infer-local-url` | `http://localhost:11434/v1` |
| `default-kotoba-cli-bin` | bare `kotoba` |

Host uses `oracle-const` for load-time dual-source; inject `getenv` path
unchanged (fn/map). Env name list (`ops-config-keys`) and process
`System/getenv` 0-arity remain host.

### Still host

| concern | why |
|---|---|
| exact-name `getenv` inject | process ambient / test map |
| `ops-config-keys` vector | host key registry for env-values fold |
| EDN / path probes | filesystem I/O |

## Evidence

- regenerated `config_core.kir.edn`
- config unit inject + kotoba parity + authority call-match green

## Related

- ADR-260728-w6-ops-config-inject
- ADR-260728-w6-cert-kagi-config-inject
- ADR-260728-w6-tunnel-config-oracle-authority
