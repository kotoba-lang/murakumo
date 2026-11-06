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
