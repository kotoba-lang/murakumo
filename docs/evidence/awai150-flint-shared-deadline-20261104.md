# Flint count and generation share an initial time window

The resident previously passed the same remaining lease/retention seconds to
both the token-count request and the later generation request. A slow count
could leave generation starting after that initial budget. The candidate now
passes the initial wall-clock sample and an absolute deadline to the profile
step. Immediately before starting a new count or generation unit, it samples
the host wall-clock capability and caps curl's timeout to the remaining whole
seconds; invalid, expired, out-of-range or observed backward readings refuse
the new submission. A sample after count is also used as a lower bound for
the generation sample. If a dispatch marker was written before a refusal,
existing unknown-state recovery remains necessary. A standalone profile step
has no resident-supplied window, so its Flint new submission fails closed.
Existing non-Flint request timing is unchanged.

This is only a partial mitigation. It does not re-learn or revalidate the
lease between count and generation, make the time check atomic with starting
the unit, guarantee a monotonic clock between samples, bound queue/startup or
polling overhead, stop an already running unit at the deadline, authenticate
the model host, or provide receiver-side fencing. The resident path remains
unsafe to enable until those conditions and the resource governor are
qualified. A reused pre-existing unit is not stopped by this change.

On 2026-11-04, selected current resident and recovery source plus historical
dependencies passed the existing Amu source `check`. The complete locked
project and integration cases were not built or run. A local signed Darwin
source-unity fixture compiled the actual `pr-window-seconds` function and
passed normal, reduced, expired, too-short and invalid/rollback boundary
cases. A mutation that removes the initial-time rollback check failed as
expected. Each compiler and native fixture process had a three-second CPU
limit; no hard memory cap was applied. This tests arithmetic only: no wall
capability, systemd, lease, inference, concurrent client or GitHub ran.

