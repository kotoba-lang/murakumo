### `kapitsa bench compare`: Qwen3.5-4B on Apple M1 Max, 32 GB, macOS 26.4

kapitsa 0.2.36 (fe6a17a); harness e1d86b9 (`bench/harness`, profile `quick`: thinking off, one request at a time, T=0.6, prefill 4000 tokens, medians). Measured 2026-11-15 17:39.

| # | engine | decode tok/s | prefill 4000 tok/s | prefill 4000 tok/s | TTFT cold | TTFT cached | memory | vs Kapitsa |
|--:|---|--:|--:|--:|--:|--:|--:|--:|
| 1 | Kapitsa | 137.5 | 764 | - | 5.41 s | 78 ms | 3.8 GB |  |
| 2 | mlx-lm | 64.6 | 268 | - | 15.40 s | 577 ms | 5.1 GB | 0.47x |
| 3 | llama.cpp | 28.7 | 298 | - | 13.88 s | 297 ms | 5.5 GB | 0.21x |

#### Who beats Kapitsa here, and why

No engine measured here is more than 3% ahead of Kapitsa on any column.

#### Caveats from the run

- Kapitsa: CONTENTION: sustained foreign GPU load > 5% in ['decode', 'prefill_4000'] (mean 11.75%, top ['Claude Helper'])
- mlx-lm: CONTENTION: sustained foreign GPU load > 5% in ['decode', 'prefill_4000'] (mean 22.09%, top ['Claude Helper'])
- llama.cpp: decode: repetition loop (distinct-4-gram 0.4761)
- llama.cpp: CONTENTION: sustained foreign GPU load > 5% in ['decode', 'prefill_4000'] (mean 10.39%, top ['Claude Helper'])

<details><summary>Commands that ran</summary>

- Kapitsa `q35-4b-mtp-kapitsa`: `~/github/wt/muse/e1-first-token/murakumo/.agent-output/e0/tools/kapitsa-0.2.36-darwin-arm64/kapitsa serve --model <models>/qwen3.5-4b-4bit-mtp --model-id qwen3.5-4b --dflash none --host 127.0.0.1 --port 8091 --ctx 32768 --slots 1`
- mlx-lm `q35-4b-mlxlm`: `/opt/homebrew/bin/mlx_lm.server --model <models>/qwen3.5-4b-4bit-mtp --host 127.0.0.1 --port 8081 --prompt-cache-size 4 --prompt-cache-bytes 8589934592 --max-tokens 16384 --prefill-step-size 2048`
- llama.cpp `q35-4b-llamacpp`: `/opt/homebrew/bin/llama-server -m ~/github/wt/muse/e1-first-token/murakumo/.agent-output/e0/models/Qwen3.5-4B-Q4_K_M.gguf --host 127.0.0.1 --port 8083 -a qwen3.5-4b -c 32768 -np 1 -ngl 999 -fa on -b 2048 -ub 2048 -lm none --jinja --chat-template-file <models>/qwen3.5-4b-4bit-mtp/chat_template.jinja --reasoning-format deepseek --cache-ram 8192 --ctx-checkpoints 32 --temp 0.6 --top-p 0.95 --top-k 20 --min-p 0 --no-webui`

</details>

Kapitsa's maintainers wrote this benchmark; every config it ran is in `bench/harness/configs/` and is open to challenge. Re-run with `kapitsa bench compare --model 4b`.
