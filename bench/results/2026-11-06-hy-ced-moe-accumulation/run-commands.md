# Executed protocol

Executing training source:176bf401. Hy1.1.0, MLX0.32.4, portableCPython3.12.12,
NumPy2.5.4, macOS26.7.2. Use the existing fleet task workspace with its pinned
runtime/data and independently qualified memory-limit receipt. The original
step64 snapshot is immutable. No package install or system configuration change.

Single-host reference, on naphtali (logs/supervisor/snapshots retained):

```sh
env PYTHONPATH="$PWD/site-packages" ./python/bin/python3.12 -m hy code/bounded_trial.hy \
  --seconds 240 --output ga-1b-reference-supervisor.json -- \
  ./python/bin/python3.12 -m hy code/distributed_trial.hy \
  --mode reference --preset 1b --data data --output ga-1b-reference \
  --initialize-from-single long1b-part-b/checkpoint-64-rank-0 \
  --memory-limit-gib 10 --steps 2 --seq 64 --accumulation-steps 4
```

DDP rank0 below runs on naphtali. Concurrent rank1 runs on issachar, changing
`MLX_RANK=1` and initialization path to `ddp-init64/checkpoint-64-rank-0`.
The existing two-entry `ring-ddp-1b.json` binds both actual Ethernet addresses
to port25673. Each host owns its supervisor/output; no shared output directory.

```sh
env MLX_RANK=0 MLX_HOSTFILE="$PWD/ring-ddp-1b.json" PYTHONPATH="$PWD/site-packages" \
  ./python/bin/python3.12 -m hy code/bounded_trial.hy \
  --seconds 420 --output ga-1b-micro-supervisor.json -- \
  ./python/bin/python3.12 -m hy code/distributed_trial.hy \
  --mode ddp --preset 1b --data data --output ga-1b-micro \
  --initialize-from-single long1b-part-b/checkpoint-64-rank-0 \
  --memory-limit-gib 10 --steps 2 --seq 64 --accumulation-steps 4 --sync-frequency micro
```

After both ranks exit, repeat the DDPrun with `--sync-frequency update`,
distinct `ga-1b-update` output/log/supervisor names and the same other arguments.
An output directory must not already exist. Around each command, record UTC,
`memory_pressure -Q` and `sysctl vm.swapusage`; preserve its exit code. Current
source, corpus, initial-state seal and actual kernel-limit readback are also
bound in each result. No developer-Mac training or weight comparison.

On fleet, run `verify_distributed_inputs.hy` for each pair against reference,
and `compare_weight_exports.hy` for reference-vs-micro, reference-vs-update and
micro-vs-update. The comparison exports remain on fleet. Copy small JSON/text
receipts only. `summarize.hy` is stdlib-only arithmetic and checks the retrieved
evidence; it does not import MLX or execute a model.