Source SHA-256: recovery
`6f22f01e4226be51ea711642dcec50f84989a0b6bc2677fea4a9524880710d2c`, resident
`51b5c85823de219d60d3c903db9f96f754bd41e73c300e1dff53d7d93f0e92ba`, and
integration test definitions
`76dff4f0e491526d5ec8a08bf073f2a0ec6d3361a673ba57c7b998c9cf134ebd`.
The diagnostic script SHA-256 was
`db6358e94a2248db910994a5294af23bda7ed4978b29a4ec3feed576c24088b3`;
compiler and diagnostic packager SHA-256 were respectively
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` and
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
The signed good and rollback-mutant fixture hashes were
`674c07eb5a30d50a07cb02b5b6e24b8ce6ca7e05e87ea294cade726df044211a` and
`708f9e47d7a598c60215e3ab84739d83ca753578a9393f17d72be0b14996420d`.

## Count submission seam with synthetic dependencies

The local signed Darwin diagnostic was extended to compile the actual
`pr-count` and `pr-window-seconds` source together. Test-only stubs supply
time, systemd state, filesystem responses and the submit operation. With a
10-second initial allowance and 3.5 seconds remaining, the positive case
returned the synthetic count only when the submit stub saw a three-second
timeout. An already-expired case returned unknown. A source mutation that
passed the original ten seconds instead of the remaining window failed the
fixture. The test does not independently observe whether the submit stub was
called in the expired case. Each native process had a three-second CPU limit;
no hard memory cap was applied.

The updated script SHA-256 was
`b67831c2d5e0db85f9306a982c51c14a84690516101e6f0e798fc8077c5f3ee7`.
The signed count-seam and bypass-mutant images hashed respectively to
`c704c95707b1129b7f70aff54ab818394ed63f54096ce2d0994740a5981fab1f` and
`3c3c77bd83f9b72e62111ec99fe39dd0dea7092cf13e6fb7d40687582ef64282`.
This is a synthetic seam test, not an actual clock, systemd/curl, lease,
generation, source-locked application or governor test. The lease refresh,
atomic fence, wall-clock and workload-lifetime limitations above remain open.

## Relearn lease between count and generation

The resident candidate now separates Flint into a count-only phase and a
generation phase. A completed count leaves the busy marker in place and
returns `profile/flint-count-ready` without generation. The resident then
relearns the same lease, checks its binding and retention again, and checks
the original absolute time window before entering generation. The second
phase is reuse-only: if its count unit is absent rather than reusable, it
cannot create a new count unit. A standalone phase-zero Flint step and an
invalid phase are refused; non-Flint resident steps keep their single phase.

This reduces the earlier gap but does not make the lease check atomic with
generation. The second phase still reads state, queries the existing count
unit and prepares the request after the lease check. A lease can change during
that interval. The host must exclude other writers and server users and
reconcile retained markers after unknown outcomes. A receiver-side effect
fence, monotonic deadlines, actual process lifetime and resource enforcement
remain necessary; this candidate does not authorize deployment.

On one local Darwin ARM64 host, selected actual source and historical
dependencies passed the existing Amu source `check`. Separate signed native
fixtures exercised: the actual `rt-dispatch` with stubbed lease, time and
profile steps (same lease proceeds, changed lease or expired retention does
not, failed count does not refresh or generate); actual `pr-count` with
stubbed systemd/filesystem/clock (reuse-only refuses a new unit and can reuse
a reported existing one); and actual `pr-step-range` with stubbed count,
clock, unit, submit and persistence (count-only submits no generation,
generation uses reuse-only, expired generation submits nothing, standalone
and invalid phases refuse). Mutants bypassing the lease refresh, reuse-only
guard, existing-count reuse or generation deadline failed their respective
fixtures. These are separate synthetic tests, not one integrated live run;
the actual lease quorum, durable files, process transport and model were not
exercised. Each compiler/native fixture had a three-second CPU cap and no
hard memory cap.

Current recovery and resident source SHA-256 values were
`c8b8f736960a08185803792a65f8f2a5b9953de32faf53ab75d67380f08afc96` and
`0c88ef2836413c3934f8363bc1ba26cef1c8a0591e1c04d518c21e537aa720f0`.
The native test script SHA-256 was
`d44b84ee3c47c6dad76f6d2094ccb817371131ae1e8f0a92efd25bd38de88fcc`.
The signed resident-refresh and refresh-bypass images hashed to
`71fd54978e6eebabbcbcea26530b04738dc2da223d129b56af763fefa2c7cb55` and
`e4d08d3a93b467252f1f630f7d6d27676a091f873ccf5777d27d4e07587b4651`.
The generated full-step fixture and its deadline-bypass control hashed to
`a8d860ea5c77a33e2106dabca4040d8bd9ba564c96e3695405a4fefbf3dc82cd` and
`1def7d87aa3501caf47284b8bbb3c073b6611da3646a5e5a9449f05d8bc2ff57`.
The generation fixture used temporary builder SHA-256
`cb59024c1ace9d3d634f56ff1d29d4ac48121c692f6aa5ea1c15f3984d6a77a9`.

### Count-only busy marker and reconciliation with diagnostic FS2

On one local Darwin ARM64 host, a source-unity fixture combined the current
`pr-step-range`, busy-marker and `pr-reconcile-count` forms with the pinned
diagnostic FS2 provider. It used private task-owned files and synthetic state.
The count function was replaced by a stub that writes a synthetic matching
count request, dispatch marker and completed response; unit state, wall clock
and runtime validation were also stubbed. The generation submit stub would
write an `unexpected-submit` file if called.

The actual count-only phase returned `profile/flint-count-ready`, retained a
busy marker and left `unexpected-submit` absent. An explicit subsequent
`pr-reconcile-count` returned `profile/count-reconciled 1`; an independent
filesystem check observed busy absent and last/settled markers present. In a
separate fixture with a generation intent inserted, reconciliation returned
unknown and the busy marker remained. The signed fixture was independently
verified with `codesign --verify --strict`; compiler and fixture processes
had a three-second CPU cap and no hard memory cap.

The temporary generator and composed fixture SHA-256 values were
`2a876be04a348e48f730350345da2e8c42efdcff44b9ff797d0090ff86263aae` and
`1dfa58f72cd3462dc90fada36f64cfdf0cd715994e63eac5c3a37cbf2c143502`;
the signed fixture image SHA-256 was
`324d82a86a257b80714bfb756a3dbad080e35eff0ae05dac0adce7e0f4c38bf1`.
Task-owned temporary files were retained. This tests diagnostic FS2 state,
not an actual lease loss, systemd/curl, real count service, power failure,
production filesystem provider, host serialization or qualified governor.

## Darwin diagnostic clock capability observation

On one local Darwin ARM64 host (macOS 26.3.1, build `25D771280a`), a
source-unity fixture containing the actual `pr-wall-ms` and a host-time window
assertion was built with the pinned compiler and `fs2-cli-stdio-general`
diagnostic packager named above. The signed fixture exited with SIGTRAP
(subprocess return `-5`), without stdout or stderr. Its source and image
SHA-256 were `c6f3716ee28d6927a93f538e5fe9274dd4a47b04f9c032b4aa5fa3807457f96f`
and `d5a2f34bd9ade2c93c660f55c276c9fb4e5f4be33f7de77ce05816e37ec45a9c`.

A separate fixture performing only `typed-cap-call 7` with `wall` also exited
with SIGTRAP (`-5`); its source/image SHA-256 were
`7cce6bc18352531845022ba471ce0c2892a1c787c9c709931472a21ed9249279` /
`c53977bdd6028a6ecacfb190a27bfcfaecea261f1e9d24c0edbedc855c6aa927`.
With that call replaced by a fixed decimal test string, the selected
`pr-wall-ms` parser returned the expected number (exit 0); its source/image
SHA-256 were `0ed1705924d7251c7eb4b5de71dbf21b0c7329140708bd52d947e66c0911dcb6`
and `aa51bcae5114715f69fe1c8cb1c06c14303b76060bd368fd3cfe712daeda7953`.
Each compiler and test process had a three-second CPU limit, without a hard
memory cap. Signed images and private temporary directories were task-owned.

This demonstrates that the tested diagnostic combination did not supply a
usable wall-clock call in these fixtures; the exact trap stage is unverified.
It is not a test of a production Mac runtime, Linux runtime, clock rollback,
real lease or governor. Darwin clock capability and the complete resident
runtime closure must be independently qualified before using the deadline
path; do not infer that the arithmetic-only diagnostic exercised wire 7.

## Source-derived Linux clock observation on Xavier

On 2026-11-04, a Tailscale SSH session to Xavier checked the peer's advertised
host key with strict verification and used the source-derived Linux ARM64 F3
compiler from Actions run 41833823850, SHA-256
`46fce6f620a73d91c7ec482687856a3354646f416e05085a6fa61022d37a5e0b`.
It compiled a short-lived guest containing the actual `pr-wall-ms` source and
a stdout print. Guest source and ELF SHA-256 were respectively
`c6e0175c4c6196b2e322d293f9e7be9bda26632563733a179ac6770b7bc244ab` and
`a46db7c74ae4fb45631b65152ead118fea46eb7bd78c21d74c8be2eca881946c`.
With wire 7 permitted, the guest exited zero, printed an integer, and the
observer checked that it lay between the same host's Python wall-clock samples
with a five-second tolerance. An unpredictable in-band result marker was
required; SSH exit status alone was not treated as proof. The temporary
observer script SHA-256 was
`261eaeb4c1d34bcab37e4068083ab911f0d5eaca9d1d029cbff3d316706599b0`.

The owned compiler and guest processes had 3/4-second soft/hard CPU limits,
8 GiB virtual-address limits and 50 ms RSS sampling with termination above
200 MiB. Observed sampled peaks were 16,512 KiB and 360 KiB respectively;
sampling is not a hard memory cap. The task-owned remote diagnostic directory
was retained. No systemd unit, model, bot, persistent service or existing
workload was started or stopped.

This is one successful point sample on a source-derived Linux diagnostic,
not the pinned public compiler, a Darwin clock test, an independent clock
authority, a monotonicity/rollback test, an end-to-end deadline test, lease
revalidation or governor qualification. The separate Darwin SIGTRAP
observation remains unchanged.

### Expired submission call counter

A follow-up native fixture records calls and passed timeout values in a
test-owned vector. The valid case recorded one call with three seconds; the
expired case recorded zero submit calls. A separate mutant bypassing the
deadline only after expiry failed this assertion, while the original full
budget bypass also failed. This supersedes the preceding limitation that the
expired-case submit call was not independently observed. The clock, unit,
filesystem and submit operation remain stubs; the caveats above still apply.

Updated script SHA-256:
`b5070f271d4c4a630602686d8732f504e29d86a64f8bc5c1007d35f343fe5b09`.
The signed count fixture and expired-only bypass mutant image SHA-256 values
were `a651f0ea2458e333377f2e3fe2253644d3790170d977d88863289ee87fba8d81` and
`44c7e7a6e4adafc30586a6a2db3566cb8d8936f6f171e699fe2a028067beb62d`.

## Generation submission seam with synthetic dependencies

On 2026-11-04, a signed local Darwin source-unity fixture executed the actual
`pr-step-range` and `pr-window-seconds` forms, using historical assembled
dependencies and test-only stubs for runtime validation, count, clock,
filesystem, unit state, submit, response and persistence. With 3.5 seconds
remaining from an initial ten-second budget, the submit stub recorded one
call with a three-second limit and the synthetic step completed. When the
stubbed clock was past the deadline, the step returned
`profile/inference-unavailable-state-retained` and the submit counter remained
zero. Replacing the generation window calculation with the original ten
seconds caused the negative-control fixture to fail. Each compiler and native
fixture process had a three-second CPU limit; no hard memory limit applied.

The temporary generator SHA-256 was
`bc27b08c70bb36b4a4f7ab72411701a1e047854c6ad5d8eb1130500782833f47`.
Good and guard-bypass fixture source SHA-256 values were
`5911c13ebe58c0d6a3d1028a9158e058ffb67cb2201cf88735593fe97d0d40a3` and
`e5fddae847ceeb2ba60af49903bc9b68f4bfe187dca9849a9e13126bf1887ec3`;
the signed image SHA-256 values were
`799c838051b47baeb50e50b450974af3316bbd3f0d08e3fb7ab8aec1db83d2e1` and
`e1f2e024a855f4610854093d3c25222761bd9c19926e350e35379e7ca52d733c`.
The diagnostic compiler/packager are pinned in the earlier section. This is
not a real lease, clock, systemd/curl, model, output recovery, full source-lock
build or qualified governor. Atomic lease fencing remains unqualified.

## Existing count reuse on Xavier systemd

On 2026-11-04, a Tailscale SSH session with strict host-key verification
used the source-derived Linux ARM64 F3 compiler SHA-256
`46fce6f620a73d91c7ec482687856a3354646f416e05085a6fa61022d37a5e0b`
to compile a selected-source fixture on Xavier. The fixture used the actual
current `pr-count`, `pr-unit-state`, clock and FS2 functions with synthetic
request, marker and response files. The source and ELF SHA-256 values were
`d637a6dbc4fbb2789f169bcf56c6aef3fe3df038d6eb140a7e3abedfb3e012e9` and
`9de3b4e83f6ef84fd3311fc0b891770b696bc6a4802a1a759e6adcefb8771dd1`.
The temporary generator and operator script SHA-256 values were
`793c1376c890a277ffec1b3d3c3b370e35224ee22d21ac46ba789059e364f9fa` and
`1eb36f2436171eaffc29a74743a06b9a9d320c7f08b7b91ffbc864632f35f4f1`.

The operator first confirmed the dedicated transient unit name was absent,
then started `/usr/bin/true` as a oneshot unit and waited for its `exited`
state. With matching files, reuse-only returned the synthetic count 2231;
a bad marker returned unknown. After stopping that task-owned unit, an absent
unit with no prior files also returned unknown, leaving no count request,
marker or response files. Guest process spawn was granted only to
`/usr/bin/systemctl`; the operator, not the guest, created the test unit.
The test and a separate host-key-checked systemd query both observed the unit
as `not-found` / `inactive` afterward. The remote test directory was retained.

Compiler and fixture processes had 3/4-second soft/hard CPU limits, an 8 GiB
virtual-address ceiling and 50 ms RSS sampling with a 200 MiB termination
threshold; sampling is not a hard memory limit. This is one host and synthetic
count data, not a real count service, full resident two-phase flow, lease
quorum, curl, model, production provider, source-locked release or qualified
governor. No existing service or model was stopped.
