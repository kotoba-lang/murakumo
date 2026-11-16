# JUN-936: model and optimizer sharding

Active owner goal:3B distributed training, including three physical fleet M4s,
each requested10GiB/fatal9GiB, speed/loss/communication and saved-state restart.
No developer-Mac model execution. Full-model replication is not this acceptance.

`sharded_model.hy` assigns contiguous layers to their only owner. Embedding/input
and tied output head/final norm have one owner (rank0). Per-key initialization
defines the same global random model independently of2/3-rank layout. No teacher
checkpoint is imported. FP32-master SGD is assigned to the same owners on NVMe.
Activations, shared mainKV/index state, CED source and their cotangents cross
boundaries; weights/optimizer tensors do not travel with activations.

## Boundary correctness unit

Fleet naphtali only, small F32 full-model oracle vs sequentially composed2/3
logical stages, tiny512 vocabulary,12layers, sequence16/window4/chunk4,
attention/MoE checkpointing. Compare loss and all155 gradients against `model.loss`.
Both layouts match loss exactly and have maximum gradient difference9.313226e-10
or less. The test includes shared mainKV/indexer, decoder CED source and both
uses of embedding. Existing absolute2e-5/relative1e-4 controls were not loosened.
Requested2GiB/fatal1GiB, peak footprint109,691,504bytes, supervisor exit0/reaped.
This is the local-on-fleet mathematical control, not physical distributed3B.

Three-node qualification, physical pipeline, optimizer/global clipping, global
checkpoint/refusal controls and real3B save/restart were pending at that unit.
Original single-rank checkpoint-bound sources remain unchanged. No existing
service, credential/host key or unrelated workspace is changed.

## Physical2/3-owner controls

Both physical layouts complete three F32 SGD updates on the small512-vocabulary
model. Each rank compares every owned gradient, weight and FP32-master value to
the complete single-host mathematical oracle before/after each update. Keys
cover the full model exactly once. Maximum gradient error3.516675e-6, maximum
weight/master error2.980233e-8; loss error<5e-7, global norm error<1.1e-5.
Existing2e-5 absolute controls are retained. Every child exits0/reaped without
timeout under requested2GiB/fatal1GiB. These are real separate-M4 runs, but the
full-model oracle is small; they do not constitute3B capacity measurements.

The first3-rank attempt refused an unsupported uint32 selection-state dtype;
its peers reported loss and all children exited1/reaped. Add explicit uint32
transport (discrete state only); retain those failed supervisors. The successful
rerun is physical3-v2, not a reinterpretation of the failed run.

benjamin is a distinct M4/16GiB/macOS26.7.2 host, free90%, swap0, wired link-local.
The existing pinned runtime/site-packages/corpus were streamed directly from
naphtali, with no package downloads or GB storage on the developer Mac. Its
CPU128MiB/Metal384MiB positive-allocation then fatal-limit controls both pass
and bind its own qualification receipt to host/OS/source. No daemon/systemwide
limit or pre-existing job was changed. Whole-interface counters and transfer
bytes are distinct from measured training.

Global checkpoint and actual3B save/restart remained pending at that unit.

## Global checkpoint / separate-process continuation controls

Each owner writes its own weight/FP32-master tensor files with hashes, headers,
config/options/source/runtime/corpus identity, sampler and local MLXRNG state.
Files/directories are fsynced before atomic local publication. All ranks agree
on common state and every rank's sealed shard manifest, then write and read back
the same global commit certificate. A prepared local directory is not a completed
global snapshot. Load independently checks every local file, ownership/layout,
counts, source/runtime/data/options and the certificate before tensor loading,
and requires all ranks to agree on one generation. No layout-changing resume.

Small F32 physical3-rank training on the same32K train-only tokenizer:2 updates,
global checkpoint, actual new processes2 more updates. Independently compare
against uninterrupted4 updates. All155 owned weights and FP32 masters match
exactly across the partition, both at restored boundary and final continuation.
Sampler input sequence and local MLXRNG state are exact; boundary held-out CE
is identical. Continuous and resumed final global-manifest hashes are identical.

