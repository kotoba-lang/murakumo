# Resident stops on refused or unknown cycles

The standalone resident now schedules another cycle only after `resident/idle`,
`resident/replicas-refreshed`, or a well-formed `resident/published` result.
Refusal and uncertain outcomes leave the loop without waiting and retrying.
The one-shot guest functions also return 0 only for the appropriate successful
operation; failures return 1. This is an application-level result contract,
not proof that a particular host propagates it to the OS process status.

On one local Apple Silicon Mac, the attached candidate and old-control Kotoba
fixtures ran through the pinned native Mac CLI. They embed the current and
previous `profile-resident-main`; host configuration and `rt-serialized` are
synthetic. A test-only pause prints `PAUSE` and returns false, making a retry
attempt visible without waiting or starting a workload. Candidate `run` on
`resident/completion-unknown` returned guest value 2 without `PAUSE`; old
control reached `PAUSE`. Candidate `once` returned guest 1 for the unknown
result while the old control returned 0. Idle, replicas and well-formed
publication are accepted; malformed publication and refused refresh/archive
are rejected in the fixture. The sample does not exercise a real controller,
inference, checkpoint, network, or a remote mini.

The public CLI and its compiled test Mach-O displayed the guest values in
stdout, but returned OS process status 0 even for guest 1 and 2. Consequently
this change does not yet make launchd reliably see failure on this Mac runtime;
that requires a qualified exit-status propagation boundary. The test observes
this exact artifact, not every Darwin provider. The one-shot test image SHA-256
is `7d1aa2262bebead30e02b004a37d7548fdb5ad7ac1cf748cd39170fcd307cd46` and the
native CLI SHA-256 is `6e7dc223795919581cdfc9128344948dfaddec58e9573bef9632923538f33a2a`.

The bounded Linux test VM failed to complete its SSH startup on this attempt.
Its task-owned VM and the stuck stop command were terminated and the VM was
confirmed stopped; no Linux test is claimed for this source revision. This
fixture is not a resident crash-recovery, persistence, or fleet qualification.

Source SHA-256 `083be90fac9780971d20f02e30b0f72e4181ad3e6689f3a6b75dd3ee3e9a1774`.
Candidate fixture SHA-256 `a01bc1f08930ad7a6f2d5d02cd03aad470d63b031a773a0973e020d7c02f0c70`;
control SHA-256 `91588f6cd4b46dbee19d5073dd9aa84073f59862329635ba5e701014323ea125`.
