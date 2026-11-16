# Executed3B protocol

Training sourcec6fd4fa0. Existing pinned Hy1.1.0/MLX0.32.4/NumPy2.5.4/
portableCPython3.12.12, each fleet M4/macOS26.7.2. Use the task workspace's
`python`, `site-packages`, `data` and `code` directories; no new package download.
Every host must first have its own successful kernel-limit qualification receipt.

Run concurrent ranks0/1 on naphtali/issachar forworld2, or0/1/2 on those two
plus benjamin forworld3. The existing two-entry `ring-ddp-1b.json` uses port25673;
`ring-sharded-3.json` adds the third physical wired address on port25681.
Do not reuse an existing output directory, run overlapping conditions, or change
the memory envelope. The code validates distinct hosts/settings/source/data.

Rank0/world2 prefix, with simultaneous rank1 changing only `MLX_RANK`:

```sh
env MLX_RANK=0 MLX_HOSTFILE="$PWD/ring-ddp-1b.json" PYTHONPATH="$PWD/site-packages" \
 ./python/bin/python3.12 -m hy code/bounded_trial.hy --seconds 600 \
 --output shard3b-w2-prefix-supervisor.json -- \
 ./python/bin/python3.12 -m hy code/sharded_train.hy --preset 3b --world-size 2 \
 --data data --output shard3b-w2-prefix --memory-limit-gib 10 --steps 2 --seq 64
```

After both children finish, launch new suffix processes with the same arguments,
output/supervisor `shard3b-w2-suffix`, and
`--resume shard3b-w2-prefix/checkpoint-2`. All rank files stay on their original
owners; this is a real global same-layout restore, not replica initialization.

After both suffix ranks finish, repeat prefix/suffix withworld3, the three-entry
hostfile, and all output/supervisor names changed to `shard3b-w3-*`. Each rank
has its own local output and master store. Around every run record UTC,
`memory_pressure -Q`, `sysctl vm.swapusage` and `df -h .`, preserving the actual
supervisor exit status. All runs retain requested10GiB/fatal9GiB and600s limits.

Once all training finishes, run a separate readback cohort per layout:

```sh
env MLX_RANK=0 MLX_HOSTFILE="$PWD/ring-ddp-1b.json" PYTHONPATH="$PWD/site-packages" \
 ./python/bin/python3.12 -m hy code/bounded_trial.hy --seconds 180 \
 --output shard3b-w2-readback-supervisor.json -- \
 ./python/bin/python3.12 -m hy code/verify_sharded_artifacts.hy --world-size 2 \
 --prefix shard3b-w2-prefix/checkpoint-2 --final shard3b-w2-suffix/checkpoint-4 \
 --output shard3b-w2-readback.json
```

Start both ranks concurrently; then separately useworld3/three-entry hostfile/
matching names and three concurrent ranks. The verifier checks every shard and
global generation and independently reconstructs sampler states/input hashes;
it does not execute a model. Keep the GB artifacts on fleet. Retrieve only the
small reports/supervisors/snapshots and final source/process/listener receipts.
`summarize_3b.hy` is stdlib-only evidence arithmetic, not model execution.
