# Bonsai rotated weights and distributed qualification

AWAI-147 needs a way to use Mac mini resources for the requested Bonsai 2
MTP workload. A 2026-11-03 read-only sample reached ten minis (one on retry).
Free plus inactive memory pages ranged from 1.95 to 12.26 GiB and three hosts
reported nonzero swap. These are non-simultaneous observations, not memory
reservations or allocatable capacity. GPU activity, thermal headroom, host/VM
shared reservations and work ownership remain unknown. The detailed host
snapshot remains private. Memory-pressure percentage alone is not a capacity
measurement.

The installed model configuration identifies `prism_hadamard_qwen35`. Exo's
[pinned mlx-lm revision](https://github.com/exo-explore/exo/blob/main/pyproject.toml)
contains a [Prism model](https://github.com/ml-explore/mlx-lm/blob/6b572ab1b531dd8fd2d13f6f36a89c2eccda18ea/mlx_lm/models/prism_hadamard_qwen35.py)
whose custom quantized layer applies a sign vector and Hadamard transform.
Its parent Qwen model [defines tensor sharding](https://github.com/ml-explore/mlx-lm/blob/6b572ab1b531dd8fd2d13f6f36a89c2eccda18ea/mlx_lm/models/qwen3_5.py)
using MLX's generic linear sharding. This source relationship is a reason to
test the custom layer, not proof that a particular Exo setup works or fails.

On a Mac mini, installed MLX 0.32.2, a network-disabled small synthetic
rotated 2-bit layer failed a generic `shard_linear` conversion at group size
one with `Module does not have parameter named "signs".` A normal quantized
linear control converted and retained exact output in the same process. The
synthetic fixture and measured versions are recorded in
[the evidence](evidence/awai147-rotated-shard-20261103.json); its standalone
[diagnostic](evidence/awai147-rotated-shard-probe.py) is not a product
runtime or a CI fallback.

This is not an Exo end-to-end test: the installed MLX differs from Exo's
pinned MLX, no actual model weights were loaded, and no multi-rank or MTP
execution occurred. Do not treat this as a global incompatibility claim.
Conversely, do not admit tensor-parallel Bonsai from model name or an engine's
generic sharding capability alone. The actual pinned engine, rotated layer,
MTP head, data path and multi-rank outputs need correctness and resource
qualification before live placement. Pipeline parallelism remains a separate
candidate with its own correctness and bandwidth tests.
