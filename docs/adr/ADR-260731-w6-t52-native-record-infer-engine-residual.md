# ADR-260731: T5.2 native guest record wire — infer-engine residual

- Status: accepted
- Date: 2026-07-31
- Depends: murakumo native-record waves through option-i64 residual
- WBS: T5.2 native guest record wire expansion

## Decision

Fold remaining multi-scalar pure cmd builders on `infer_engine_core` into single guest records:

| Export | Schema | Fields |
|--------|--------|--------|
| `embed-head-front` | `:engine/embed-front` | bin-dir, model-path, pooling, ctx |
| `embed-head-back` | `:engine/embed-back` | parallel, port |
| `mlx-moe-front` | `:engine/mlx-moe` | venv, model-repo, port |
| `opt-i64-flag` | `:engine/opt-i64` | flag, value, present |
| `opt-str-flag` | `:engine/opt-str` | flag, value, present |
| `tensor-split-3` | `:engine/tensor-3` | s0, s1, s2 |
| `mlx-launch-front` | `:engine/mlx-launch` | venv, hosts-file, model-repo, max-tokens |
| `rpc-csv-2` | `:engine/rpc-csv-2` | ep0, ep1 |
| `rpc-csv-3` | `:engine/rpc-csv-3` | ep0, ep1, ep2 |

(Previous wave already covered endpoint / rpc-server / head-cmd-front|middle|tail.)

Host builds `oracle/record` + `call-record` `:raw`.

## Non-claims

- Single-arg residual (`mlx-moe-bin`, `split-mode-name`, `i64-str`) stay scalar.
- Variable-arity CSV join over N workers stays host (`str/join`).
- Prompt `pr-str` quoting stays host.
- Overlay/reconcile internal `starts-with?` multi-arg left for a later wave.
- T8.3 nested EDN still W4-gated.

## Evidence

- KIR regenerated for `infer_engine_core` only
- Focused infer-engine parity + authority green
