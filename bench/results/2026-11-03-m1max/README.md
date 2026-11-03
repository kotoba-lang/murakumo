# 2026-11-03 · MacBook Pro M1 Max · 32 GB · macOS 26.4

Author-run on one shared Mac. Not independently verified.

## Host

- Apple M1 Max (8 performance + 2 efficiency cores), 32 GB, macOS 26.4, AC power.
- The Mac was busy and was left as it was: two `qemu-system-x86_64` VMs that
  belong to other work (running for 8–9 days) held about one core each, swap
  was 7.5 of 9.2 GB, and the load average read 21–40 during the runs. Every
  number here was taken under that load. The GPU columns barely moved between
  rounds; the CPU columns moved by up to a third.
- Rounds were interleaved (llama.cpp Metal, llama.cpp CPU, MLX, then again).

## Model

`bartowski/Qwen2.5-0.5B-Instruct-GGUF` → `Qwen2.5-0.5B-Instruct-Q8_0.gguf`,
531,068,480 bytes, sha256 `25130a98aa782284a7dabea0c23245b2fd371ed47244e79d78b8ec23245fdf96`
(the same file and size as the 2026-10-19 Kotoba-kernel record, which did not
record a digest). MLX reads `mlx-community/Qwen2.5-0.5B-Instruct-8bit`
(group-wise affine 8-bit, `model.safetensors` 525,045,902 bytes): the same bit
width, not the same file.

## Engines and argv

| Engine | Version | argv |
| --- | --- | --- |
| llama.cpp Metal | 0.7.0, build 11669 (`620d2225c`), Homebrew | `llama-bench -m <gguf> -p 512 -n 128 -r 3 -o json` |
| llama.cpp CPU | same | `llama-bench -m <gguf> -ngl 0 -t 8 -p 512 -n 128 -r 3 -o json` |
| MLX | mlx 0.31.1, mlx-lm 0.31.1 | `mlx_lm.benchmark --model <dir> -p 512 -g 128 -n 3` |
| Kotoba Q8_0 kernel | Amu `1abb17f30` | `python3 -I bench/kernels/q8_0-matvec/run.py --amu <amu> --static --reps 300 --rounds 5 --static-reps 20 --static-rounds 3` |

Raw files: [`raw/`](raw/) (one file per engine and round), [`q8-matvec-m1max.json`](q8-matvec-m1max.json).
Folded by `python3 -I bench/summarize.py` into [`summary.json`](summary.json).

## Results: whole model, Qwen2.5-0.5B-Instruct

Median of three round means (range in brackets), tokens per second.

| Engine | Prefill, 512-token prompt | Decode, 128 tokens |
| --- | ---: | ---: |
| MLX, 8-bit | 7,191 [7,140–7,215] | **249.4** [248.9–250.4] |
| llama.cpp Metal, Q8_0 | **8,842** [8,788–8,883] | 218.4 [190.6–229.5] |
| llama.cpp CPU, 8 threads, Q8_0 | 496 [454–529] | 49.3 [35.4–54.8] |
| murakumo, Kotoba GPU kernels (bootstrap reference) | — | 71.2 ¹ |
| murakumo, Amu selfhost on this Mac | no route ² | no route ² |

¹ Not re-measured: recorded on this Mac on 2026-10-19 (kotoba-lang/inference
`verify/evidence/qwen25-0p5b-dense-selfhost-vs-llamacpp-m1max-20261019.json`,
`nvgpu-h2` layout, GPU fence time per step through MoltenVK). The guest was
compiled by `bin/amu` but launched by the C `kexe_loader`, which Amu removed on
2026-10-22 (amu #1459/#1460), so that path no longer builds. Commit messages of
2026-10-20 mention 7.07 ms/step after further fusion; no evidence file was
written, so that figure is not used.

² No prompt-to-token route exists through Amu selfhost on macOS today: the
native Mac CLI gives guests no filesystem and a 5-second `run` deadline, the
checker refuses surface float arithmetic, and the GPU wire is refused (E2115).
The kernel below is the closest measurable piece.

## Results: one decode kernel, built three ways

Q8_0 × Q8_0 matrix-vector product with Qwen2.5-0.5B's `ffn_up` shape
(4,864 × 896), the operation behind llama.cpp's CPU decode
(`ggml_vec_dot_q8_0_q8_0`). Same algorithm in [`q8_matvec.kotoba.in`](../../kernels/q8_0-matvec/q8_matvec.kotoba.in)
and [`q8_matvec.c`](../../kernels/q8_0-matvec/q8_matvec.c): synthetic weights
packed eight bytes to a word, fixed-point block scales, single thread. All
builds print the same checksum (`256895886555412` after one product,
`182271268304401` after 300); a mutated Kotoba kernel prints `127909983820564`.

| Build | ms per product (median, range) | GMAC/s | Decode bound, tok/s ³ |
| --- | ---: | ---: | ---: |
| C, `cc -O2` (Apple clang 21) | 1.446 [1.445–1.491] | 3.01 | 6.1 |
| C, `cc -O3 -mcpu=native` | 1.448 [1.446–1.645] | 3.01 | 6.1 |
| **Kotoba, Amu selfhost Mac CLI** (`bin/amu compile`, arm64 Mach-O) | **2.472** [2.466–2.482] | **1.76** | **3.6** |
| Kotoba, Amu static root (`aarch64-linux`, podman VM) | 315.6 [304.8–316.6] | 0.014 | 0.03 |

³ Kernel only: Qwen2.5-0.5B's 493,961,216 matvec multiply-adds per token
divided by this kernel's rate. It leaves out attention, norms, sampling and
threads, so it is an upper bound for a single-threaded engine built from this
kernel, not a measured token rate.

The Mac CLI build is 1.71 times the C time. The static image is 128 times
slower than the Mac build, and the Linux VM is not the reason: a Python
loop runs 2.27 s in the VM against 2.16 s on the host. Two probes in
[`probes/`](probes/) split it (50 M iterations each):

| Probe | Mac CLI build | Static image |
| --- | ---: | ---: |
| scalar shifts, multiply, mask ([`scalar.kotoba`](probes/scalar.kotoba)) | 0.48 s | 0.27 s |
| one `vector-at` per iteration ([`vec.kotoba`](probes/vec.kotoba)) | 0.54 s | 3.13–3.25 s |

Arithmetic is on par; each vector read in the static image costs about 60 ns.
That root (`e140b8d2…`, produced 2026-10-27) emits every vector access as a
helper call through the context table, which kotoba-lang/amu#1667
(KOTOBA-168, open) replaces with inline bounds-checked loads. The Mac CLI uses
the adopted AArch64 core (`seed/41-a64gen`), which already inlines them.

## Non-claims

- No murakumo-built engine produced a token on this Mac today.
- The kernel table is one operation on synthetic weights, not tokens.
- MLX's 8-bit and llama.cpp's Q8_0 are different quantizations of one model.
- One shared, loaded host; no power-mode or thermal control.
