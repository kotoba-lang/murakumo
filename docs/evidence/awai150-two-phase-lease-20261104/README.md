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

## Stop waiting for count after the shared deadline

The count wait loop now checks the shared deadline and clock before each unit
state query. An expired or unknown sample returns an unknown count and retains
the busy marker; it does not claim the unit stopped. A query or sleep already
underway is not forcibly interrupted by this check.

On local Darwin ARM64, a signed selected-source native fixture tested an
expired deadline without entering its instrumented unit query, plus an
unexpired completed-count control. A separately built guard-bypass mutant
failed that assertion. The other deadline, count and resident fixture cases
passed, and an assembled historical-dependency source with the updated count
functions passed the pinned Amu CLI `check`. The mock unit and clock in this
local fixture are not the production provider or clock.

On Xavier, the previous task-owned `sleep 4` systemd count scenario was rerun
with the updated selected `pr-count` and `pr-count-wait` forms and actual
systemd state queries and host clock. At expiration it returned
`profile/context-budget-unknown-or-insufficient`, retained the busy marker,
made no generation call or post-count lease round, and the unit's substate was
still `start`. The operator waited for the unit to exit before the unchanged
control, which reached the generation stub. With only the new wait guard
bypassed, the timed case instead returned after the unit was `exited` and the
later deadline check refused; this was the expected negative-control
observation. Each timed case had four requests per mock voter; controls had
six. All fixture assertions passed. The task-owned units were stopped and an
independent final check observed `not-found` / `inactive`.

Compiler sampled peak RSS was 61,340 KiB for the guarded run and 66,624 KiB
for the mutant; the timed guests each sampled at 2,668 KiB. The original
CPU/address-space limits and RSS sampling policy apply. The two remote
synthetic artifact directories are `/tmp/awai-lease-loopback-9z_8gm8k` and
`/tmp/awai-lease-loopback-7y6xik7d`. The guarded fixture, ELF and operator
SHA-256 values were
`6e947ed07735a9efe2ba7fad4f0ef150eedff474ff7d7b12ef815f0e7bded687`,
`3f5d4325b4b9d838927a3ddd0f808ddb423276a307aeb92911dd141fc9015028`, and
`5a56c80e12bf17437ef78a37242591b03ebfb1a182c7fc1d5645b5bf55968d27`;
the corresponding mutant values were
`787e2a5ed60d9d05bfcd5930406f071f9d0369cbbef620be7ea329e9a3f3fee9`,
`3e9fab8392d158fcba563698cdd4a59e95a5862e956fc40701f80c1f4a35c4fb`, and
`22876c413a7e79e87cf4abb79f28a5e15e54e8af810ce137e07f0329ae25309d`.
The `count-wait` patches apply after the respective baseline `count-expired`
patches; the mutant patches apply after the guarded `count-wait` patches.
Voters and count response were synthetic, generation was stubbed, and no
model or GitHub publication ran. Clock monotonicity, operation interruption,
process containment, receiver fencing and governor qualification remain open.

## Stop Flint generation polling after the shared deadline

The selected Flint generation wait now checks the shared deadline before each
unit state query and returns false on an expired or unknown sample. Its caller
retains the request, dispatch marker and busy state; this check does not stop
an existing unit or interrupt a query or sleep already in progress. The
non-Flint path keeps its earlier behavior.

On local Darwin ARM64, selected native fixtures with a mock clock and unit
verified that an expired Flint request did not query the unit, an unexpired
Flint request did, and a non-Flint control with no deadline retained its
previous behavior. A separately built guard-bypass mutant failed the expired
case. The other deadline fixtures passed; an assembled source with historical
dependencies and the current generation wait and caller passed pinned Amu
`check`. This non-Flint fixture is not a full MTP integration test.

On Xavier, the selected generation wait used the actual host clock and
systemd queries against a task-owned `sleep 4` unit. With a two-second
synthetic deadline, the wait returned false while the unit still showed
`start`; a guard-bypass mutant returned true only after the unit was `exited`.
Both operators then confirmed a completed-unit control. Response checks and
response bytes were stubbed, and this was a direct `pr-wait` fixture rather
than the full resident, so retained markers and real generation were not
exercised. The unit was stopped and independently observed `not-found` /
`inactive` after the tests. A first compilation attempt failed on an outdated
fixture caller before any unit was started; the successful fixture substituted
the current caller as well.

Compiler sampled peaks were 61,076 KiB and 64,516 KiB; the guarded and mutant
timed guests each sampled at 624 KiB. The original CPU/address-space limits
and sampled RSS policy apply. Remote synthetic artifact directories are
`/tmp/awai-lease-loopback-sy6div22` and `/tmp/awai-lease-loopback-28upkflf`.
The guarded fixture, ELF and operator SHA-256 values were
`b3e7aed5fa3d8e11b08d7ded7757e85f71ba873a8e6df266fe3baffa5129a13f`,
`4c09012489ee158df281346f9bca5b8e48c9f170638167118fc8d0c2e6fe3b4b`, and
`56b287e04159e894b52c0cc42b816575abe58bbfcaca57e172adcc28792c116e`;
the corresponding mutant values were
`ff330679bf2343c1bc624a42839f113fb93aad317bfea664055450fadb785425`,
`4be66a9735bd3df0420b62fe1883e4e453ddda4a14373b729d61221c35a42562`, and
`1706363cb428f970aa2e97336e1f077d2208e147d605fb45cc2a22e84ad127d7`.
The `generation-wait` patches follow the guarded `count-wait` patches;
the mutant patches apply on top. No model, real quorum, full resident,
GitHub publication or qualified governor was tested.
