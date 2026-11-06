# JUN-936: first M4 from-scratch 1.044B capacity/update trial

2026-11-06 JST. This is four optimizer steps, not convergence or a useful
pretrained model. No 3B training or 30B time prediction is established here.

## Conditions

- Mac mini M4, 16GiB unified memory, macOS26.7.2, existing fleet host.
- Code `d5f9a4a7` on the JUN-936 review branch; Hy1.1.0, MLX0.32.4,
  portable CPython3.12.12. No model checkpoint was loaded.
- Reduced CSA2-style CED/SWA/Sparse MoE; differences from DeepSeek are listed
  in `experiments/ced_moe/README.md`.
- 1,043,698,432 total parameters; 123,572,992 selected-weight upper bound.
  16 layers, width768, 64 routed experts, top4 plus one shared expert.
- Fully SHA-verified WikiText-2 raw source; train-only32K BPE. Derived data
  manifest SHA256 `abbeeeac4e69931808c7a93774f346710a22dfd5d3977136ed49b7198264f82a`.
- BF16 model/gradients, FP32 master weights, SGD without momentum, LR0.001,
  weight decay0.01, norm clip1.0, batch1, sequence64, seed936.
- Explicit admitted first trial: four steps, MLX cap10GiB, outer child
  deadline60s, no checkpoint. This does not qualify a shared governor or a
  subsequent long/distributed run. The developer Mac performs no model execution.

## Observed

| Metric | Result |
| --- | ---: |
| Completed optimizer steps / training tokens | 4 / 256 |
| Training-phase wall (includes periodic validation and first-step warm-up) | 9.3512s |
| Training-phase rate | 27.3761 tokens/s |
| Median warm step (steps2–4) | 1.92288s |
| Child supervisor wall, including startup/initialization/evaluation | 14.6336s |
| MLX allocator peak | 9,278,325,592 bytes (8.64GiB) |
| Validation CE, same64-token held-out sample | 10.45107 -> 10.42562 |
| Swap usage before / after | 621.75MiB / 621.75MiB |
| Free-memory indicator before / after | 82% / 82% |

The supervisor exited0 without a timeout and reaped its child. The result
JSON contains the four complete step timings. MLX peak is not total OS RAM.
The one-sample validation change and short duration do not establish quality,
sustained throughput, restart behavior at this scale, or absence of transient
swap activity within the run. Existing processes/services were not stopped.

The fleet's observed Ethernet links negotiate1Gb/s. This single-host trial
performs no distributed communication; it cannot calibrate PP/EP or inter-island
traffic, and its inference speed is not measured. Compare optimizers and
partition geometry explicitly before projecting any 30B duration.

The64-token input is shorter than the configured128-token SWA window, so
this capacity trial does not exercise local-window truncation or measure its
long-context savings. The small numerical control separately exercises a
window4 on sequence11. AdamW at1B also needs substantially more state than
this SGD trial; do not treat the16GiB result as unsharded AdamW support.

## Two-host CPU ring and tiny replicated training controls

Two physical M4/16GiB hosts on the wired LAN, MLX ring backend, with separate
30s child deadlines. These are small controls; they do not establish 1B DDP,
PP/EP, asynchronous/local-SGD scaling or a long-running resource qualification.

The CPU-only ring probe verifies all three repetitions of element-wise sum
for 4KiB, 1MiB and 16MiB inputs on both ranks. For16MiB, rank-local medians
are0.17509s and0.15455s (raw samples in the JSONs). Interface TX deltas were
56,188,260 and54,589,676 bytes across the complete probe; they include
background/control traffic and are not exclusive per-job wire-byte counts.

The 2,807,616-parameter DDP control uses different per-rank data RNG streams,
BF16 gradients, FP32 AdamW, batch1/sequence16, two steps and a1GiB cap per rank.
Both ranks finish with the same validation CE10.40366 and the same saved
weights SHA256:

`dc4c5df4cb1e67ada708a672ff2a775b906191dd54aa1b6d2bd6ef8a68090d02`

Each rank reduces5,615,232 logical gradient bytes per step. The second step's
communication times are0.16430s and0.15000s; rank0's cold first step also
waits for the other rank's warm-up. Neither rank estimates exclusive wire
bytes from these timings. The complete supervised controls take about6.30s
per host. Both children exit0 and the ring listener/owned training processes
are absent on readback. Tiny checkpoint files remain as experiment evidence;
no system service or scheduler is installed.

The high per-tensor collective latency and optimizer barriers make these
short controls unsuitable for a30B wall-clock prediction. Batch/context
amortization, coalesced communication, a larger model point, model-state
sharding, and a held-out scaling configuration still need measurement.
