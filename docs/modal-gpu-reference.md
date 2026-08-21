# Modal's GPU catalogue — rate, VRAM, bandwidth

Read 2026-08-21 from [modal.com/pricing](https://modal.com/pricing) and
[modal.com/docs/guide/gpu](https://modal.com/docs/guide/gpu). Bandwidth is the
card's published spec; **Modal does not publish it**, and it is what decode
throughput tracks.

The machine-readable copy is `tools/modal-bench/modal_gpu_reference.py`, which
`qwen38_27b_bench.py` imports. **There is deliberately only one copy of these
rates** — a benchmark that prices tokens off a table that has drifted from the
one humans read reports a wrong number in the same format as a right one.

Eleven types. `$/mo` is 730 hours of the GPU line only; Modal bills CPU and
memory separately (below).

| GPU | `gpu=` | VRAM | memory | GB/s | $/s | $/hr | $/mo 730h | ¥/mo |
|---|---|---|---|---|---|---|---|---|
| T4 | `T4` | 16GB | GDDR6 | 320 | 0.000164 | 0.59 | 431 | 68,312 |
| L4 | `L4` | 24GB | GDDR6 | 300 | 0.000222 | 0.80 | 583 | 92,471 |
| A10 | `A10` | 24GB | GDDR6 | 600 | 0.000306 | 1.10 | 804 | 127,461 |
| L40S | `L40S` | 48GB | GDDR6 | 864 | 0.000542 | 1.95 | 1,424 | 225,764 |
| A100 40GB | `A100-40GB` | 40GB | HBM2 | 1,555 | 0.000583 | 2.10 | 1,532 | 242,842 |
| A100 80GB | `A100-80GB` | 80GB | HBM2e | 2,039 | 0.000694 | 2.50 | 1,824 | 289,077 |
| RTX PRO 6000 | `RTX-PRO-6000` | 96GB | GDDR7 | ~1,597 | 0.000842 | 3.03 | 2,213 | 350,725 |
| H100 SXM5 | `H100` / `H100!` | 80GB | HBM3 | 3,350 | 0.001097 | 3.95 | 2,883 | 456,942 |
| H200 SXM | `H200` | 141GB | HBM3e | 4,800 | 0.001261 | 4.54 | 3,314 | 525,254 |
| B200 | `B200` / `B200+` | 180GB | HBM3e | 8,000 | 0.001736 | 6.25 | 4,562 | 723,110 |
| B300 | `B300` | 288GB | HBM3e | 8,000 | 0.001972 | 7.10 | 5,182 | 821,413 |

¥ at ¥158.5/USD. Multi-GPU is `gpu="H100:8"`, up to 8; **more than 2 per
container usually means longer waits.**

## Bandwidth per dollar — where decode cost actually comes from

| GPU | GB/s per $/hr | | GPU | GB/s per $/hr |
|---|---|---|---|---|
| B200 | **1,280** | | A10 | 545 |
| B300 | 1,127 | | T4 | 542 |
| H200 | 1,057 | | RTX PRO 6000 | 527 |
| H100 | 848 | | L40S | 443 |
| A100 80GB | 816 | | L4 | **375** |
| A100 40GB | 741 | | | |

This column is why the measured $/token ranking follows bandwidth and not the
inverse of price (ADR-260820b): **L4 buys a third of B200's bandwidth per
dollar.** The GDDR cards, including the 96 GB RTX PRO 6000, sit at the bottom.

## Three silent substitutions

- **`gpu="H100"` may hand you an H200.** `H100!` pins it. **Whether billing
  follows the upgrade is not documented and we have not verified it.** In three
  runs we always received `NVIDIA H100 80GB HBM3`.
- **`gpu="A100"` may hand you the 80 GB part.** `A100-40GB` pins it.
- **`gpu="B200+"` may hand you a B300 and bills as B200.** The only free one —
  prefer `B200+` over `B200`.

## Spec bandwidth over-predicts, and worse on faster cards

From our own runs (Qwen3.8-27B-FP8, MTP off, single stream, ~28 GB of weights
read per token):

| GPU | measured tok/s | implied GB/s | fraction of spec |
|---|---|---|---|
| RTX PRO 6000 | 45.5 | 1,274 | **80%** |
| H100 SXM5 | 78.2 | 2,190 | 65% |
| L40S | 18.8 | 526 | 61% |
| H200 SXM | 93.9 | 2,629 | **55%** |

**Do not convert catalogue GB/s to tok/s linearly.** The realised fraction runs
55–80% and falls as the card gets faster, so the top of the table is flattered
most.

## VRAM: nameplate is not what the container reports

`torch.cuda.get_device_properties(0).total_memory`, measured:

| `gpu=` | nameplate | observed |
|---|---|---|
| L40S | 48GB | **44.39 GiB, and 47.37 GiB** |
| H100 | 80GB | 79.18 GiB |
| H200 | 141GB | 139.8 GiB |
| B200 | 180GB | 178.35 GiB |
| RTX-PRO-6000 | 96GB | 94.97 GiB |

**The same `gpu="L40S"` string returned two different totals across
containers.** If a deployment is sized to the last gigabyte, it will work on
one container and OOM on another.

## Caveats on the numbers themselves

- **RTX PRO 6000 is the Server Edition** (`NVIDIA RTX PRO 6000 Blackwell Server
  Edition`, observed). Sources split between ~1,597 GB/s for Server Edition and
  1,792 GB/s for Workstation/Max-Q; the lower figure is used here.
- **A100 40GB is 1,555 GB/s (HBM2)**, not 2,039. At least one widely-cited
  comparison table gives both A100 variants 2,039 and calls H100 memory HBM2e;
  it is wrong on both counts.
- **Prices change.** Re-read the pricing page before quoting. The failure mode
  is a dated number being copied without its date.

## What actually fits — measured, not inferred from the VRAM column

Qwen3.8-27B at **W4A16** (`dbirks/Qwen3.8-27B-W4A16-AutoRound`, ~19.5 GB on
disk, MTP draft module included), vLLM 0.27.1, MTP on, 16k context. Full data
in `docs/adr/data/ADR-260821-*`.

| `gpu=` | observed VRAM | result |
|---|---|---|
| `L4` | **22.03 GiB** | **does not fit.** OOM in profiling with MTP; without MTP the weights load and then `No available memory for the cache blocks` |
| `A10` | **22.06 GiB** | **does not fit.** Same, both ways |
| `A100-40GB` | 39.49 GiB | runs. 108.1 tok/s single, 959 at c32, **$0.791/Mtok** |
| `A100-80GB` | 79.25 GiB | runs. 84.0 tok/s single, 1,388 at c64, **$0.627/Mtok** |

**The 24 GB cards report 22 GiB, and a "4-bit 27B" occupies 21.11 GiB of it.**
The gap between the catalogue's `24GB` and the 664 MiB actually left over is
where a capacity plan built from the VRAM column dies. The BF16 MTP head and
the vision tower are carried at full precision regardless of the weight
quantisation, so **dropping MTP does not buy the headroom back** — measured,
both cards, both ways.

**A100 is poor value for this model despite the HBM.** Ampere has no FP8 tensor
cores, so W4A16 dequantises to BF16 and the cards go compute-bound under batch,
topping out near 1,000–1,400 tok/s against an H100's 4,280. Per token they land
at $0.627–0.791 against H100's $0.297 and H200's $0.231 — **2–3x worse than
cards that cost 1.6–1.8x more per hour.**

### FP4 does not rescue the 24 GB cards — it makes it worse

Asked directly, and measured: `sakamakismile/Qwen3.8-27B-MTP-NVFP4`, MTP on,
8k context, `max_num_seqs=16`.

| `gpu=` | W4A16 in use | NVFP4 in use | headroom left |
|---|---|---|---|
| `L4` | 21.11 GiB | **21.92 GiB** | 105 MiB |
| `A10` | 21.11 GiB | **21.97 GiB** | 79 MiB |

**NVFP4 used *more* memory than W4A16, not less**, and both OOM. Two reasons,
and the second is the one that generalises:

1. The NVFP4 checkpoint is **20.6 GB on disk against W4A16's 19.5 GB.**
2. It keeps **MTP draft head, vision tower, `lm_head`, and DeltaNet `conv1d`
   in BF16** — the same modules W4A16 preserves. Both formats put the 27.78B
   transformer body at 4 bits, so **the body was never what did not fit.**

The arithmetic that explains all of it: body 27.78B at 4 bits ≈ 13.9 GB,
embeddings 248,320 × 5,120 at BF16 ≈ 2.5 GB, `lm_head` another ≈ 2.5 GB, MTP
head plus vision tower ≈ 2.5 GB — **≈ 21.4 GB, against the 21.11 GiB
observed.** A 248k vocabulary and a vision tower cost about 7 GiB that no
weight-quantisation scheme in a public checkpoint touches.

⚠ **vLLM did not reject NVFP4 on Ada or Ampere** — it accepted the checkpoint
and died on memory during profiling. So this measurement says nothing about
whether the FP4 *kernels* would have worked on sm_86/sm_89; we never reached a
dispatch. NVFP4 is documented as Blackwell (SM100/SM120), but that is not what
we observed failing.

**It is known to be possible with patches.** `syv-ai/qwen38-27b-rtx3090` runs
this model on a 24 GB RTX 3090 — the same ~22 GiB class, and Ampere like the
A10 — at 114 tok/s with 150k context, using **calibrated int4 `lm_head`** and
an own-output draft vocab: quantising exactly the modules public checkpoints
preserve. That is a patched vLLM, not a checkpoint you can point `--model` at.

**And it would not be worth it.** Scaling that 3090 result by bandwidth, an L4
would reach ~36.5 tok/s and an A10 ~73 — **$7.76 and $5.03 per Mtok**, against
a measured $0.672 for an A100 40GB at concurrency 32 and **$0.271 for an H100
at 128**. The cheap cards are not a cheaper way to run this model; they are a
more expensive one that also does not start. The A100 figures are **W4A16**, while the
H100/H200/L40S figures elsewhere are **FP8** — different quantisation, so the
quality is not the same and only the throughput is comparable. And the
**A100 40GB measured *faster* single-stream than the A100 80GB** (108.1 vs
84.0) despite lower spec bandwidth; both are n=1 and ran alongside other jobs,
and we did not resolve it.
