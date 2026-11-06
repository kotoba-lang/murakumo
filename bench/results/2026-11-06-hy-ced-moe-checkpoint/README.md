# JUN-936: NVMe checkpoint/restart and longer1B pilot

2026-11-06 JST. All model execution is on fleet naphtali (M4/16GiB,
macOS26.7.2, Hy1.1.0/MLX0.32.4/CPython3.12.12). The developer Mac executes
no training/GPU benchmark. Requested10GiB remains unchanged; kernel fatal
process-footprint threshold stays9GiB for the1B pilot.

## Checkpoint controls

V2 stores per-parameter weight/FP32-master shards, sampler/MLX RNG, canonical
parameter order, global step/tokens and source/runtime/data/options metadata.
Hash/COMPLETE/header checks precede model loading. fsync precedes rename.
It supports single-rank SGD only; no global distributed restart is claimed.

Under a1GiB kernel threshold, independent small-model processes compare
continuous4steps with2steps + save + process restart +2steps:

| Control | FP32 | BF16 |
| --- | ---: | ---: |
| Boundary weights/masters/RNG | exact | exact |
| Final max weight difference | 2.9802e-8 | 1.2207e-4 |
| Final max FP32-master difference | 2.9802e-8 | 6.1020e-6 |
| Sampler/options/step/tokens after continuation | equal | equal |

FP32 continuation tolerance is1e-4, with the actual errors recorded above.
BF16 continuation errors are observations; bitwise end-to-end reproducibility
is not qualified and no enlarged BF16 pass threshold is substituted for FP32.

Six refusal controls pass for both dtypes: tensor-bit corruption; correct hash
but wrong actual tensor shape; interrupted partial publication; changed optimizer
options; changed corpus; wrong metadata seal. Three failed-memory-setter cases
still refuse before model/collective/output creation. Raw receipts are adjacent.

## Preregistered longer pilot

1.0437B total parameters, BF16 weights/gradients, FP32 SGD masters on NVMe,
lr0.001, weight decay0.01, global norm clip1.0, seed936, batch1/context256,
expert chunk64 and activation rematerialization. Corpus/tokenizer/digests match
the prior WikiText-2 experiment. Context256 has more measured headroom than512.

Two independent processes perform32 +32 updates, with resume from the first
process's step32 checkpoint. Fixed8-batch held-out evaluation (2,048 tokens,
same sequence/seed) at start/end and every8steps. Checkpoint at16/32/48/64,
with final-save deduplication. Each half has a600s outer deadline and540s
between-step budget. No memory-cap increase or optimizer/precision change.

Record validation CE and first/last8 training-objective means, effective
tokens/s including checkpoint/restart, kernel lifetime footprint/MLX peak,
checkpoint save/load time, logical master traffic and physical process I/O.
The OS cache is not cold-reset; process disk counters are not raw SSD-device
totals. A loss decrease here is a small-data operational learning result, not
general-purpose quality or convergence.3B/distributed scaling/30B duration remain
unqualified. At this implementation unit the longer pilot has not started.