Six cooperative load refusals: missing global commit, corrupted tensor, missing
master, changed optimizer options, mixed valid global generations, and partial
directory. One rank's defect causes all ranks to refuse. Instrumentation forbids
both mx.load and MasterStore construction in these cases; neither is reached.
One rank's failed prepare also prevents every COMMITTED certificate. All controls
pass under requested2GiB/fatal1GiB, no timeout, children reaped. The initial
checker-only parenthesis error exited before model work; its supervisors remain
recorded and the corrected checker passes asv2. Executing trainer/source remained
unchanged between prefix, suffix and continuous runs.

## Preregistered actual3B measurement

Model config3b:3,003,341,824 total parameters,24 layers,width1024,64experts,
top4,FF608,16heads,64head-dim,CSA2-style/sharedKV/SWA/CED unchanged. Random
per-key initialization(seed936), no imported pretrained model. BF16 weights/
gradients, owner-local FP32 SGD/NVMe masters, lr0.001/decay0.01/global clip1.
Sequence64/globalbatch1, same sampler/tokenizer/corpus and fixed2-batch128-token
held-out CE. Compare2 and3 physical owners, contiguous12/8 layers per owner,
with one tied embedding/head owner(rank0). Each layout uses2 updates, global
checkpoint2, separate processes restore then2 more updates/global checkpoint4.
Total4 updates/256 global training tokens counted once per layout.

All ranks request10GiB and independently read back XNU active/inactive fatal9GiB.
Outer deadline600s/rank/process, serial layout/phase execution, no cap increases
or stopping unrelated jobs. If a condition fails, preserve its terminal evidence
and do not claim capacity success. Original7 checkpoint-bound sources remain
unchanged. Actual links1Gb/s; model/master weights never move with activations.
The serial model-parallel schedule has no pipeline overlap or EP dispatch.

Measure maximum-rank phase times and supervisors (including init/load/eval/save),
actual partition counts, local compute, collective transfer/metadata/barrier wait,
logical boundary payloads/ring estimates/whole-en0 traffic, process memory and
NVMe I/O, loss/norm/input hashes, source identity, checkpoint files/global seals,
and actual resumed step/sampler/boundary CE. Transfer and peer-compute/barrier
wait are separate. Global byte counts and per-rank collective estimates must not
be summed as exclusive wire traffic. Short runs do not establish convergence or
sustained scaling.3B results were pending at that implementation unit.

## Actual3B results and separate-process full readback

The complete planned2+2 processes finish for both physical layouts. Executing
training sourcec6fd4fa0 is unchanged across every3B process and all hosts. The
original seven sources are unchanged. Global keys cover304 parameters tensors
exactly once, with3,003,341,824 coefficients; no rank holds the full model.

| Metric | Two owners | Three owners |
| --- | ---: | ---: |
| Parameters per owner (B, decimal) | 1.518564 /1.484777 | 1.023462 /0.990204 /0.989676 |
| BF16 model bytes/owner (GB) | 3.0371 /2.9696 | 2.0469 /1.9804 /1.9794 |
| FP32 master bytes/owner, NVMe (GB) | 6.0743 /5.9391 | 4.0938 /3.9608 /3.9587 |
| Updates /global tokens | 4 /256 | 4 /256 |
| Summed maximum-rank phase wall | 51.7020s | 38.6057s |
| Global phase tokens/s | 4.9514 | 6.6312 |
| Summed maximum supervisor wall | 120.5957s | 79.2644s |
| Init/load/eval/save-inclusive global tokens/s | 2.1228 | 3.2297 |
| Maximum process footprint /requested /fatal | 8.4759 /10 /9GiB | 6.5804 /10 /9GiB |
| Common initial128-token CE | 10.599606 | 10.599606 |
| Final CE after4 updates | 10.615460 | 10.604201 |
| Actual checkpoint2 load (max rank) | 7.6529s | 4.1036s |
| Saves2/4 (max rank per save) | 27.1046 /26.5575s | 11.7634 /17.4549s |
| Total global checkpoint file bytes/generation | 18.0201GB | 18.0201GB |

