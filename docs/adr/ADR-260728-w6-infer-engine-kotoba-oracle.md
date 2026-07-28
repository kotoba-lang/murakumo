# ADR-260728: W6 pure-planner oracle — infer engine cmd assembly core

Status: accepted sixth cutover slice of `murakumo-pure-planners-v1`

## Decision

Port the pure string/cmd assembly core of `murakumo.infer.engine` to
`kotoba/infer_engine_core.kotoba`:

| function | notes |
|---|---|
| `default-rpc-port` | 50052 |
| `i64-str` / digit helpers | port/ctx embedding in cmds |
| `split-mode-name` | tensor → row, else layer |
| `endpoint` | host:port element of rpc-endpoints |
| `rpc-server-cmd` | single-worker rpc-server argv string |
| `embed-head-front` / `embed-head-back` | embed server cmd (ABI max arity 5; join for full cmd) |

### Not ported

- `workers` / `tensor-split` / `head-cmd` over plan assignments (vector reduce)
- `mlx-hosts` / `mlx-launch-cmd` / full `mlx-moe-cmd` optional flags
- `commands` dispatcher

## Evidence

- `test/murakumo/infer_engine_kotoba_parity_test.clj`
- Equality against `murakumo.infer.engine` offline unit corpus

## Related

- murakumo#37–#41
- Completes high-priority candidates of `murakumo-pure-planners-v1`
- `lang/w6-murakumo-path-inventory.edn` first-cutover-slice
