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

## Reading rank-local slices

A follow-up three-Mac diagnostic used `safetensors.get_slice` to read only
rank-local parts of the real value projection into actual MTPLX
`HadamardQuantizedLinear` instances. Row-split layers retain the full sign
vector; input-split layers retain only their whole 1024-wide rotation blocks
and matching quantized groups. The row tensor arrays requested on the three
ranks were 512,960 / 511,520 / 511,520 bytes, versus 1,495,040 bytes for all
four full-layer tensors. Input-split arrays were 598,016 / 598,016 / 299,008
bytes. These are array sizes, not measured physical I/O or peak unified memory:
the constructor, allocator, OS page cache and temporary copies can use more.
For verification rank 0 separately loaded the full layer and shared its
output; ranks 1 and 2 did not load that full reference. Output-row slices
matched exactly and the float16 sum of input slices differed by at most
0.00390625 on the one synthetic activation.

The [probe](evidence/awai149-sliced-real-layer-probe.py) and
[evidence](evidence/awai149-sliced-real-layer-20261103.json) implement this
experiment, not a production loader. This is still one layer, not the full
model, real prompt, MTPLX post-load fast paths, MTP, authenticated production
transport, process recovery or measured system memory savings.

## Three real decoder layers across three Macs

A bounded follow-up assigned consecutive real decoder layers 2, 3 and 4 to
three physical Macs. Layers 2 and 4 use the gated linear-attention path;
layer 3 uses full attention. Each worker loaded only its layer's checkpoint
tensors into the installed MLX/MTPLX decoder and Prism projection classes.
A reference on rank 0 separately loaded all three layers. Two deterministic
synthetic hidden-state inputs were processed in sequence with separate,
persistent per-layer cache objects. The stage-to-stage outputs matched the
single-host reference exactly at both steps. Each distributed layer's tensor
fingerprint matched the corresponding reference fingerprint.

The raw tensor arrays read for the three distributed stages were 109,990,784,
104,933,376 and 109,990,784 bytes. The reference copies on rank 0 are
additional; these counts are not peak memory or physical I/O. The diagnostic
set an MLX per-process 2 GiB limit and stopped if sampled host memory-pressure
free fell below 60%. The sampled minima were 89%, 87% and 87%; sampling
cannot rule out shorter drops or impact on other workloads. No sustained
model service was started. [Probe](evidence/awai149-three-mac-decoder-pipeline-probe.py)
and [evidence](evidence/awai149-three-mac-decoder-pipeline-20261103.json).