Rates count256 processed training tokens once per layout, not per rank. Sum the
maximum rank durations separately for prefix and suffix; reporting/Git pauses
and the later independent readback are excluded. Evaluations and saves are
excluded only from phase rates, and included in supervisor rates. No cold-cache
reset/repeat trials; first-step compilation and storage variation are retained.
This single short observation does not qualify steady-state scaling efficiency.

**Neither final held-out CE improves over initialization.** Training objective
steps1–4: two-owner11.205250,11.446786,11.440002,11.182490; three-owner11.205250,
11.463551,11.473864,11.137591. Do not call objective changes convergence or rank
the configurations by quality. Floating execution/VJP boundaries differ with
layout; cross-layout BF16 bitwise equivalence was not asserted or qualified.

### Communication and storage accounting

Global owner boundary-array payload is8,983,472bytes (two owners) and13,557,808
bytes (three), counted globally once across both complete processes, including
their evaluation passes. Per-rank ring TX estimate across all collectives/control
is9,565,384 /19,502,944bytes. Actual whole-en0 TX per rank is12.5692/10.3585MB
(2owners) or22.5227/20.8117/22.5227MB(3owners); RX is15.0545/13.2958MB or
23.9866/22.7812/24.0165MB. These include background/control; exclusive wire bytes
remain null. Broadcast is owner-data-plus-zeros all-sum, so ring estimates are
not useful point-to-point lower bounds. No full model/master tensor is sent
through the training transport. Both actual links stay1Gb/s.

`mesh` channels separate boundary tensor transfer, metadata and barrier wait.
Barrier wait includes other owners' compute/IO. The per-step optimizer+finish
wait already includes its finishing barrier: do not sum those overlapping
accounting fields twice. NIC and `mesh` totals include init/evaluation/save;
backward channels correspond to training only. Raw per-channel records are kept.

OS per-process disk reads/writes (GB), summed prefix+suffix: two-owner rank0
40.7761/54.6748,rank1 41.8762/53.4583; three-owner rank0 24.0207/36.8489,
rank1 21.8588/35.6517,rank2 24.6093/35.6343. Logical master IO and restore-file
bytes are separately recorded in every result. Physical counters include
init/load/eval/save/hash and are not exclusive SSD/device measurements.
Swap is unchanged in every before/after snapshot: naphtali1801.88MiB,
issachar0,benjamin0. Transient/other-workload effects are not measured.

### Save, actual restart and full-file verification

The2/3-rank prefix creates checkpoint2; new process IDs load that agreed global
generation and restore step2, optimizer masters and sampler, then train tostep4.
Loaded initial CE is exactly the preceding final CE in both layouts. All global
manifest/certificate hashes agree across their ranks. A later separate process
re-reads and verifies both generations' every local file hash/header/shape/dtype,
ownership/config/options/source/runtime/data/global seal, then checks agreement
across all ranks. Each global generation has608tensor files (304weight+304master),
18,020,111,288 file bytes. Both layouts change249weight and304master shard hashes
between steps2/4. Sampler states independently reconstruct all4 input hashes
from corpus/seed; the two layouts' complete training input streams match.

Full readback supervisors exit0/reaped; all608files in each of both generations
pass for each layout. This is a separate-process readback using the same format
validator, not an independent implementation or whole-BF16-trajectory parity
claim. Small F32 continuous/resumed parity and boundary checks remain the numeric
control. All actual3B training children exit0/reaped without timeout; final readback
finds no owned model/checker/supervisor processes or task-port listeners. Verifier
source SHA7e412e7b… matches all three deployed receipts. GB checkpoints stay on
fleet, and the earlier1B checkpoints/services/keys/unrelated WIP remain intact.

`summarize_3b.hy` imports only stdlib and checks actual settings, full ownership,
source identities, limits, exits, sampler, global checkpoint generations and
readback before deriving `3b-summary.json`. It does not execute a local model.
The original goal's bounded3B2/3-node capacity/update/speed/loss/communication/
same-layout save-restart verification is met. Sufficient pretraining/quality,
AdamW state, EP, overlapped pipeline, elastic recovery/resharding and30B duration
remain open. No developer-Mac model work or production deployment occurred.
