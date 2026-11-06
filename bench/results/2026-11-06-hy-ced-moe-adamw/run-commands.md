# Fleet command provenance

Executing learner source:7229876e. All commands run from the existing fleet
workspace; management Mac only copies source/JSON and uses SSH. Each command
uses its own rank0/1/2 on issachar/levi/simeon and ring-adam-3.json, with
PYTHONPATH=site-packages:code and the pinned standalone Python3.12/Hy/MLX runtime.
Source fingerprints and runtime versions are embedded in every raw result.
Existing runtime/data copied from qualified fleet, no new package download.

Controls: bounded_trial.hy --seconds90 ->check_adamw_physical.hy --world-size3;
adamw_train.hy tiny --steps2 --seq16 --eval-batches2 --eval-every2 then
new-process resume checkpoint2 for2more; fresh continuous4;
check_adamw_checkpoint.hy with9 refusal cases. All requested2/fatal1GiB.

Screen: three serial3-rank runs, bounded_trial.hy --seconds900,
adamw_train.hy --preset3b --world-size3 --data data --memory-limit-gib10
--steps8 --seq64 --eval-batches8 --eval-every8 --no-save.
Optimizer/LR pairs:sgd/.001,adamw/.0001,adamw/.0003.
4step linear warmup thenconstant, beta.9/.95,eps1e-8,decay.01,clip1.
No confirm flag; test split not consulted for selection.

Long-run template (only after frozen selection):bounded_trial.hy --seconds2400,
adamw_train.hy --preset3b --world-size3 --data data --memory-limit-gib10
--steps32 --seq64 --eval-batches8 --eval-every8 --confirm with selectedoptimizer/LR.
Second process adds --resume adam-long-prefix/checkpoint-32 and distinct output.
All MB/GB-sized checkpoints remain on their owner nodes.

Raw JSON records actual args/options, output paths, supervisor deadlines,
exit/reap state and source hashes; this template alone is not execution proof.