This is a three-layer, two-step experiment using synthetic hidden states,
not a complete prompt, full model, logits, MTP head, throughput or production
pipeline. It uses the installed MTPLX classes without the full model's
post-load kernel wiring, and a temporary direct Tailscale ring without
application-level peer authentication. Exo's
[pinned loading code](https://github.com/exo-explore/exo/blob/63760522e34fa070fc9f40f5b93803e29b3b0797/src/exo/worker/engines/mlx/utils_mlx.py)
explicitly tolerates unused MTP tensors; a successful generic pipeline load
alone therefore cannot establish the requested MTP execution. Full-model
state, MTP verification, authenticated transport and physical reservations
remain separate requirements.

## Full decoder over three Macs: two greedy tokens

An experimental text-only pipeline assigned all 64 real decoder layers to
three physical Macs in ranges `[0,21)`, `[21,42)`, `[42,64)`. Each stage
loaded its own layers from the local packed checkpoint; rank 0 loaded only the
embedding row for each input token, and rank 2 loaded the output projection
and final normalization. The workers kept their own layer caches between two
single-token steps. With input token 9419 (`Hello`), greedy decoding produced
token IDs 11 and 353 (`Hello, I`). Both output logits arrays had the same
SHA-256 as a separate single-Mac calculation that streamed the 64 layers from
disk for each token with independent caches. That reference used the same
layer-loading and mathematical implementation, so it tests partition and
transport consistency, not equivalence to every production model path.

The three stages requested 2,284,519,424 / 2,284,519,424 / 2,389,452,800
bytes of layer tensor arrays; rank 2 also requested 357,601,280 bytes for the
head. These counts exclude allocation, caches, the reference, OS page cache
and temporary copies; they are not measured peak memory. An MLX 4 GiB
per-process limit and a watchdog stopping below 60% reported free pressure
were applied. Across two pipeline runs the sampled host minima were 89%, 87%
and 72%; the streaming reference used a 2 GiB MLX limit and had a sampled
minimum of 88%. Sampling cannot establish the lowest instantaneous value or
absence of impact on other work. Processes and listeners were stopped after the
runs. [Pipeline probe](evidence/awai149-three-mac-full-decoder-probe.py),
[streaming reference](evidence/awai149-full-decoder-stream-reference.py),
and [evidence](evidence/awai149-full-decoder-pipeline-20261103.json).

This is not an MTP run: it excludes the draft head, MTP verification and the
full model's post-load optimizations. It uses a single input token and two
greedy steps rather than a representative prompt or quality benchmark. The
short-lived direct Tailscale ring lacks application-level peer authentication.
It is experimental code, not a deployed engine, gateway, scheduler or bot.

## Experimental distributed MTP candidate and verification

The three-Mac 64-layer experiment was extended with the installed MTPLX
`inject_mtp_support` implementation and the actual `mtp.safetensors` sidecar on
the final stage. A separate parameter-name coverage check found 31 sidecar
keys and 31 MTP parameter leaves, with no missing or extra names. This is a
coverage check, not a complete validation of the weights or quantization.
The experiment used the shared trunk output head rather than the optional
MTPLX draft-only output-head optimization.

For each of four short, multi-token prompts, the distributed decoder advanced
the MTP history cache for every known adjacent prompt pair, produced a draft
candidate using the last hidden state and the primary greedy token, and ran
the target decoder serially on that primary token to verify the candidate.
All four candidates matched the target. For `Hello, I am`, a separate
single-Mac layer-streaming calculation produced the same primary, candidate,
verified target, and SHA-256 of both target logits and the draft logits. Both
paths used the same layer-loading and MTPLX MTP implementations. Separately,
eight *single-token* prompts without prior history produced zero accepted
candidates; `Hello`'s candidate and target logits also matched the
single-Mac reference. These twelve short cases exercise both acceptance and
rejection, but are not a representative acceptance or performance benchmark.

This serial check does not perform batched speculative verification or show a
speedup. It is an experimental text-only program, not a running MTP server,
full MTPLX post-load path, production gateway, scheduler or bot. It does not
validate longer contexts, MTP rollback, streaming generation, independent
production-loader parity, failure recovery or ten-host scheduling. As in the
prior pipeline, the temporary direct Tailscale ring had no application-level
peer authentication. Two contextual pipeline runs had sampled pressure-free
minima of 75/73/69%; the reference minimum was 84%, with the same 60% guard.
These samples are neither peak RAM measurements nor proof that other work was
unaffected. [Evidence](evidence/awai149-distributed-mtp-20261103.json).

## Nine-Mac experimental MTP pipeline

The later [nine-Mac experiment](evidence/awai149-nine-mac-mtp-20261103.json)
partitioned the 64 decoder layers into nine consecutive stages and used the
actual MTP sidecar for four short multi-token prompts. The serial target
verification accepted all four draft tokens. For the first case, the target
and draft logit hashes matched both the three-Mac result and the separate
single-Mac streaming reference; those references shared the same model
loading and mathematical implementation, so this is not independent
production-loader parity. The [probe](evidence/awai149-nine-mac-mtp-context-probe.py)
is experimental Python, not the native runtime or a deployed service.

Six temporary shard transfers used mutually authenticated TLS and checked
SHA-256 at both ends; the actual inference ring instead used temporary direct
Tailscale TCP without application-level peer authentication. The evidence
records a 4 GiB per-process MLX limit and sampled pressure-free minima of
78–87%. These readings do not qualify total unified-memory limits, other
workloads, process-tree recovery or a fleet governor. The tenth Mac was not
used in this run because its observed memory pressure and swap were high;
this is not a permanent assessment of that machine.

This observation advances the distributed MTP correctness investigation,
but proves neither a speedup nor safe continuous service. It does not cover
batched speculative verification, representative contexts and quality, cache
rollback, authenticated inference transport, bounded process lifetime,
concurrent reservations, actual bot execution, or ten-host scheduling.
