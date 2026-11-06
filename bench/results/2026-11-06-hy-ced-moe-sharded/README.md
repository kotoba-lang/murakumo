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
sustained scaling.3B results are pending at this checkpoint implementation unit.
