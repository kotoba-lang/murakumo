# Dated fleet measurement provenance

ManagementMac runs only SSH/source editing/stdlib-only receipt arithmetic.
All model allocation/computation, oracle comparisons and training use fleet.
Pinned runtime copied from qualified issachar: Python3.12.12/Hy/MLX0.32.4/
NumPy2.5.4; actual versions/source hashes in receipts. No package download.

Ranks0..9: joseph,issachar,levi,simeon,dan,judah,naphtali,benjamin,zebulun,asher.
Use existing fleet workspace `github/workspaces/codex/jun-936-hy-moe`,
PYTHONPATH=site-packages:code, MLX_RANK=rank andMLX_HOSTFILE=ring-ten.json.
All ring endpoints are refreshed wiredlink-local addresses onport25711;
SSH binds managementen8 (all links negotiate1Gbps). No network/key changes.

Own-process bounded_trial.hy supervises kernelcontrols90s, smallcontrols180s,
actual3B phases1200s and artifactreadback900s; never discovers/stops unrelatedjobs.
Fatal qualification: check_kernel_limit.hy CPU128MiB/Metal384MiB positive
allocation thenSIGKILL, newreceipt on all10 hosts, not a sampled memory cap.

Bothbackend small F32 full-model oracle controls use
`check_fleet_physical.hy --world-size 10 --state-placement ram|nvme` for3updates.
Then fleet_train.hy --preset tiny --world-size 10 --data data --memory-limit-gib 2
--seq 16 --optimizer adamw --lr 0.0001 --state-placement ram|nvme
--eval-batches 2 --eval-every 2, with2updates prefix, newprocess2suffix from
checkpoint2, freshcontinuous4. check_fleet_checkpoint.hy verifies allstate kinds,
RNG/input continuity and10 refusal cases includingwrongplacement.

Actual3B: fleet_train.hy --preset 3b --world-size 10 --data data
--memory-limit-gib 10 --seq 64 --optimizer adamw --lr 0.0003
--state-placement nvme|ram --eval-batches 8 --eval-every 8 --steps 16.
Second process uses --resume ten-3b-MODE-prefix/checkpoint-16 and distinctoutput
`ten-3b-MODE-suffix`; finalcheckpoint32. Fixedwarmup4,betas.9/.95,decay.01,clip1,
eps1e-8,biascorrectionTrue,seed936. Eachbackend2048globaltokens/32updates.
NVMe executes first, thenRAM, nocacheflush/repeat. Periodicdev512tokens is
identical; no additional hyperparameter/test selection is performed.

After actualtraining, verify_fleet_artifacts.hy --world-size 10 --data data
--prefix ten-3b-MODE-prefix/checkpoint-16 --final ten-3b-MODE-suffix/checkpoint-32
--output ten-readback-MODE.json in separatefleet processes checks everyfile.
SSDdevice snapshots use dateUTC/iostat-Id-disk0; processcounters separately.
Rawresults/supervisors/options, not this commandtemplate, prove execution.
