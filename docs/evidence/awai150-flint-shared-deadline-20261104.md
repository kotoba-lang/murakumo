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

## Two-phase resident seam on Xavier

On 2026-11-04, a separate task-owned fixture combined the current actual
`rt-dispatch`, `pr-step-range` and `pr-count` source with the source-derived
Linux ARM64 F3 runtime, diagnostic FS2, host wall clock and real Xavier
systemd unit-state queries. A strict-host-key SSH session compiled and ran
the fixture. The operator confirmed the dedicated count-unit name was absent,
started an `/usr/bin/true` oneshot unit, and waited until it was `exited`.
Each case had separate private files containing a synthetic completed count.

Lease acquisition and validation, runtime/profile validation, count ID and
initial budget were test stubs. The generation submit stub wrote a marker
and returned failure; it never called curl or a model. When the simulated
lease remained the same, the actual two-phase flow reached the submit stub
with 29 seconds, and the expected turn intent and busy marker remained. When
the simulated lease changed after count, the flow returned lease-lost, never
called the submit stub, created no turn intent and retained the busy marker.
A separate strict-host-key SSH check independently inspected those files. The
test and another independent systemd query confirmed the task-owned unit was
`not-found` / `inactive` after cleanup. Test directories were retained.

Fixture source and Linux ELF SHA-256 values were
`24f94613f6e7628f85c6eee41eeb5cfe49f5d61d89fcc2cdeb8563c0c1c0c09d` and
`6e9aad8e0c010fb813aa81a481fd6b7c3b9ee3d3593ef98e560869181cdcff65`;
the temporary generator and operator scripts hashed to
`61b9d24c1e9cd032123f1f3157bbec7caa7902f841eb8cfaded5f1d7d5a423c9` and
`6c9a4fa43e3ce62b467adae72ec695b8f345051c1077197e1ef710a5c1b20cd2`.
Compiler and fixture processes had 3/4-second CPU limits, an 8 GiB address
space ceiling and sampled RSS termination at 200 MiB; sampling is not a hard
memory cap. The earlier pinned F3 hash applies.

This is a real systemd/FS2 integration seam with simulated lease and count,
not a real quorum, model request, atomic fence, source-locked product,
production provider or qualified governor. It does not prove survival across
power loss, complete process lifetime, or safe three-host deployment. No
existing service or model was stopped.

## Native lease client against synthetic loopback voters

On 2026-11-04, a Tailscale session with strict advertised host-key
verification compiled a selected-source Linux ARM64 guest on Xavier with the
previously pinned source-derived F3 compiler. The guest executed actual
`rt-lease` twice and then actual `rt-post-problem`. Twelve selected lease,
HMAC, voter-list and admission function bodies were compared byte-for-byte
with the current worktree. Other dependencies came from the historical unity
and were not all independently pinned as a source-locked product.

Three test-only Python voter servers listened on three distinct loopback
addresses on the same Xavier. They verified the guest's request HMACs and
returned synthetically constructed HMAC-protected replies using a synthetic
token. With the same chosen value, the guest returned
`resident/admission-held` (four requests per server). When the servers
deliberately returned a changed accepted node in the second round, it returned
`resident/lease-lost` (four per server). When two servers deliberately sent
invalid reply MACs in the second round, it returned `resident/lease-lost`
(three per server). All three guest invocations exited zero because their
expected assertions passed; an in-band random completion marker was required.
The test listeners were closed and their threads joined. Test artifacts and
the synthetic token were retained in a private temporary directory.

Fixture source and ELF SHA-256 were
`8bb7ef2fa8eb82590d3b29d1e259c17124e2f8aa4fe2a80f702f5d9c7a34becf` and
`e11e14c1e1367e35ad64ac3b3442118a71513e5e71f40cae3abc7d6e61f84b76`.
Temporary generator and operator script SHA-256 values were
`6eae6359c73de73051b7de93c7b7b6045e5caf10d20cfd3dbadeabba8424b14c` and
`341c639e478f3fa4e274b7967ca87b4b19c70f1ab700fc8e59e85f44a672e6a8`.
The compiler had a sampled peak of 61,588 KiB; the three guests sampled at
1,780, 484 and 484 KiB. Native processes had 3/4-second CPU limits, an 8 GiB
address-space ceiling and RSS sampling with termination above 200 MiB;
sampling is not a hard memory limit.

## Refuse an already-expired budget before the post-count quorum round

The resident now samples the host clock after count and refuses before calling
`rt-lease` if the shared deadline has less than one whole second left or the
sample fails the existing window checks. Previously it would still initiate a
quorum round and only test the remaining budget after that round returned.
The existing post-round lease and budget checks remain necessary: this new
check does not bound the quorum round or atomically fence generation.

On one local Darwin ARM64 host, the selected current `rt-dispatch` was
compiled and executed in the signed diagnostic fixture from
`scripts/native/check-flint-deadline-window.py`. A mock clock changed when a
test-only FS2 directory was created during the count stub. At the deadline the
resident returned `profile/budget-refused`, made only the initial lease call,
and did not enter generation. The normal, changed-lease, expired-retention and
unavailable-count controls passed. A built mutant bypassing the new guard
failed because it made the extra lease call; a separate existing refresh
mutant also failed. Other deadline and count fixture cases passed as well.

