# ADR-260728: W6 pure-planner oracle — engine mlx-moe / launch / split strings

Status: accepted after high-priority engine cmd core (#42) + plan fits compose (#69)

## Decision

Extend `infer_engine_core.kotoba` with remaining pure string cores from
`murakumo.infer.engine`:

| export | role |
|---|---|
| `mlx-moe-bin` / `mlx-moe-front` | mlx-moe serve prefix (venv optional) |
| `opt-i64-flag` / `opt-str-flag` | optional CLI flag fragments (present 0/1) |
| `tensor-split-3` | fixed 3-span `--tensor-split` join |
| `mlx-launch-front` | mlx.launch ring cmd through `--max-tokens` |

Optional flags compose via `string-concat` in parity (ABI max arity 5).
`extra-args`, `pr-str` prompt quoting, and plan assignment walks stay cljc.

### Still cljc

- `workers` / full `tensor-split` / `head-cmd` over assignment vectors
- `mlx-hosts` JSON structure
- `commands` dispatcher / `extra-args` join

## Evidence

- `test/murakumo/infer_engine_kotoba_parity_test.clj`

## Related

- murakumo#42 first engine cmd oracle
- inventory Next: engine mlx-moe cmd string core
