# JUN-936: three-seed64-update RAM/NVMe paired study with suffix replays

Read `research-ledger.json` for preregistered conditions and all acceptance
boundaries. Seeds936/937/938 vary both initialization and training sampler.
Within eachseed, RAM/NVMe use identical orderedtokens, same64updates/4096tokens
and allhyperparameters. Eachnewprocess restarts at32. A second32→64 replay per
backend starts fromtheexact samecheckpoint to measure within-conditionvariation;
it is not training to96. Fixed512token primarydev/every16, plus2048token secondary
dev at64. No testset selection, no causal/equalconvergence claim fromn3.

All modelwork is fleetonly, eachrequested10GiB/fatal9GiB, current10ownerlayout
(head-onlyjoseph+9layerowners) andexistingservices retained. Source-boundold
checkpoints are untouched. The newseedcontroller/checkpoint schema and explicit
value/firstgradient fingerprints are separatelyqualified before actual3B.

Newprimary32/64snapshots are losslessly compressed/verified and retained across
the existingfleet; only redundant new-study copies may retire after archive
verification. No modelweights are stored on the managementMac or inGit.
Coordinating/compression/statistics tools are one-shot stdlib measurement tools;
Hy/MLX training remains fleetonly. No CI/runtime fallback or workflow addition.
