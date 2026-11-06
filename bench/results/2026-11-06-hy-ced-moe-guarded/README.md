# JUN-936: kernel memory backstop and rematerialized training

2026-11-06 JST. Developer Mac performed no training/GPU benchmark. All controls
here ran on fleet naphtali, M4/16GiB, macOS26.7.2, MLX0.32.4, Hy1.1.0,
portable CPython3.12.12. No system service or system-wide memory setting changed.

## Enforcement qualification

XNU `memorystatus_control` SET/GET_MEMLIMIT_PROPERTIES, active/inactive FATAL,
sets a per-process fatal physical-footprint threshold. A one-shot `sudo -n`
helper verifies that the target is its invoking user's live ancestor, refuses
over10GiB or increasing a smaller existing process limit, installs and reads
back both fatal limits. Training remains unprivileged. Security checks use
explicit exceptions and remain active under Python optimization.

CPU and Metal children positively allocate then cross128MiB/384MiB thresholds;
both are killed with SIGKILL. Their maximum attempted payloads are256MiB/512MiB.
The qualification binds host, OS and all three guard/test source digests.
The refused-setter controls cover tiny/16/world1,1B/256/world1,1B/512/world2,
with model/evaluation/collective sentinels and no output creation.

Requested10GiB runs use a lower9GiB kernel fatal threshold, reserving1GiB
below the requested ceiling for limit-crossing granularity and runtime margin.
This is kernel kill-on-limit enforcement, not a pre-allocation allocator quota.
MLX remains advisory. Record process lifetime maximum physical footprint,
resident memory and MLX peak separately. Kernel footprint includes compressed
memory; MLX buffers are not total process memory. No whole-host reservation or
shared native-governor qualification is claimed.

Primary source: [XNU fatal footprint enforcement](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_memorystatus.c),
`memorystatus_on_ledger_footprint_exceeded` and SET/GET_MEMLIMIT_PROPERTIES.
The ABI was checked against the fleet's installed macOS SDK headers.

## Memory savings and numerical controls

Attention and MoE kernels are rematerialized per layer using `mx.checkpoint`.
Only discrete indices/masks are stopped; shared K/V/index K and CED source are
not detached. Initial tensors/FP32 masters materialize sequentially; consumed
gradient entries and the previous step's tree are released, and cache is zero.
Model dimensions, total parameters, BF16 model/gradients and FP32 SGD masters
remain unchanged.

FP32 checkpoint-disabled/enabled control compares all155 parameter gradients:
loss difference0; worst gradient difference1.0728836e-6; SGD-update difference
2.9802322e-8. Kernel process limit1GiB. This is a small numerical control, not
large-model BF16 bitwise reproducibility. Existing causality/perturbation,
nonzero shared-source gradients and shared-KV VJP control also pass after
refactoring. A four-step tiny BF16 SGD run completes under the kernel guard.

## Resident-master1B stop and additional memory savings

The first guarded1B sequence256 attempt (`d543a91c`) was killed by the kernel
after1.984s during initialization, before any optimizer step. Supervisor
exit-9, no timeout, child reaped. No memory ceiling was increased. System swap
was1,809.88MiB before and after; the memory event and snapshots are preserved.

The next implementation adds `--master-offload nvme`: one FP32 master tensor
is loaded/updated/written at a time instead of retaining all4.175GB of masters
in the MLX allocator. Files are temporary optimizer working state, cleaned on
normal completion, not resumable checkpoints. It supports SGD only and refuses
checkpoint/resume. Physical process counters include actual disk I/O; logical
bytes are recorded separately, because page-cache reads may avoid SSD I/O.

Attention also uses batched matrix products to avoid full head/selection/dim
broadcast intermediates. Compared against the SHA-pinned earlier full model
(`model.hy` SHA256 `5c0915427f140de1327a63fea920da705638e5feef4fae36a2ef32271fe28628`),
FP32 worst gradient difference is9.8943710e-6. Rematerialization enabled/disabled
after this change: loss difference0, worst gradient1.4603138e-6,
update1.4901161e-8. All155 parameter gradients are compared.

NVMe-vs-RAM masters: exact initial FP32 serialization and three SGD updates
under identical gradient inputs give maximum weight/master error0. The test
isolates optimizer placement; independent BF16 scatter-gradient executions
are not bitwise reproducibility proof. The actual tiny NVMe training loop also
completes four steps. Adjacent JSONs preserve results and supervised exits.

At this code unit, the1B NVMe measurement is next. No1B/3B convergence or30B
duration is established.
