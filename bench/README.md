# murakumo bench

One directory per run under `results/`, with its raw files, a `summary.json`
from `summarize.py` and a README that names the host, its load, the engines'
versions and argv. Method: [docs/benchmarks.md](../docs/benchmarks.md).

| Run | Mac | Model | Best result (engine) | murakumo |
| --- | --- | --- | --- | --- |
| [2026-11-03-m1max](results/2026-11-03-m1max/README.md) | M1 Max, 32 GB, macOS 26.4 (loaded host) | Qwen2.5-0.5B-Instruct Q8_0 / MLX 8-bit | 249.4 tok/s (MLX) | Kotoba Q8_0 kernel via Amu selfhost Mac CLI: 2.472 ms, kernel bound 3.6 tok/s; no token route |
| [2026-11-13-m1max](results/2026-11-13-m1max/README.md) | M1 Max, 32 GB, macOS 26.4 (loaded host) | Qwen2.5-0.5B-Instruct Q8_0 / MLX 8-bit | 250.8 tok/s (MLX) | not in this round |
| [2026-11-15-e2-decode](results/2026-11-15-e2-decode/README.md) | same | Qwen2.5-0.5B-Instruct Q8_0 / MLX 8-bit | **292.6 tok/s (murakumo)**; MLX 252.4, llama.cpp 168.9 same hour | E2: Kotoba via Amu selfhost, Metal, decode |
| [2026-11-15-e3-f16](results/2026-11-15-e3-f16/README.md) | same | Qwen2.5-0.5B-Instruct Q8_0 / MLX 8-bit | prefill pp512: llama.cpp 8,860, murakumo 8,814, MLX 6,428 | E3: batched prefill, level with llama.cpp within about 1 % |
| [2026-11-15-e0-kapitsa](results/2026-11-15-e0-kapitsa/README.md) | same | Qwen3.5-4B 4-bit (Q4_K_M / MLX / Kapitsa packs) | 106.3 tok/s (Kapitsa, speculative); MLX 48, llama.cpp 32–36 plain | not in this round |
| [2026-11-16-qwen35-decode](results/2026-11-16-qwen35-decode/README.md) | same | Qwen3.5-4B Q4_K_M / MLX 4-bit | 67.9 tok/s (MLX, plain) | 38.6 between the others, 45–51 alone; llama.cpp 35.5 |
| [2026-11-16-qwen35-decode-3](results/2026-11-16-qwen35-decode-3/README.md) | same | Qwen3.5-4B Q4_K_M / MLX 4-bit | 55.5 tok/s (MLX, plain) | 44.0 between the others (17.6–18.0 ms/token in build comparisons); llama.cpp 38.6 |
| [2026-11-16-qwen35-mtp](results/2026-11-16-qwen35-mtp/README.md) | same | Qwen3.5-4B Q4_K_M + MTP head | — (build comparison only) | MTP drafts: 15.2–15.3 ms/token against 17.1 plain (repetitive text, 81 % accepted); no faster on chat text (71 %) |
| [2026-11-17-qwen35-multi](results/2026-11-17-qwen35-multi/README.md) | same | Qwen3.5-4B Q4_K_M | — (build comparison only) | prompt in 16-token passes: 38–41 ms per pass (about 2.5 ms/token) against 24 ms per 2-token pass; llama.cpp pp16 380 tok/s |
| [2026-11-17-qwen35-dflash](results/2026-11-17-qwen35-dflash/README.md) | same | Qwen3.5-4B Q4_K_M + DFlash drafter | — (build comparison only) | DFlash drafts, lossless: 16.6–21.0 ms/token against 18.2–19.5 plain with a think block (3.3–3.5 tokens per cycle); 24–30 against 17 without (2.15) |
| [2026-11-17-qwen35-dflash-2](results/2026-11-17-qwen35-dflash-2/README.md) | same | Qwen3.5-4B Q4_K_M + DFlash drafter | — (build comparison only) | 8-column verify matvec: DFlash block 8 12.3–12.4 ms/token against 17.0–20.7 plain with a think block (about 1.4×); 18.2–18.7 against 16.8–17.0 without |
| [2026-11-17-qwen35-verify](results/2026-11-17-qwen35-verify/README.md) | same | Qwen3.5-4B Q4_K_M | — (build comparison only) | 8-token pass 25.4–26.5 ms (llama.cpp pp8 about 23.9 ms); six matvec variants tried, one kept (16-token pass 37 → 35 ms) |

| Tool | Does |
| --- | --- |
| `kernels/q8_0-matvec/run.py` | builds one Q8_0 matvec as Kotoba (Amu Mac CLI, optional static root) and C, checks the checksum and a mutant, times interleaved rounds |
| `summarize.py` | folds a run's raw files into `summary.json` |
| `kernels/qwen2-metal/run.sh`, `kernels/qwen35-metal/run.sh` | build the Kotoba engine program for Qwen2.5-0.5B or Qwen3.5-4B and run its gate (greedy tokens, mutants, refusals) |
| `poster.py` | redraws `docs/assets/header-{light,dark}.svg` from `poster.json`, whose numbers come from the records above |

The older llama-bench / diffuse / kotoba-native ledger is `murakumo bench`
(`src/murakumo/bench.cljk`); its `kotoba-native` engine drives the removed C
loader and does not run today.
