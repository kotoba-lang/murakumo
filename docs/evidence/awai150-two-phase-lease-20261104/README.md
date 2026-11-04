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

## Count finishes after the shared deadline

On 2026-11-05 JST, the current `rt-dispatch` was substituted into the archived
fixture and tested on Xavier with the selected native count, lease client,
diagnostic FS2 and actual host clock. A task-owned transient systemd count
unit ran `/usr/bin/sleep 4`; its synthetic count response and matching request
were pre-created. The runtime used actual systemd state queries and a separate
`/usr/bin/sleep` pause while awaiting the unit. The synthetic retention window
was 12 seconds, leaving about two seconds after the existing ten-second margin.

The timed case returned `profile/budget-refused`, retained its busy marker and
did not call the generation stub. Each loopback voter observed four requests:
two prepare/accept rounds, before count completion. The unchanged control
reached the generation stub and each voter observed six requests. A separate
guard-bypass mutant under the same setup also returned budget refusal before
generation, but each voter received six requests, demonstrating the extra
post-count quorum round the new guard avoids. These counts were assertions in
the operator; both fixture runs and their controls exited zero. This mutant is
expected to perform the extra round, so its zero exit is a successful
negative-control observation, not a passing version of the guard.

All listeners were closed and the test-owned units stopped. An independent
check after each run found the unit `not-found` / `inactive`. The compiler
sampled peaks were 61,340 and 64,244 KiB; timed guests sampled at 2,668 and
2,932 KiB. Controls sampled at 3,988 KiB. The same CPU and address-space
limits and RSS sampling policy described above applied; sampled RSS is not a
hard bound. Remote synthetic artifacts remain at
`/tmp/awai-lease-loopback-f4b9lfvi` and `/tmp/awai-lease-loopback-f2jlhmsq`.

The baseline fixture, ELF and operator SHA-256 values were
`7fcef72a99da5fd4dbcb585a6054ed07cb62abca8bf80aa86d4fa15e0ad95e2d`,
`07f3137b5f4eb905a3274851e267fbc59b343edb2066b93ab28ffa1c8e7e3ef7`, and
`393e1d5013db3119001b4384e2ad1cf6483789bac6745f81a71ae0e94f314827`.
The mutant fixture, ELF and operator hashes were
`08ff9fdc01b0c0f4f637fe4e4a922ab7eb687c0ba415cfa5ae8e4e093a8a59a3`,
`b4db617e31a41ec9e05bc5f332ffb8d89dc5b398cad3f28cf21e60f59998e33e`, and
`c6ab25ad86f1d2c99990d4c97d5d7e8debff63dc03f9a7f3c00a5755f2cf3288`.
The `count-expired` patches record the exact changes from the archived source
and operator, with the mutant patches applied on top of those changes.

The voters and count response were synthetic, generation was stubbed and no
model or GitHub publication was run. This is not a real quorum, a qualified
clock or governor, nor an atomic receiver-side fence.
