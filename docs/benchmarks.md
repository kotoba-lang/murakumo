# Benchmarks

murakumo against the engines people run the same model with on a Mac, on the
same machine, with the same weights wherever an engine reads them. The README
poster and tables come from here; every number we measured links to the run
that produced it.

- Runs, one directory each with raw files: [bench/results](../bench/results/), indexed in [bench/README.md](../bench/README.md).
- What has to change for murakumo's own engine to lead: [inference-roadmap.md](inference-roadmap.md).
- Numbers other projects publish, which we have not run: [Other engines, as published](#other-engines-as-published).

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

## Other engines, as published

We have not run these. Each number is what the linked page stated when we read
it on 2026-11-06, on its own hardware and model, so they cannot be ranked
against each other or against the M1 Max table above. No number below is for
Qwen2.5-0.5B. On this Mac and that model, only MLX and llama.cpp have been
measured. murakumo's Amu selfhost engine has no token rate yet; the last tokens
a murakumo engine generated here came through the C loader that is now gone.

| Engine | Targets | On an M1 Max | Published decode, tokens per second |
| --- | --- | --- | --- |
| [Kapitsa](https://github.com/nace-ai/kapitsa/blob/6ab2ec899fc4fc1dd8068248421cfa64f5f2241e/README.md) | Apple silicon only | States M1 or newer; publishes an M1 Pro, not an M1 Max | M1 Pro 16 GB: Qwen3.5-4B 4-bit 97, Qwen3.6-35B-A3B 2-bit 52. M5 Max 128 GB: Qwen3.6-35B-A3B 4-bit 351. All speculative (KFlash). The project's own runs |
| [TensorFold](https://github.com/ashhart/TensorFold/blob/54695a804e736d52555347379b1d39cdc3bfab84/README.md) | Metal, CUDA | No check on this chip shown: the README lists Nemotron 3.5 Lightning for M1-M5 as a range, and the [Zig preview](https://github.com/ashhart/TensorFold/blob/54695a804e736d52555347379b1d39cdc3bfab84/ZIG-PREVIEW.md) says Qwen3.5-2B is untested on M1-M4 | M5 Max, Nemotron 3.5 Lightning 30B-A3B MLX 4-bit with MTP head, one greedy stream, essay prompt: 277 (Zig engine), about 220 (Python engine), about 175 (mlx_lm server, greedy prose). The project's own runs |
| [Strata](https://github.com/Niko1221/Strata) | Consumer GPUs, Mac | [Measured by the project](https://github.com/Niko1221/Strata/blob/9f7d977d220e9fdf2ec53cf2620cdadac1cfb24e/docs/MAC.md) on an M1 Max 34 GB | M1 Max 34 GB, Q2_0 pack, experts streamed from the SSD: 7.9. [R9700 32 GB](https://github.com/Niko1221/Strata/blob/9f7d977d220e9fdf2ec53cf2620cdadac1cfb24e/docs/QWEN36.md), Qwen3.6-35B-A3B UD-Q4_K_XL, tg128: 117.8 (Strata 0.1.45) against 81.9 to 82.1 (llama.cpp 972b5fc HIP, same file); greedy with `--mtp 0`: 83.9 against 73.8. The project's own runs |
| llama.cpp | Nearly everything | Measured here: the M1 Max table | Also the reference in the Kapitsa and Strata pages |
| [vLLM](https://docs.vllm.ai/en/latest/getting_started/installation/cpu/) | GPU servers | Built in: experimental, CPU only, built from source. Metal through the separate [vllm-metal](https://github.com/vllm-project/vllm-metal) plugin | [RTX 4090, Qwen2.5-7B-Instruct, 64 concurrent requests](https://computingforgeeks.com/?p=170231): 1,740 in total (vLLM 0.25.1). Third party, July 2026 |
| [SGLang](https://github.com/sgl-project/sglang/blob/93cfea6b12a908161719edf0e3a732e771ac9fad/README.md) | GPUs, TPUs, NPUs, CPUs | The README lists Apple Silicon through Metal / MLX; not tried here | Same test: 1,718 in total (SGLang 0.5.15). Third party, July 2026 |
| [Flare](https://github.com/NVIDIA/spark-local-inference/blob/972e28e44781d8d0c84d70ae434db92c36076a43/README.md) | NVIDIA SM120/SM121 only | No | None in the README, which calls it a research prototype |

Reading these:

- **Totals are not streams.** The vLLM and SGLang numbers are totals across 64
  requests at once. The test's author calls them a tie; first tokens took 849 ms
  and 3,045 ms, and each later token 34.0 ms and 29.1 ms, in that order.
- **Speculative decoding depends on the text.** The same TensorFold engine, in
  the same test, runs from 244 to 581 tok/s across five prompts. Kapitsa and
  TensorFold both state that drafts do not change the output.
- **Most of this is self-reported.** Kapitsa, TensorFold and Strata each measured
  their own engine, against other engines they chose.
- **This compares engines, not fleets.** A murakumo node can serve through
  llama.cpp, vLLM or MLX, so those are also its backends. The comparison is with
  murakumo's own Kotoba engine; what that engine does not plan yet is in the
  roadmap's [Not in the stages yet](inference-roadmap.md#not-in-the-stages-yet).
