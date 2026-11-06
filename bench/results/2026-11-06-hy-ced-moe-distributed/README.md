# JUN-936: two physical M4 replicated-training verification

2026-11-06 JST. Fleet naphtali and issachar, each M4/16GiB/macOS26.7.2,
Hy1.1.0/MLX0.32.4/portable CPython3.12.12. Actual en0 links negotiate1Gb/s.
No training/GPU benchmark on the developer Mac. Checkpoint was streamed to
the second fleet host without storing GB model data on the developer Mac.

## Controls completed before1B

Both hosts have independently qualified kernel fatal limits; source/OS/host
binding and readback are preserved. Small controls request2GiB with1GiB kernel
threshold. Both physical ranks use identical model initialization, source/data
hashes/settings and different local data from the same global sampler stream.

The F32 tiny model has2,807,616 parameters (train-only32K vocabulary), window4,
chunk4, sequence16, batch1/rank, two updates. Reference computes the mean of
the two local objectives sequentially. All155 first-step gradients compare
within max1.4901161e-8. Final weights and FP32 masters match the reference
exactly. The two physical ranks also have identical final weight/master hashes.
Global norm clipping follows gradient averaging. MoE balancing stays local;
batch2 loss evaluated once has different balancing semantics and is not the
reference.64MiB buckets limit collective buffers and preserve gradient dtype.

Missing MLX rank/hostfile fails before output/model initialization. Deliberately
exiting rank1 produces a peer-loss error in rank0: both exit1 in about0.39s,
no timeout, children reaped, no complete result. Only owned task processes are
affected. This demonstrates bounded failure reporting, not fault recovery.

## Preregistered1B trial

Explicitly import the SHA-verified step64 single-host checkpoint as common
replica initialization. Preserve its strict original source/options validation;
do not relabel this as distributed resume. New trial: BF16 weights/gradients,
FP32 SGD/NVMe masters, same lr/decay/clip, context64, local batch1, global batch2,
four updates. Context64 bounds sequential-reference accumulation memory; it
does not exercise window128 truncation (the small control does).

Compare two physical ranks with a single-host sequential mean of the same two
local objectives and same imported state/sampler. Per host requested10GiB,
kernel fatal threshold9GiB. Outer deadlines300s/rank and180s/reference.
No cap increases, unrelated process stops or global checkpoint publication.

Report global tokens once (not sum both rank counters), divided by maximum
rank-local phase duration. Also report init/verification-inclusive time,
compute/communication/optimizer+finish-sync, logical gradient bytes, ring TX
estimate, whole-interface counters (background/control included, not exclusive
wire bytes), process memory/NVMe counters and final replica hashes. Final weight
exports/master hashes are verification artifacts, not resumable global state.
OS cache is not reset. At this implementation unit the1B trial has not started.
