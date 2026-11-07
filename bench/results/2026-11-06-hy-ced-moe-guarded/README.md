# JUN-936: kernel memory backstop and rematerialized training

2026-11-06 JST. Developer Mac performed no training/GPU benchmark. All controls
here ran on fleet naphtali, M4/16GiB, macOS26.7.2, MLX0.32.4, Hy1.1.0,
portable CPython3.12.12. No system service or system-wide memory setting changed.

## Current result: both contexts complete within the budget

Same executing revision `ada22acc`,1.0437B total parameters, BF16 weights and
gradients, FP32 SGD masters on NVMe, attention/MoE rematerialization, expert
token chunk64, batch1. Each trial has four updates,120s outer deadline,
110s between-step budget, requested10GiB and lower9GiB kernel fatal limit.
No checkpoints. Both children exit0 and are reaped.

| Metric | Context256 | Context512 |
| --- | ---: | ---: |
| Processed training tokens | 1,024 | 2,048 |
| Training phase, including periodic validation | 29.7596s | 52.5516s |
| Training-phase tokens/s | 34.4091 | 38.9712 |
| Outer supervisor wall, including startup/setup/evaluation | 33.3356s | 56.4906s |
| MLX allocator peak | 6.7550GiB | 8.7711GiB |
| Kernel lifetime process footprint peak | 6.9061GiB | 8.9626GiB |
| System swap before / after | 1,809.88 / 1,809.88MiB | 1,809.88 / 1,809.88MiB |
| Held-out sample CE | 10.47376 -> 10.46357 | 10.55070 -> 10.55902 |
| Process physical disk reads / writes | 3.2794 / 20.8776GB | 12.5296 / 20.8777GB |

Both allocator and kernel lifetime peaks are below10GiB; kernel footprint is
also below its lower9GiB threshold. This is a short operational update test,
not sustained-performance/quality qualification. The512 run has only about
38MiB of margin to the kernel threshold; a larger or longer workload can be
killed rather than exceeding its admitted footprint threshold indefinitely.
System-wide swap snapshots do not rule out transient swap or other-app impact.

Both use the SHA-verified same corpus/tokenizer and seed936. Validation samples
differ with context, so their CE is not a quality ranking.512 ran before256;
the OS page cache/SSD was not cold-reset. The read-byte difference matters:
these single rates include NVMe work and are not a controlled context-scaling
curve or a pure GPU-compute measurement. Each trial logically reads16.6992GB
and writes20.8740GB of FP32 master data (including initial write). Physical
process I/O counters include filesystem overhead and cache effects.

`comparison.json` binds source digests (matched against fleet files), conditions,
metrics and limits. Raw results/supervisor/snapshots are adjacent. Normal-exit
temporary optimizer workfiles are removed. Earlier kernel-killed trial artifacts
remain separate. Current owned training processes are checked absent at closeout.
No developer-Mac training, model-size reduction, cap increase or persistent
service was used.3B sharding, sufficient learning and30B extrapolation remain open.

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

The table above is the later measured result. No1B/3B convergence or30B duration
is established.
