# JUN-936: two physical M4 replicated-training verification

2026-11-06 JST. Fleet naphtali and issachar, each M4/16GiB/macOS26.7.2,
Hy1.1.0/MLX0.32.4/portable CPython3.12.12. Actual en0 links negotiate1Gb/s.
No training/GPU benchmark on the developer Mac. Checkpoint was streamed to
the second fleet host without storing GB model data on the developer Mac.

## Controls completed before1B

Both hosts have independently qualified kernel fatal limits; source/OS/host
binding and readback are preserved. Small controls request2GiB with1GiB kernel
threshold. Both physical ranks use identical model initialization, source/data
hashes/settings and different local data from the same global sampler stream.

The F32 tiny model has2,807,616 parameters (train-only32K vocabulary), window4,
chunk4, sequence16, batch1/rank, two updates. Reference computes the mean of
the two local objectives sequentially. All155 first-step gradients compare
within max1.4901161e-8. Final weights and FP32 masters match the reference
exactly. The two physical ranks also have identical final weight/master hashes.
Global norm clipping follows gradient averaging. MoE balancing stays local;
batch2 loss evaluated once has different balancing semantics and is not the
reference.64MiB buckets limit collective buffers and preserve gradient dtype.

Missing MLX rank/hostfile fails before output/model initialization. Deliberately
exiting rank1 produces a peer-loss error in rank0: both exit1 in about0.39s,
no timeout, children reaped, no complete result. Only owned task processes are
affected. This demonstrates bounded failure reporting, not fault recovery.

## Preregistered1B trial

Explicitly import the SHA-verified step64 single-host checkpoint as common
replica initialization. Preserve its strict original source/options validation;
do not relabel this as distributed resume. New trial: BF16 weights/gradients,
FP32 SGD/NVMe masters, same lr/decay/clip, context64, local batch1, global batch2,
four updates. Context64 bounds sequential-reference accumulation memory; it
does not exercise window128 truncation (the small control does).

Compare two physical ranks with a single-host sequential mean of the same two
local objectives and same imported state/sampler. Per host requested10GiB,
kernel fatal threshold9GiB. Outer deadlines300s/rank and180s/reference.
No cap increases, unrelated process stops or global checkpoint publication.

Report global tokens once (not sum both rank counters), divided by maximum
rank-local phase duration. Also report init/verification-inclusive time,
compute/communication/optimizer+finish-sync, logical gradient bytes, ring TX
estimate, whole-interface counters (background/control included, not exclusive
wire bytes), process memory/NVMe counters and final replica hashes. Final weight
exports/master hashes are verification artifacts, not resumable global state.
OS cache is not reset. The trial below executes the unchanged training sources
from2d383f08; the seven original checkpoint-bound sources are unchanged.

## Completed1B observation

Both physical ranks complete all four updates and exit0 with children reaped,
no timeout. Each holds the full1,043,698,432-parameter model; global512 training
tokens are counted once. CPU-only reconstruction from the source checkpoint's
sampler and corpus verifies all four global reference hashes and both distinct
rank-local input hashes. See `distributed-1b-input-alignment.json`.

| Metric | Sequential reference | Two-host DDP |
| --- | ---: | ---: |
| Global updates / tokens | 4 /512 | 4 /512 |
| Training phase | 44.0344s | 96.8452s (maximum rank duration) |
| Training phase tokens/s | 11.6273 | 5.2868 |
| Init/eval/export/hash verification-inclusive supervisor | 56.5130s | 108.2112s (maximum rank duration) |
| Supervisor-inclusive global tokens/s | 9.0599 | 4.7315 |
| Maximum kernel footprint | 6.7958GiB | 4.9708 /4.9704GiB per rank |
| Maximum MLX allocation | 6.6073GiB | 4.7527GiB per rank |
| Process I/O read / write | 19.9825 /22.9587GB | rank0 22.5149 /22.9650GB; rank1 27.0223 /22.9659GB |
| Fixed2-batch CE before / after | 10.48991 /10.46641 | 10.48991 /10.47732 |

Per-rank training rates represent the **same global512 tokens**: do not add
them. This short context64/globalbatch2 observation is not a speedup comparison
against the earlier context256/globalbatch1 long run. DDP is0.4547x the matched
sequential phase rate in this observation. Reference and DDP are independent
executions without a cold-cache reset; no statistical speedup qualification.

Each update synchronizes2,087,396,864 logical gradient bytes per rank through
49 buckets. World2 ring TX estimate is also2,087,396,864 bytes/rank/update;
four updates total8,349,587,456 per rank. These are not exclusive wire bytes.
Whole-en0 deltas: rank0 TX8.7420GB/RX8.7536GB; rank1 TX8.7490GB/RX8.7421GB.
Checkpoint transfer finished before measured counters. Background/control
traffic remains included. Actual negotiated link is1Gb/s on both nodes.

Steps2-4 median rank0 compute1.2180s, gradient communication18.4851s,
optimizer+finish-sync4.2381s, total24.0274s. Communication occupies77.29% of
the full rank0 training phase. No compute/communication overlap is implemented.
These measured costs explain the observed slowdown in this trial; they do not
predict10Gb/s throughput or100/1000-node efficiency.

Per rank, logical FP32-master reads and writes are each16,699,174,912 bytes,
plus4,174,813,516 restored master-file bytes. OS process I/O includes setup,
validation and final export/hash verification. It is not exclusive SSD/device
traffic, and cache effects are not isolated. System swap stays1,801.88MiB on
naphtali and0 on issachar before/after; transient/other-workload effects are
not measured. Limits remain requested10GiB/kernel fatal9GiB throughout.

Final two-rank weight file SHA256:
`704a76b07c0ca8f5636a9407fdf5649ce1bdcf9e59286b1bce69138ff2b0777c`.
FP32-master shard aggregate SHA256:
`57abc705714ef2edb18fd253dbe941a58a314f785b489ed0e3fac3487eaef003`.
Both match exactly across ranks. The runner refuses completion on mismatch.

All203 final tensors/1,043,698,432 coefficients were compared against the
sequential BF16 reference on fleet under the same fatal9GiB threshold.
72,411 coefficients differ (0.006938%); maximum absolute difference0.000244140625.
This is an observation, **not a newly selected BF16 pass tolerance**. BF16
sequential-vs-DDP bitwise equivalence and the cause of each difference are not
qualified. Small F32 gradient equivalence remains the correctness control.
The comparison binds both export hashes and its own executing source hash.

Raw `oneb-*.json`, supervisors, before/after snapshots, input alignment,
weight comparison and `oneb-summary.json` preserve conditions/arithmetic.
Final readback finds no owned trial/comparison processes or listeners on the
three trial ports. GB exports and original checkpoint remain on fleet only.
No global checkpoint or distributed restart has been implemented or tested.

The2-batch CE decrease over4 updates is not convergence/quality qualification;
the earlier64step fixed held-out CE worsening remains in the checkpoint record.
Next acceptance: fixed-token gradient-accumulation communication comparison,
then capacity-qualified PP/EP or optimizer sharding with shared-KV/CED/tied
gradient controls and a global checkpoint.3B/30B calibration remains open.
