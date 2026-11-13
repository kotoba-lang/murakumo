# JUN-936: NVMe checkpoint/restart and longer1B pilot

2026-11-06 JST. All model execution is on fleet naphtali (M4/16GiB,
macOS26.7.2, Hy1.1.0/MLX0.32.4/CPython3.12.12). The developer Mac executes
no training/GPU benchmark. Requested10GiB remains unchanged; kernel fatal
process-footprint threshold stays9GiB for the1B pilot.

## Measured outcome

Checkpoint/restart and64 updates completed within budget. The sampled training
objective decreased, but **fixed held-out CE did not improve**. This does not
qualify language-model quality or convergence.

Executing source `54f10c9c`, unchanged across two processes.1.0437B total
parameters,16,384 processed tokens, context256, batch1, fixed seed/options below.

| Metric | First32 updates | Resumed32 updates | Combined |
| --- | ---: | ---: | ---: |
| Global step range | 0 -> 32 | 32 -> 64 | 0 -> 64 |
| Training tokens | 8,192 | 8,192 | 16,384 |
| Supervisor seconds, including setup/load/eval/checkpoints | 287.1883 | 344.6410 | 631.8293 |
| Effective tokens/s using supervisors | 28.525 | 23.770 | 25.931 |
| Process lifetime footprint peak | 6.9100GiB | about6.91GiB | 6.9100GiB max |
| Process disk-I/O reads | 58.8124GB | 102.4001GB | 161.2124GB |
| Process disk-I/O writes | 150.3137GB | 150.3200GB | 300.6337GB |

GB is decimal; memory GiB is binary. Process counters include optimizer and
checkpoint activity and reflect OS caching/filesystem attribution. They are
not whole-SSD device totals. The post-run integrity verification is separate.
Supervisor sums exclude the pause for Git/Linear/evidence recording between
phases. First-before to final-after is706s (23.207 tokens/s including that pause).
Training-run-only all-in rate is25.951 tokens/s; the slightly broader supervisor
rate25.931 is used above. No cold-cache reset or SSD-throttling cause was measured.

| Loss observation | Start | End |
| --- | ---: | ---: |
| Training objective, first/last8-update means | 11.025574 | 10.983852 |
| Fixed2,048-token held-out CE | 10.539890 | 10.545210 |

The training objective includes indexer/router auxiliary losses and the means
use different sampled training batches. Its decrease is not evidence that
held-out language CE improved. The latter increased by0.005320. Intermediate
validation fluctuates; the best intermediate value is not substituted for the
preregistered64step endpoint. A next quality experiment needs a separate fixed
token-budget/learning-rate/optimizer comparison, not an unsupported success claim.

V2 checkpoints were published at16/32/48/64, each6,262,230,306 tensor-file
bytes. Total save time44.8833s. Actual checkpoint32 reload took4.3202s and its
initial held-out CE exactly matched the prior process's final10.5380344391.
Model weights, FP32 masters, RNG, sampler/options and global counts were carried
through the restart. Snapshot32 ->64 hashes change in168/203 weight shards and
203/203 master shards. BF16 continuation is still not bitwise qualified.

Final checkpoint remains on fleet:
`naphtali:github/workspaces/codex/jun-936-hy-moe/long1b-part-b/checkpoint-64-rank-0`.
Its406 tensor shards were independently reread: every hash/header/shape/dtype
and COMPLETE seal passed. State SHA256:
`1d4e5c2b6a4c41aa59c1ce97bcf97d4c62c0f6905aeac63acf8719992aef8588`.
GB checkpoints are not committed to Git; their receipts/manifests and run data
are preserved here. Working optimizer temp files are removed after normal exit;
published snapshots are retained. Both training children exit0 and are reaped.
Before/after swap is1,809.88MiB in both phases; snapshots do not rule out transient
swap or measure other applications' latency. No developer-Mac model execution.

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
unqualified. The measured outcome above records the completed pilot; this section
retains its preregistered conditions.
