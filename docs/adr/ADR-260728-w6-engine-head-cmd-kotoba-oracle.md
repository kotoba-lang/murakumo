# ADR-260728: W6 pure-planner oracle — engine head-cmd string assembly

Status: accepted after mlx-moe/launch/tensor-split (#70)

## Decision

Extend `infer_engine_core.kotoba` with pure fragments of `head-cmd`:

| export | role |
|---|---|
| `head-cmd-front` | `bin/llama-server -m model` |
| `rpc-csv-2` / `rpc-csv-3` | join endpoints |
| `head-cmd-middle` | `--rpc` + `--split-mode` + `--tensor-split` |
| `head-cmd-tail` | `-ngl/-c/--parallel/--host/--port` |

Parity joins front+middle+tail against full cljc `head-cmd` for fixed 2-worker rings.

### Not ported

- plan-walking workers / assignment vectors
- `moe-override` pr-str quoting
- extra-args join

## Evidence

- `test/murakumo/infer_engine_kotoba_parity_test.clj` (9 tests / 32 assertions)
