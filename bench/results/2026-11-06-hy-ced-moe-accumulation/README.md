# JUN-936: gradient accumulation with fixed tokens and effective batch

2026-11-06 JST. Owner requests fleet-only training, per-node10GiB maximum.
naphtali/issachar are physical M4/16GiB hosts with independently qualified
XNU fatal9GiB footprint limits (requested10GiB,1GiB reserve). Small controls
request2GiB/fatal1GiB. No developer-Mac model execution or GPU benchmark.

## Protocol fixed before1B measurement

Import the same strict step64 single-rank snapshot as new replica initialization,
not distributed resume. Original seven checkpoint-bound sources remain unchanged.
Same1,043,698,432-parameter1B, BF16 parameters/gradients/accumulation, FP32
SGD/NVMe master, lr0.001, decay0.01, global clip1, seed936, sequence64,
local microbatch1, train-only tokenizer/corpus and restored sampler.

All three conditions process the same ordered1,024 global tokens: two optimizer
updates with effective globalbatch8. Reference computes8 local objectives per
update sequentially. DDP computes4 local microbatches/rank/update:

1. Reference: no network gradient synchronization.
2. DDP `--sync-frequency micro`: average across ranks after every microbatch,
   then accumulate four results, divide by4 and update once. Eight full-gradient
   averaging calls across the trial. This is the sync-every-micro accumulation
   control, not eight optimizer updates or a different effective batch.
3. DDP `--sync-frequency update`: accumulate four local gradients, divide by4,
   then average across ranks once per update. Two full-gradient averaging calls.

All use `--steps 2 --seq 64 --accumulation-steps 4`. The objective is the mean
of eight local microbatch losses including local MoE balancing. It is not one
batch8 loss with different balancing statistics. Global clipping and SGD/decay
happen only after the full gradient mean. BF16 summation order differs by mode;
numerical equivalence is observed, not assumed. Each current gradient is consumed
and materialized per parameter to bound overlap; no retained activation graphs.

Reference outer deadline240s, each DDPrank420s. Runs are serial by condition;
two ranks within a DDPrun execute concurrently. No cap increase or unrelated job
stop. Runtime/corpus/checkpoint downloads are not required. Both actual Ethernet
links negotiate1Gbps. Order: reference, sync-every-micro, sync-at-update.

Compare global tokens once over maximum rank-local phase duration, also maximum
supervisor time including initialization/evaluation/export/hash verification.
Record initial/final fixed held-out CE (same2batches), per-update training
objective and norm, compute/communication/optimizer+finish-sync, logical/ring
gradient bytes, whole-interface TX/RX, process disk counters, logical master
traffic, memory and before/after swap. Interface traffic includes control and
background; exclusive wire bytes remain null. No cache reset, repeat trials or
causal thermal/I/O attribution. Report this single-condition-order observation
without claiming sustained scaling or convergence.

Verify same source/state/data settings, sampler/input hashes, optimizer counts
and all final replica weight/master hashes. Compare all BF16 reference exports
on fleet under fatal9GiB; do not introduce a post-hoc BF16 pass tolerance.
These are verification artifacts, not global restart checkpoints. PP/EP, memory
pooling, fault recovery and30B calibration remain outside this acceptance.

## Fleet controls completed before1B

F32 tiny2,807,616 parameters, seq16, effectivebatch8, two updates/256tokens,
same accumulation modes. Both physical DDPranks complete with exact matching
weight/master hashes in each mode. Against the sequential reference, all155
first-update gradients have maximum absolute difference5.9604645e-8 for both
modes. Final weight/master maxdiff4.6566129e-10 (micro sync),3.7252903e-9
(update sync), within the existing FP32 control tolerances. No loosened gate.
Unsupported accumulation3 exits1 before output/model/collective creation;
supervisor reaps it without timeout. Original missing-config/peer-loss controls
remain in the earlier distributed record; this unit does not claim new recovery.

At this implementation unit1B measurements are pending.
