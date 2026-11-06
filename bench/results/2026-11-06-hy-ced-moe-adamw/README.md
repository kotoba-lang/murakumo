# JUN-936: owner-state AdamW and Co-Scientist experiment loop

Owner constraints: eventual16GiB×10Mac training,16GiB single-Mac inference,
Bonsai-class small-memory quality target. This unit is a3-node training pilot,
not10-node or inference qualification. Fleet only; no developer-Mac model work.
Reuse JUN-936/parent JUN-931 and native Bonsai reference JUN-187.

The study adapts Google's hypothesis generation, critique, ranking and iterative
refinement method, with falsifiable hard gates and measured candidate ranking.
It does not claim access to/execution of Google's service or independent multi-agent
review. `research-ledger.json` records hypotheses, criticisms, candidate settings,
acceptance, evidence and decisions. Primary sources are linked there.

Pin3.003B architecture/data/seed/context64/globalbatch1 across all runs. Screen
SGD lr.001 vsAdamW lr1e-4/3e-4 for8updates/512tokens, all with4step warmup then
constant rate. AdamW usesbeta1=.9,beta2=.95,eps1e-8,decay.01,global clip1,
bias correction enabled. Development8batches/512tokens selects the candidate;
this compares configured optimizer/LR bundles rather than isolating an optimizer
causal effect. No screen checkpoints. After publishing selection, fresh-init the
chosen AdamW run for64updates/4096tokens,32+32 separate-process restart,
checkpoint32/64, fixed development evaluation every8steps. Independent final
test16batches/1024tokens(seed2027) is not a selection criterion. Acceptance is
test CE decrease>=.02 and>=12/16 paired batches improve, with all memory/numeric/
checkpoint gates passing. Failure remains recorded and triggers hypothesis revision.

Choose issachar/levi/simeon with enough disk for full FP32 master/m/v workspace
and two retained global checkpoints. Old naphtali/benjamin SGD and1B artifacts
remain untouched. New hosts use copied pinned runtime/data and independently
qualified CPU/Metal fatal limits. Requested10GiB/fatal9GiB for real3B; controls
requested2GiB/fatal1GiB. No cap increases or unrelated job/service stops.

## Optimizer equation/placement unit

`optimizer_shards.hy` keeps each owner's master,m,v FP32 tensors on local NVMe
and streams one parameter at a time. Global clipping precedes bias-corrected
AdamW/decoupled decay. Six varying-gradient updates including4step warmup match
MLX0.32.4 AdamW with explicitbias_correction=True exactly in weights; master/m/v
agree with an independentNumPy float64 equation oracle to<9.27e-8. Predetermined
absolute2e-6 bound was retained. Control exits0/reaped under1GiB fatal,82.56MB
footprint. Physical3B capacity and global m/v restart are pending at this unit.

Future inference reference is pinned to HF revision839bd570…; model-card weights
PTQ1_0 5.95GB/PQ2_0 7.21GB are distinct from whole-process8GB acceptance and
publisher intelligence metrics are distinct from our own measurements.

## Physical optimizer and global checkpoint unit

Three distinct M4 owners execute a small F32 full-model oracle comparison for
three updates. All owned gradients and weights/master/m/v pass the pre-existing
2e-5 absolute bound (gradients also rtol1e-4). Maximum gradient error2.414e-6,
weight/master1.193e-7. A separate2+new-process2 restart vs continuous4 restores
all four kinds exactly at boundary, sampler and MLXRNG exactly; continuation
maxweights/master3.316e-7,m3.598e-8,v1.921e-9 (below2e-5, not bitwise parity).
Nine cooperative refusal cases include missingm/v, corrupt tensors, wrongbeta,
missingcommit, mixedgenerations and partialsnapshots, all before tensor loading
or OptimizerStore allocation. Failed prepare never produces globalCOMMITTED.
All small controls exit0/reaped under requested2/fatal1GiB. Actual3B capacity,
held-out quality, m/v long checkpoint and64update study remain pending.

New modules preserve legacy source-bound SGD checkpoints. Full FP32 optimizer
states are owner-local NVMe files; only one parameter's master/m/v is streamed
for update. Checkpoint certificates require all owners and same-layout reload.
The `rank*/` files retain raw result/supervisor evidence.

## Actual3B matched screen and frozen selection

Each condition processes the same ordered512tokens/8updates with4step warmup,
seq64/globalbatch1,3physicalM4/16GiB owners. All hard guards, finite updates,
source/runtime/data/settings cohorts, disjoint304-key ownership and exit/reap
receipts pass; peak<=6.6291GiB and observed before/after swap0.

| Configured bundle | FinaldevCE(initial10.625805) | Phase tok/s | All-in tok/s |
| -- | --: | --: | --: |
| SGD lr.001 | 10.631926 | 7.6497 | 6.9509 |
| AdamW lr.0001 | 10.588808 | 2.6344 | 2.4888 |
| AdamW lr.0003 | 10.505480 | 2.6034 | 2.4042 |

Select AdamWlr.0003 by preregistered dev rule before consulting confirmation.
This is a configuredbundle comparison, not optimizer-only causality. No repeated
trials/cache resets or general-quality benchmark claims. Logicalboundary payload
27.116MB globally once/condition including evaluations; pernodewhole-NIC
TX41.2–45.2MB, not exclusive wire bytes. Adam physical process IO pernode
read95.0–98.3GB/write106.9–110.5GB versusSGD cachedread8.5–29.8GB/write35.6–36.8GB.
Optimizer+finish wait includes peer wait and dominates Adam updates.

`summary.json` is regenerated by `summarize.hy` using the existing stdlib-only
Hy interpreter on the managementMac (no MLX/model import/execution). This dated
measurement arithmetic is not a CI gate or product runtime. Phase includes
periodic dev evaluation; update-only rate excludes it; all-in uses maxrank
supervisor including init/eval/cleanup. Confirmation/actual3B checkpoints pending.

## Long-run first phase (32/64 updates)

Fresh selectedAdamWlr.0003 completes32updates/2048tokens on all3 owners,
exit0/reaped/no timeout, maxfootprint6.63083GiB, observed swap0. Fixed devCE
10.625805→8.894307. Phase including4periodicdev evaluations2.57624tok/s;
maxrank total supervisor831.714s, save20.920s. All ranks agree on global
checkpoint32 generation4ad49037… includingBF16 weights andFP32 master/m/v.
Full3B resume/independent confirmation/all-file readback remain pending.

The extra confirmation-only forward evaluations before step1 leave model
inputs unchanged (first8input hashes equal screen), but separateBF16 VJPs
show gradient/trajectory differences. No bitwise cross-run parity or repeated
trial confidence is claimed. Final confirmation uses its own fresh initialCE.
`prefix-io-window-start.txt` samples SSD disk0 after step2; it is a partial-phase
device-wide window, not full-phase or exclusivelearner/NAND I/O.
