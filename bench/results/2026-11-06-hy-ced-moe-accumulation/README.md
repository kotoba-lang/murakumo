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

## Completed1B comparison

All three conditions complete on unchanged executing training source176bf401.
Every supervisor exits0, no timeout, child reaped. All8 global input-pair hashes
and16 distinct rank-local hashes are reconstructed from checkpoint sampler and
corpus for each DDPrun. Both DDPruns have exact final weight/master hash agreement
across their two physical ranks. `summarize.hy` checks source/state/data/limits,
global tokens, batch/update counts, synchronizations/buckets/bytes, supervisors,
input reconstruction and all comparison-export bindings before writing summary.

| Metric | Sequential reference | DDP every micro | DDP after4 micros |
| --- | ---: | ---: | ---: |
| Global tokens / effectivebatch / SGD updates | 1024 /8 /2 | 1024 /8 /2 | 1024 /8 /2 |
| Training phase (maximum rank duration forDDP) | 30.2149s | 169.6364s | 56.5924s |
| Global training phase tokens/s | 33.8906 | 6.0364 | 18.0943 |
| Init/eval/export/hash-inclusive supervisor | 38.8618s | 179.0917s | 68.1870s |
| Supervisor-inclusive global tokens/s | 26.3498 | 5.7177 | 15.0175 |
| Full-gradient averaging calls per rank | 0 | 8 | 2 |
| Logical gradient bytes per rank (decimalGB) | 0 | 16.6992 | 4.1748 |
| Whole-interface TX rank0 /rank1 (GB) | 0.0008 /n/a | 17.4911 /17.4962 | 4.3715 /4.3749 |
| Maximum footprint per rank (GiB) | 6.7961 | 6.8272 /6.8277 | 6.8267 /6.8256 |
| Same128-token held-out CE initially | 10.489908 | 10.489908 | 10.489908 |
| Held-out CE after two updates | 10.473276 | 10.476797 | 10.469759 |

The sync-at-update phase is2.9975x faster than sync-every-micro; supervisor
speedup is2.6265x. Logical gradient traffic drops75%. Actual1Gb/s links remain
unchanged. Global tokens/rates are never summed across ranks. Even deferred-sync
DDP remains slower than this matched single-host sequential reference.

Rank0 phase totals in seconds: reference compute22.1256/communication0/
optimizer+finish-sync8.0891; every-micro10.8344/150.5509/8.2499; after4 micros
11.3582/37.1285/8.1042. Communication falls with the measured averaging calls.
The after4-micro phase still spends65.61% in gradient/scalar communication.
Logical bytes count only gradient tensors; scalar/barrier/digest/control traffic
is separate and included in the whole-interface observations. NIC deltas are
not exclusive wire bytes and include background. Ring TX estimates equal logical
bytes atworld2; exclusive wire bytes remain null.

Training objective (CE+aux), first→second global update: reference11.046326→
10.959880; every-micro11.046326→10.956983; after4 micros11.046326→10.965228.
The same local balancing semantics and optimizer budget are maintained. BF16
rounding/order differs: against reference, both modes have maximum absolute
final-weight difference0.000244140625 across all203tensors/1,043,698,432
coefficients (50,086 changed for micro;51,672 for update). Micro-vs-update
also has max0.000244140625 with53,373 changed coefficients. No new tolerance,
bitwise equivalence or quality ranking is claimed. EachDDPrun's two replicas
do match exactly. Three2-update/128-evaluation-token observations do not establish
convergence or sustained language quality. Prior64step held-out worsening remains
in the checkpoint evidence; do not replace that endpoint with this tiny trial.

Process disk reads/writes (decimalGB): reference14.1781/14.6140; every-micro
rank0 14.6296/14.6140 andrank1 15.9700/14.6069; after4 micros rank0
14.2048/14.6140 andrank1 15.4237/14.6140. Logical master reads/writes are
8.3496GB each per process in every condition, plus4.1748GB restored master
files. This holds optimizer work fixed while synchronization changes. Process
counters include initialization/evaluation/final export/hash; no exclusive SSD
device attribution or cold-cache/thermal claim. Snapshots show unchanged swap
on each host: naphtali1,801.88MiB andissachar0 before/after every condition.
Every requested10GiB/fatal9GiB readback passes; all train footprints<6.828GiB.
Fleet-only full-weight comparisons also pass their lower9GiB fatal threshold.

All raw reports, supervisors, before/after snapshots, input reconstructions,
full per-tensor comparisons and command protocol are retained here. GB exports
and original checkpoint remain on fleet. Final readback finds no owned trial/
comparison process or task-port listener. No unrelated service/process was stopped.

This meets the short fixed-token synchronization comparison. Sufficient training,
BF16 accumulation precision studies, state sharding/PP/EP, global checkpoint/
recovery,3B and30B calibration remain open. A further scaling experiment must
preserve the effectivebatch/update controls and qualify the learner's capacity.
