# Benchmarks

murakumo against the engines people run the same model with on a Mac, on the
same machine, with the same weights wherever an engine reads them. The README
poster and tables come from here; every number links to the run that produced
it.

- Runs, one directory each with raw files: [bench/results](../bench/results/), indexed in [bench/README.md](../bench/README.md).
- What has to change for murakumo's own engine to lead: [inference-roadmap.md](inference-roadmap.md).

## How we measure

**Model.** Qwen2.5-0.5B-Instruct, `bartowski/Qwen2.5-0.5B-Instruct-GGUF`
Q8_0 (531,068,480 bytes, sha256 `25130a98…fdf96`), the model murakumo's
Kotoba kernels were first measured with. MLX reads the
`mlx-community/Qwen2.5-0.5B-Instruct-8bit` conversion: the same bit width,
a different quantization.

**Whole-model cells.** Prefill is a 512-token prompt; decode is a 128-token
answer at batch 1 (64 tokens for the single-thread CPU column). Each engine
reports its own timing (`llama-bench`, `mlx_lm.benchmark`); a cell is the
median of three round means, rounds interleaved across engines, and the
range of those means is kept in the record.

**murakumo cells.** A murakumo cell is a measured token rate from a
Kotoba-built engine, or a stand-in labeled as one. Today two kinds appear:

- *Kotoba decode guest on the GPU, 2026-10-19.* The guest was Kotoba
  compiled by `bin/amu`; its kernels were GLSL built with `glslangValidator`;
  it ran through the C `kexe_loader` and MoltenVK and was timed by its own GPU
  fences. That loader was removed from Amu on 2026-10-22, so the
  cell is a dated record and is not re-measured.
- *Amu selfhost on this Mac, kernel bound.* One Q8_0 × Q8_0 matvec written in
  Kotoba and compiled by the Amu selfhost Mac CLI, timed against the same
  algorithm in C. Its bound divides the model's per-token multiply-adds by the
  kernel's rate; it is a ceiling for a single-threaded engine made of that
  kernel, not tokens.

**Correctness before speed.** A kernel time counts only if every build prints
the same checksum and a mutated Kotoba kernel prints a different one
(`bench/kernels/q8_0-matvec/run.py` refuses to time otherwise). Whole-engine
murakumo cells will additionally have to reproduce the 2026-10-19 greedy
continuation (prompt ids `760 6511 314 9338 369` → `314 746 600 220`).

**Host.** The host, its load and anything else running are written into each
run's README. A loaded host is recorded, not cleaned up: the 2026-11-03 run
shared its Mac with two VMs, which is why eight llama.cpp CPU threads decoded
slower than one there.

## M1 Max · 2026-11-03

[Record](../bench/results/2026-11-03-m1max/README.md). Tokens per second.

| Engine | Prefill | Decode |
| --- | ---: | ---: |
| MLX, 8-bit | 7,191 | **249.4** |
| llama.cpp Metal, Q8_0 (b11669) | **8,842** | 218.4 |
| llama.cpp CPU, 1 thread | 150 | 90.7 |
| murakumo, Kotoba decode guest on the GPU (2026-10-19 record) | — | 71.2 |
| llama.cpp CPU, 8 threads | 496 | 49.3 |
| murakumo, Amu selfhost on this Mac (kernel bound) | — | 3.6 |

| One Q8_0 matvec, 4,864 × 896, one thread | ms | GMAC/s |
| --- | ---: | ---: |
| C, `cc -O2` | 1.446 | 3.01 |
| Kotoba, Amu selfhost Mac CLI | 2.472 | 1.76 |
| Kotoba, Amu static root, `aarch64-linux` in a VM | 315.6 | 0.014 |

## Run it yourself

On an Apple-silicon Mac with an Amu checkout next to this one:

```sh
# reference engines (llama.cpp and mlx-lm from Homebrew/pip)
llama-bench -m Qwen2.5-0.5B-Instruct-Q8_0.gguf -p 512 -n 128 -r 3 -o json > raw/llama-metal-r1.json
llama-bench -m Qwen2.5-0.5B-Instruct-Q8_0.gguf -ngl 0 -t 8 -p 512 -n 128 -r 3 -o json > raw/llama-cpu-r1.json
mlx_lm.benchmark --model <mlx-model-dir> -p 512 -g 128 -n 3 > raw/mlx-r1.txt

# the Kotoba kernel; --static also needs podman
python3 -I bench/kernels/q8_0-matvec/run.py --amu ../amu --out q8-matvec-mymac.json [--static]

# fold a run directory and redraw the poster
python3 -I bench/summarize.py bench/results/<run>
python3 -I bench/poster.py bench/results/<run>
```
