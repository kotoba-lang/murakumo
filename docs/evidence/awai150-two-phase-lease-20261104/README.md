# Archived two-phase lease-client fixture

These are the exact fixture source and operator script used for the combined
Xavier observation described in [the shared-deadline evidence](../awai150-flint-shared-deadline-20261104.md#combined-two-phase-and-native-lease-client-seam-on-xavier).
The fixture is an assembled diagnostic snapshot with historical dependencies
and test stubs; it is not application source or a production binary. Do not
assume it tracks subsequent changes to the resident or lease client.

* `fixture.kotoba`: SHA-256 `5b44f7efa1f60552e1716020eec38c2de1644af2c1f09b773d4ad223a6a4760a`.
* `operator.py`: SHA-256 `9de01f28fbfb3dadb4c91e7dfc06a7d8ec177b93520677d9fda27f7dbc94b570`.

The unmodified operator script is retained to document the observed run, not
as an unattended test runner. It refers to absolute temporary paths and a
specific Tailscale host and systemd user unit. It needs the separately obtained,
SHA-256-pinned source-derived F3 compiler recorded in the evidence, SSH access,
and a compatible Linux host. It creates a transient unit and loopback listeners;
read and adapt it to the host and confirm its lifecycle and resource limits
before any rerun. It does not download the compiler. Its resource sampling is
not a hard RSS limit, and the model and GitHub publication are not exercised.

## Lease recheck delay and budget exhaustion

On 2026-11-04, the archived fixture was modified using
`budget-fixture.patch` and `budget-operator.patch` for a separate bounded
Xavier diagnostic. The budget case set synthetic retention to 12 seconds past
the sampled clock and delayed each mock voter's third prepare reply by 0.85
seconds. The actual selected resident and lease-client bodies and real
systemd count-unit query remained in use. The fixture asserted that the
resident returned `profile/budget-refused`, did not reach the generation stub,
and retained its busy marker. The unchanged control reached the stub. Both
guests exited zero, indicating their respective fixture assertions passed;
each voter received six requests in each case and no mock server error was
reported.

The compiler sampled peak RSS was 61,340 KiB and the control and budget guests
sampled at 3,988 and 2,932 KiB. CPU and address-space limits and sampled RSS
termination were as described in the linked evidence. The task-owned transient
unit was stopped and separately observed `not-found` / `inactive` afterwards.
The synthetic remote artifacts remain in `/tmp/awai-lease-loopback-r2qc_r3v`.

The patched fixture source, generated ELF and operator script SHA-256 values
were respectively `f0fd968e52df1875bfa2862f1c581ce4f350ed1f2705aafce703f8209bc99afb`,
`1eb8529d78bcaebe818bf25b4d511262a25bbda04cef64b7ef297927e9d244dc`, and
`743d25624eb682d84191deaeddb1dbe41fb45297442238b8fb4f5df4b18e515b`.
This exercises budget exhaustion at the selected seam using an actual host
clock, not real voters, inference, monotonic time, atomic fencing or a
qualified governor. Injected delay is not an estimate of fleet latency.