The selected assembled source, with the current `rt-dispatch` substituted
into historical dependencies, passed the existing Amu CLI `check`. The
resident source and test script SHA-256 values were
`b63ac3e1871af8407af98266a2326f2c5979eadd72f757cd91d9253081d0f4b6` and
`5c24e5880b050c3cf25b18d5d7828160d55e54cc8e269dc3312be4b81b689d71`.
The passing resident fixture source and signed image SHA-256 values were
`153758be2f6d9ce9424c907677cb89da62821805f8bba498a9710d66631c43b8` and
`f21d801ac9ec3bcab5f603cb7056e18f6ee5ff6501db5f480232fbc640d4d8af`;
the guard-bypass mutant values were
`4fc04e95cfc373b0c6d7ddc1fdadff483932fe2cc76fe52501a7c5b4d62fbfb5` and
`f9975763b1c5dd861b4e44bec427e102e9f2e3c8ebea5d0cefdcb2bd4a14399a`.
Compiler and packager were the pinned diagnostics described above; each
compiler and native process had a three-second CPU limit, without a hard
memory limit. The clock and lease were test stubs and no voter or model was
contacted. This is not a monotonic-clock, source-locked product, live quorum,
receiver-fencing or governor qualification.

The servers were scripted mocks, not the actual native voter implementation;
all ran on one machine and the changed value was deliberately injected. This
tests the selected native client's loopback/HMAC and lease comparison, not a
real authenticated fleet quorum, real lease loss during count, the full
two-phase resident, receiver fencing, model inference or governor
qualification. No persistent service or model was started.

## Combined two-phase and native lease-client seam on Xavier

On 2026-11-04, a single bounded fixture combined the selected current
`rt-dispatch`, `rt-lease`, `rt-post-problem`, `pr-step-range`, `pr-count`,
`lease-group-round`, `ln-ask` and `recovery-lease-problem` bodies with the
diagnostic FS2 and host clock. These eight bodies were compared byte-for-byte
with the current source; other fixture dependencies were historical. Runtime
and profile selection, count ID and generation submission were test stubs.
Generation submission only wrote a sentinel; it made no model request.

Three Python mock voters on separate loopback addresses checked request HMACs
and returned replies signed with a synthetic token. The fixture reused a
completed synthetic count through a task-owned transient systemd unit, then
performed the actual selected native lease-client check. The unit was absent
before the test and independently verified `not-found` and `inactive` after
cleanup; all mock listeners were closed. No persistent service or model was
started. The retained remote synthetic artifacts are in
`/tmp/awai-lease-loopback-8iq2tlua`.

| Third-round voter behavior | Observed result |
| --- | --- |
| Same lease value | Generation stub reached; turn intent and busy marker present; stub received a 588-second timeout. |
| Changed lease value | `resident/lease-lost`; no generation sentinel or turn intent; busy marker retained. |
| Two invalid reply MACs | `resident/lease-lost`; no generation sentinel or turn intent; busy marker retained. |

The changed value was deliberately injected by the mocks and is not evidence
of normal quorum evolution. All three voters ran on one machine; this was not
the native voter implementation or a fleet quorum. Real model inference and
GitHub state publication were not exercised. The lease can still change after
the post-count check and before submission; atomic receiver-side fencing and
qualification of the process/resource boundary remain necessary.

The generated fixture source SHA-256 was
`5b44f7efa1f60552e1716020eec38c2de1644af2c1f09b773d4ad223a6a4760a`;
its generated ELF SHA-256 was
`f12b5de438648a4346f453e7838f81b7fffdfa4420e88fd6c6755aa86b6c542a`.
The exact fixture source and operator script are [archived here](awai150-two-phase-lease-20261104/README.md).
The source-derived Linux ARM64 F3 compiler SHA-256 was
`46fce6f620a73d91c7ec482687856a3354646f416e05085a6fa61022d37a5e0b`.
The fixture builder and operator script SHA-256 values were respectively
`1b398d6f865294af55b9a40e5c3ca99d14e3ef23212b320e4310188c31625ce7` and
`9de01f28fbfb3dadb4c91e7dfc06a7d8ec177b93520677d9fda27f7dbc94b570`.
Compiler sampled peak RSS was 62,928 KiB; the same, changed and invalid-MAC
guests sampled at 3,988, 3,196 and 2,668 KiB. Native processes had 3/4-second
CPU limits and an 8 GiB address-space ceiling; RSS was sampled and processes
were terminated above 200 MiB, which is not a hard RSS limit.
# Wait-loop clock rollback guard (2026-11-05)

The count and Flint generation wait loops previously compared each wall-clock
sample only with the initial start. A sample of 106500 ms followed by 103500
ms, both after a 100000 ms start and before a 110000 ms deadline, was therefore
not detected as a rollback. Each loop now compares against its immediately
preceding sample. A detected rollback returns unknown/failure and does not by
itself prove the unit stopped; the existing dispatch and occupancy recovery
rules still apply. Non-Flint generation does not sample this clock.

`scripts/native/check-flint-deadline-window.py` compiled and ran selected
source-unity Darwin diagnostics with a stub clock and stub unit. For count
and generation, the rollback case refused before a second unit poll; the
original comparison mutant polled again and failed the assertion. A
106500-to-107000 ms forward control completed after a second poll. Existing
deadline, expired, reuse and resident-refresh controls and their mutants also
passed. Compiler SHA-256 was
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`,
packager SHA-256
`df0e94211a35d84901ebcf2a8620cb653140d957732474ff77bd0cdd5b5bf4c5`;
each diagnostic used a 3-second CPU limit without a hard memory limit.

The guard observes only samples within an individual wait invocation. It
cannot detect clock changes between samples, across phases, or across a
restart, and wall time is not a monotonic clock. These stubs do not exercise
the production host clock, systemd, curl, lease fencing, model, GitHub state,
or an end-to-end governor. Trusted monotonic timing, native process lifecycle,
and the rest of the integration remain unqualified.
