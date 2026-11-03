# FIFO cancellation candidate

On one local Apple Silicon Mac, the source was compiled through the same
bootstrap compiler and diagnostic FS2 packager identified in the earlier
[recovery observation](../awai151-fifo-recovery-20261103/README.md). A task-only
FD3-scoped observer used real filesystem operations to enqueue four synthetic
tickets, refuse cancellation of the head and a missing ticket, cancel a
middle and a tail ticket, and verify remaining order. A synthetic intent left
after queue rename recovered a single non-head removal; two removals and a
reordering were refused. A single concurrent dequeue/cancel race returned one
success and one busy, with the expected queue. The observer is not operational
tooling and uses a fixed fresh temporary directory.

The test-only mutant that removed the head cancellation check failed the
observer at the head-refusal assertion. This does not simulate a process kill
during cancellation, power loss, untrusted callers, real workload termination,
Mac mini deployment, or a qualified governor. The journal format does not
identify the operation; recovery accepts any valid single removal, and only a
trusted host may write the journal or authorize a cancellation. This changes
the older recovery test's intentional rejection of a non-head removal.

Source SHA-256 `ce6feba9274c30331a962c0a97ef2ed9e32d4eade0527de9e63a09e752e6a598`;
signed fixture `86e1b42c4e49304a0abefb07f9ad1acde096ffd6607749b92a842b26f708c8ba`;
signed mutant `ee9d0daa0fa3afb2b4a2faedba8945daee35a62166029bab17da72e847371aa3`;
observer `ca9873472df8515cbe0e3d277fe753b3985e6c08d82ad225dca4b22ef09a0e45`.
