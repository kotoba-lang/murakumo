# murakumo bench

One directory per run under `results/`, with its raw files, a `summary.json`
from `summarize.py` and a README that names the host, its load, the engines'
versions and argv. Method: [docs/benchmarks.md](../docs/benchmarks.md).

| Run | Mac | Model | Best decode (engine) | murakumo |
| --- | --- | --- | --- | --- |
| [2026-11-03-m1max](results/2026-11-03-m1max/README.md) | M1 Max, 32 GB, macOS 26.4 (loaded host) | Qwen2.5-0.5B-Instruct Q8_0 / MLX 8-bit | 249.4 tok/s (MLX) | Kotoba Q8_0 kernel via Amu selfhost Mac CLI: 2.472 ms, kernel bound 3.6 tok/s; no token route |

| Tool | Does |
| --- | --- |
| `kernels/q8_0-matvec/run.py` | builds one Q8_0 matvec as Kotoba (Amu Mac CLI, optional static root) and C, checks the checksum and a mutant, times interleaved rounds |
| `summarize.py` | folds a run's raw files into `summary.json` |
| `poster.py` | redraws `docs/assets/header-{light,dark}.svg` from a `summary.json` |

The older llama-bench / diffuse / kotoba-native ledger is `murakumo bench`
(`src/murakumo/bench.cljk`); its `kotoba-native` engine drives the removed C
loader and does not run today.
