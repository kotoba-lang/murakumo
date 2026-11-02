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

A follow-up on the same Mac used two real MLX ring ranks in separate processes,
with network access restricted to loopback. An `all_sum` of rank values returned
3 at each rank, the ordinary quantized layer matched each 64-element output
slice exactly, and both ranks rejected the synthetic rotated layer with the
same `signs` error. The [probe](evidence/awai149-local-ring-probe.py) and
[evidence](evidence/awai149-local-ring-20261103.json) describe this small
experiment. It used one physical Mac and did not test inter-host networking.
The launcher returned zero even on an earlier failed child launch, so a zero
launcher status alone is not evidence that every rank succeeded; inspect each
rank's records and errors.

This is not an Exo end-to-end test: the installed MLX differs from Exo's
pinned MLX, no actual model weights were loaded, and no MTP execution occurred. Do not treat this as a global incompatibility claim.
Conversely, do not admit tensor-parallel Bonsai from model name or an engine's
generic sharding capability alone. The actual pinned engine, rotated layer,
MTP head, data path and multi-rank outputs need correctness and resource
qualification before live placement. Pipeline parallelism remains a separate
candidate with its own correctness and bandwidth tests.

## Two-Mac synthetic projection

A bounded direct Tailscale ring test used MLX 0.32.2 on two distinct Mac minis.
The data was synthetic and neither model weights nor credentials were sent.
The installed model manifest had 402 listed modules, all with block 1024;
the experiment used block 1024 and a 2048-wide synthetic input, not actual
module weights or a complete layer geometry survey. Each rank computed a
128-output 2-bit affine projection. Output-row partitioning replicated the
signs and input rotation and matched the corresponding full output slice
exactly. Input-column partitioning split only at both the 1024-element
Hadamard block and 128-element quantization group boundaries. The sum of
float16 partial projections differed from the full projection by at most
0.0625, below the experiment's pre-set 0.25 absolute limit. This tolerance
is diagnostic, not a model-quality acceptance criterion. A split cutting a
Hadamard block was explicitly refused by the fixture.

The [small probe](evidence/awai149-two-mac-aligned-probe.py) and
[results](evidence/awai149-two-mac-aligned-20261103.json) demonstrate one
candidate arithmetic approach, not an integrated sharding implementation.
They do not test segmented/fused layers, real packed weights, logits,
collective failures, actual MTP or the pinned Exo engine. The direct ring
sockets carried no application authentication and were temporary; they do
not establish a suitable production transport. A loopback-over-SSH attempt
had timed out during initialization; no conclusion about that design follows
from one attempt. Production requires authenticated transport, correct
process supervision and failure isolation in addition to model qualification.

## Actual packed-layer geometry and two-Mac check

Reading only the local safetensors header and the installed model manifest
matched all 402 listed modules. For a uniform **two-rank input-column split**
that preserves 1024-wide rotations, only 64 of 402 modules have an aligned
per-rank width; all 402 have an output width divisible by two. The most
common input width is 5120, i.e. five rotation blocks, so a two-rank equal
input split would cut a block. This count is only a necessary dimension check:
it does not account for fused segment layout or determine a complete model
partition. An uneven three-block/two-block split is a candidate for this width.

A second two-Mac experiment loaded only four tensors for the actual packed
`layers.63.self_attn.v_proj` layer (5120 inputs, 1024 outputs). The two hosts'
tensor fingerprints matched. With a deterministic synthetic input, output-row
partitioning matched the full layer's corresponding outputs exactly. The uneven
3072/2048 input split followed by a float16 all-sum had maximum absolute
error 0.00390625 against the unpartitioned result, whose largest absolute
output was 7.5078125. The preselected 0.25 diagnostic tolerance is not a
quality threshold. The [probe](evidence/awai149-real-weight-probe.py) and
[evidence](evidence/awai149-real-weight-20261103.json) do not include weights.

Only one layer and one synthetic activation were exercised. This is not a
full forward pass or MTP run, and does not verify the other 401 modules,
segment boundaries, inference quality, the pinned Exo runtime, failure
handling, or secure production communications. As in the earlier experiment,
the short-lived direct Tailscale ring had no application-level peer
authentication; only synthetic activations were used.

## Three physical Macs and the MTPLX layer

The same real value-projection tensors were exercised on three physical Macs
using MLX 0.32.2 and the installed MTPLX 2.12.2. The fixture instantiated
MTPLX's actual `HadamardQuantizedLinear`, loaded the four real tensors and
compared its output with the unpartitioned reference: the maximum difference
was zero on each rank. The test did not run model post-load wiring or enable
its planned ternary fast path. The three ranks split the five input blocks as
2/2/1 and split 1024 output rows as 342/341/341. With one deterministic
synthetic input, each output partition matched its reference exactly; after
float16 all-sum, maximum absolute difference was 0.00390625. Each host
reported the same tensor fingerprint.

This was a single layer, not full model or MTP inference. For reference
comparison each host loaded the entire selected layer before slicing, so the
experiment does not measure or achieve production memory savings. Its
short-lived direct Tailscale ring had no application-level peer authentication.
The [diagnostic](evidence/awai149-three-mac-real-layer-probe.py) is a probe,
not a runtime implementation; [evidence](evidence/awai149-three-mac-real-layer-20261103.json)
records the precise scope. A read-only check found the specific prior test
venv and model paths on three of the ten surveyed minis; their absence at
those paths on the other seven is not proof that those machines lack other
usable installations.
