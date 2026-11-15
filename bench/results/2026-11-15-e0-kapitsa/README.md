# E0: llama.cpp, MLX and Kapitsa on the same Mac, Qwen3.5-4B 4-bit (2026-11-15)

engine-plan E0, Linear AWAI-2937. MacBook Pro M1 Max (32-core GPU, 32 GB), macOS 26.4, on a busy host: other
sessions kept 10–22 % of the GPU, and every run below is flagged for that contention. Raw outputs are in
[`raw/`](raw/); home paths are shortened.

**murakumo is not in this table.** Kapitsa does not read Qwen2.5: `kapitsa import` takes only the `qwen35`, `qwen35moe`
and `flint` GGUF architectures. murakumo does not run Qwen3.5 yet. So this is the table of numbers to beat on Qwen3.5-4B.

## What was installed (owner-approved downloads)

| Item | Source, revision | Check |
| --- | --- | --- |
| Kapitsa 0.2.36 (fe6a17a) | `nace-ai/kapitsa` release `kapitsa-0.2.36-darwin-arm64.tar.gz`, unpacked into the task's `.agent-output/` (not Homebrew); `kapitsa update --auto off` | sha256 `c88e9eec…` = the tap formula's |
| Kapitsa `4b` | `nace-ai/Qwen3.5-4B-MLX` @ 9f7d327 (Nace 4-bit, group 64) + `z-lab/Qwen3.5-4B-DFlash` @ 9a1996c + MTP head from `Qwen/Qwen3.5-4B` @ 851bf6e; `kapitsa pull 4b`, 4.57 GB | Kapitsa verifies each pinned file |
| llama.cpp weights | `unsloth/Qwen3.5-4B-GGUF` @ e87f176, `Qwen3.5-4B-Q4_K_M.gguf`, 2.74 GB | sha256 `00fe7986…` = Hugging Face's |
| MLX weights | `mlx-community/Qwen3.5-4B-4bit` @ 0e7ffd5, 3.03 GB | `hf download` |

Engines: llama.cpp 0.7.0 (b11669, 620d2225c, Homebrew), mlx-lm 0.31.1 / mlx 0.31.1, Kapitsa 0.2.36.

## 1. `kapitsa bench compare --model 4b --quick` (Kapitsa's harness, same prompts for every engine)

T 0.6, thinking off, one request at a time, prefill on a 4,000-token prompt. Kapitsa ran its MTP config
(`--dflash none`), and mlx-lm was given Kapitsa's weight folder.

| Engine | decode tok/s | prefill 4000 tok/s | TTFT cold | TTFT cached | memory |
| --- | ---: | ---: | ---: | ---: | ---: |
| Kapitsa (MTP head) | 137.5 | 764 | 5.41 s | 78 ms | 3.8 GB |
| mlx-lm | 64.6 | 268 | 15.40 s | 577 ms | 5.1 GB |
| llama.cpp | 28.7 | 298 | 13.88 s | 297 ms | 5.5 GB |

The harness flagged llama.cpp's decode as a repetition loop (distinct-4-gram 0.48). Kapitsa's maintainers wrote this
harness; its configs are in the tarball's `bench/harness/configs/`.

## 2. Each engine's own benchmark

| Engine, tool | prefill | decode | Decoding |
| --- | ---: | ---: | --- |
| llama.cpp, `llama-bench -p 512 -n 128 -r 3`, 2 rounds | pp512 452.8 ±318.0, 416.4 ±247.8 | tg128 35.6 ±3.8, 32.0 ±2.4 | plain |
| MLX, `mlx_lm.benchmark --model mlx-community/Qwen3.5-4B-4bit -p 512 -g 128 -n 3`, 2 rounds | 351.7, 340.0 | 48.4, 47.9 | plain |
| Kapitsa, `kapitsa bench --model 4b --quick` (catalog default) | 708 (4K prompt) | 110.2 at T 0.6, 106.3 at T 0 | KFlash with the DFlash2 drafter; 73.8 % / 72.7 % of tokens from accepted drafts |
| Kapitsa, same with `--dflash none` | 558 (4K prompt) | 78.6 at T 0.6, 79.8 at T 0 | MTP head, depth 4; 63.7 % / 65.4 % from accepted drafts |

The rows don't measure the same thing. llama-bench and mlx_lm.benchmark decode random or fixed tokens with no
speculation. Kapitsa decodes 512-token coding answers through its server, where its drafters help. Kapitsa has no plain
decoding mode in this protocol (`--dflash none` still drafts with the MTP head). The wide llama-bench prefill
spread (±250–318) and Kapitsa's MTP decode (138 in the compare run, 77–127 here) show how much the busy GPU moved the
numbers.

## Reading

- On this Mac and model, Kapitsa decodes about 2–4x faster than MLX and llama.cpp, mostly through speculative
  decoding. Its prefill is also about 2x MLX's and llama.cpp's on long prompts.
- For murakumo, the decode bar on Qwen3.5-4B is about 106–138 tok/s (Kapitsa, speculative). Plain decoding is
  bounded by MLX's 48 tok/s and llama.cpp's 32–36. murakumo first needs Qwen3.5 support: its hybrid layers,
  Gated DeltaNet plus full attention, are not implemented. Then E5 (speculative decoding) to reach Kapitsa.
- A quiet-host rerun of the same commands is still needed before these numbers are used as targets.
