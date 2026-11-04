# Profile state publication acknowledgement

`pr-step-range` previously returned `profile/state-publication-failed` when
its final rename or repository-directory sync did not report success. A failed
or lost acknowledgement can leave the new state visible, so the return is now
`profile/state-publication-unknown`. The host must reconcile durable state and
must not infer permission to dispatch another request from this result.

A local signed native source-unity diagnostic used the current `pr-step-range`
with stubbed filesystem, transport, runtime validation and submission. It
injected a failed final sync *after* a simulated successful rename; success
returned a completed result and the injected failure returned unknown. Replacing
this final result with the previous `failed` value made the same fixture fail.
The source/image SHA-256 values were:

- corrected: `bfbd5bfb76c13bf526cdbb9019938cc972843d69663bf73bef225fdc622a06c6` / `2a26b608c180643ab348b615010d8150fd870d2ae2400770b552d7d35989aac0`
- previous-result control: `ee2728e69f2234e918fe5bfb9807fe01ef643f383c7f6fddddc2b9652d64db99` / `504fd177281cb34f8f132a85b34dde60a125b85465834a049d43ecaa8ce7f916`

Each native process had a three-second CPU cap. This is an injected branch
exercise, not a real rename, fsync failure, crash, power loss, full source-lock
build, retry policy, supervisor, or proof of recovery after an ambiguous write.
In particular, other runtime kinds still require a trusted host to serialize
and reconcile state before another inference.

## Candidate for an already-visible state

`profile-recovery-main reconcile-published REPO RUNTIME CACHE OLD-STATE REQUEST-ID COUNT-ID`
adds a separate reconciliation path for a Flint turn when rename made the new
state visible. The exclusive trusted host must supply the exact prior state and
retain the same authenticated runtime/instance, cache, and systemd unit; it
must prevent aliases, concurrent writers and independent server users. The
candidate does not infer these facts from caller arguments. It verifies the
request's identity, matching intent and dispatch marker, completed unit,
validated response, compact output and expected current state. A leftover
`state.json.tmp`, missing/mismatched evidence, or failed sync refuses. It syncs
the evidence, output and state before attempting the existing matching busy
marker release. Failed or ambiguous release still returns unknown. It never
starts an inference or rewrites the profile state. If the release happened but
its acknowledgement was lost, separate reconciliation remains necessary.

A native source-unity fixture called the actual new function with stubbed
filesystem, systemd and release operations. A matching synthetic case returned
reconciled; mismatched current state, request ID, count ID and failed sync
returned unknown. Removing the current-state comparison was detected by the
negative control. The positive and control source/image SHA-256 values were
`c156ca5d8cad52e96849f831c53069e9ae60c9e092de4ddc4bc07457e66bc6b2` /
`3e8a9c76989d6db60695c4eddf36ff5bfc6e41d16ed7a78bd361b3c1ad65392f` and
`c45c29fd627fa65e5cf87feca4d65c0426f64d04b47b41bda55d65c390aeb3d2` /
`67938449879a8eb3da506efbd3f4565dd4d530d0b1b67fe82cd408c2455fe2db`.
The actual command entry and candidate passed a selected source-unity Amu
check. This is not a real filesystem or systemd recovery, authenticated process,
crash test, qualified native provider, source-locked build or live bot run.

## Local native FS2 exercise

A follow-up on one local Apple Silicon Mac used the actual current reconciliation,
response validation, turn intent and busy-marker functions with the pinned
Darwin FS2 diagnostic provider. It created separate task-owned directories and
synthetic requests, responses, outputs and state. The `pr-unit-state` function
alone was replaced with a test stub reporting `exited`; there was no systemd
unit, network request or inference. Each native process had a three-second CPU
cap.

In the matching case the operation returned `profile/publication-reconciled`;
an independent filesystem check found no busy marker and one `.last` marker.
Four separate cases with a mismatched state, an existing `state.json.tmp`, a
missing response, or a mismatched count ID each returned
`profile/state-publication-unknown` and retained the busy marker without a
`.last` marker. Source/image SHA-256:
`935cf3f1ef93843788b073da24857b7658f4233af943da2fa10c4c0635ac42b8` /
`f119560e61989302c7c69537f0a1c2aa3e3262876c894848c526cf17795128ba`.
Compiler/provider pins are the diagnostic pins in the preceding evidence.
The test does not exercise real systemd, production credentials, lock
ownership, response loss after marker rename, provider I/O errors, a crash,
power loss or the full source-locked application. Its synthetic directories
are retained for inspection; no user workload or service was changed.

## Release acknowledgement barrier

The earlier busy-marker protocol could return unknown after moving `.busy` to
`.last` yet admit a new turn, because the next reserve observed no `.busy`.
A native local FS2 fixture reproduced that sequence after a test wrapper
withheld the directory-sync success response: the old code admitted the next
reservation while both old `.last` and new `.busy` existed. The fixture source
and image SHA-256 were `202f56a04d4956599dc170e303a85233f6681a24d15a5e4814a7e40865e93f46`
and `74677cca0fee152b55ff37087c83e59b0f345e488904364948eacaa4ae4d190a`.

Release now writes and syncs a matching `.settled` receipt only after the
`.last` rename and directory sync report success. A new reservation must see
both absent (initial state), or validate and sync both matching markers, move
`.settled` to `.previous`, sync that transition, and only then create `.busy`.
A matching existing `.busy` is reusable only with `.settled` absent. Any
missing, malformed or unacknowledged state refuses; older `.last` markers
without a receipt require host reconciliation and are not migrated silently.
After verifying the already-visible output and state, `reconcile-published` can
also re-sync a matching `.last` and establish the receipt if release was
interrupted. A count-only release without that evidence still requires separate
host reconciliation. The host must enforce exclusive ownership and preserve
all markers; this is not a distributed fence or a process supervisor.

With the same real local FS2 provider and synthetic fixture, the injected
lost-sync response left `.last` without `.settled`, returned unknown and
refused a new reservation. A separate retry, with the fault removed, verified
the published state and wrote `.settled` without starting inference. Source /
image SHA-256: `cdb179a96f2524e606133245cd2105ecf2721492aad7ca9d8c4973cffcbcb1d6` /
`83bb77c5b05896f9fd8d1466222ab5a9a54f6b1ca24c87bca2c8f12a3155afd7`.
A successful release followed by a second reservation also passed, including
receipt consumption; source / image SHA-256:
`e36de5680a6ea1f10db82f87cfa91d42b560e5b17ccd8c609b0ccbdd90976ad3` /
`47e5af5be05020932d2bc42a062d663d162b9c13526f732dc6320d032e911589`.
The regular success and state-mismatch, pending, missing-response and wrong-ID
cases passed again. The systemd unit state and sync failure were injected;
no actual sync error, power loss, multi-writer race, service or model was run.
The repository's `test/native/flint_busy_cycle_cases.kotoba` records the normal
release/reacquire cycle and an unpaired `.last` refusal. It ran with the local
native FS2 diagnostic, source/image SHA-256
`bb3021dc8f4dda862dfa18c524510a28825acb63515bb2c3f804608b40a28489` /
`f8f9fae20aa09465cfdefe3e4107f4481fa3d85955d465f448e7ea2901aef470`.

## Count-only recovery

The earlier statement that count-only release requires separate reconciliation
is now addressed by the candidate command
`profile-recovery-main reconcile-count REPO RUNTIME CACHE OLD-STATE COUNT-ID`.
Under exclusive trusted task/server ownership it verifies the unchanged old
state, an absent temporary state and generation intent, exact count request
identity, durable dispatch marker, completed count unit and a valid count
response. It syncs these files and rechecks intent absence before finishing a
matching busy release or reconciling its already-visible `.last`. It starts no
inference and does not modify the profile state. Unit identity and runtime
remain trusted host responsibilities.

A local source-unity fixture exercised the actual candidate and busy-marker
operations with the Darwin FS2 diagnostic provider and an `exited` unit stub.
A matching synthetic count reconciled; mismatched state or count ID, pending
state, generation intent, missing response and malformed response refused and
retained `.busy`. Source/image SHA-256:
`515a3003594910aaea33e72d38b3a3729c576ecc1ea8172728b5005e1cb807f8` /
`91d372992ef79d6449e8e664dec2c7be6544960563d56d20c5c888784854acd7`.
An injected lost release-sync response refused; a later retry with the fault
removed reconciled and established `.settled`, without starting generation.
Source/image SHA-256:
`961d20017557dcff8b9d50b46a4dd512adff86558a3c98b86516400072d77fa7` /
`eb03651632198a563b410de0768f1416f7115aceef736ad5b939e7b509a28997`.
These are synthetic files and injected unit/fault observations, not real
systemd, provider faults, crashes, power loss, concurrent writers or a live
model. Each native process had a three-second CPU cap.

The initial empty-marker case also now requires `.previous` to be absent;
otherwise a missing `.last` and `.settled` cannot be assumed to mean a fresh
server. The updated stored native cycle case passed with the FS2 diagnostic,
source/image SHA-256
`3fcc483a0319c5954603178de76e5e22042b68e6751727d9be0312de2488e387` /
`09ccc048d6413e93b3b1101ce5f008c0a8ff59ec41b9a486871083a85e733e36`.

## Xavier native systemd integration attempt

On 2026-11-04, a Tailscale SSH session with strict host-key verification to
Xavier compiled the actual selected count-reconciliation source with a real
`pr-unit-state` and a synthetic profile/response. The diagnostic Linux ARM64
compiler SHA-256 was
`b6bc55a5424742fee501b7ae27e9cf7c5a7bf63538c98f5fda94bd63fdba6e6d`,
source SHA-256 was
`0af96af961dd0987468e787a5c1ed6177cbc160c1561fdd2a1a2890f94d2d032`,
and generated static ELF SHA-256 was
`c26d649f1e89ba3eb182e97d73d3988a0c39adb65fb0c4669464f62090e2deb3`.
The unique test-owned transient oneshot unit reached `exited`, but the
fixture returned exit 126 with `spawn/isolation-unavailable` before it could
query the unit. Its synthetic busy marker remained. The owned unit was stopped;
a separate SSH observation found `LoadState=not-found` and
`ActiveState=inactive`. The running and absent unit cases did not run.

On the same host, Linux `5.10.120-tegra`, a separate short-lived Python process
called `close_range(100, 100, 0)` successfully and
`close_range(100, 100, CLOSE_RANGE_CLOEXEC)` returned `EINVAL` (22). The pinned
Amu ARM64 spawn source requests `close_range(3, UINT_MAX, 4)` and maps a
close-range setup failure to `spawn/isolation-unavailable`. This makes the
unsupported flag a strong explanation for the observed refusal, but the trap
does not independently identify the exact failing setup stage. The call only
involved a test-owned process; no existing service was stopped. The compiler
and fixture used a 3-second soft / 4-second hard CPU limit, an 8 GiB virtual
address ceiling (the runtime reserves large sparse mappings), and 50 ms RSS
sampling with termination above 200 MiB; sampling is not a hard memory limit.
Task-owned temporary directories were retained for inspection. This is not a
qualified supervisor or a successful systemd integration; changing descriptor
isolation or replacing this refusal requires native host and security-contract
validation. No model, bot, network inference or state publication ran.

## Source-derived runtime with real Xavier systemd

GitHub Actions run [41833823850](https://github.com/kotoba-lang/amu/actions/runs/41833823850),
PR head `a0dac512`, completed its ARM64 bootstrap job successfully. The receipt
pins the changed `seed/77-aarch64-static-rt.kotoba` to SHA-256
`595adeb9fbd706a48b7c1155394f86c49a8e844949e8edb291c0a8b3b1681862`;
its F2 and F3 compiler images both hash to
`46fce6f620a73d91c7ec482687856a3354646f416e05085a6fa61022d37a5e0b`.
The checked-in root did not reproduce this image; this is not a root repin or
public release. The F3 compiler produced the selected synthetic count fixture
SHA-256 `e914d81eea125ac8a7ead24ccf8e4dc2625523d150c046f40374630615ed84b1`
from source SHA-256 `0af96af961dd0987468e787a5c1ed6177cbc160c1561fdd2a1a2890f94d2d032`.
The source used actual count reconciliation, busy marker, FS2 and `pr-unit-state`
functions with historical assembled dependencies; it is not a source-locked
whole-application build.

On Xavier's Linux `5.10.120-tegra`, with process-spawn grant restricted to
`/usr/bin/systemctl` and scoped FS2 test data, the actual generated runtime
successfully handled a unique test-owned transient oneshot unit. When the unit
was `exited`, the synthetic count reconciled (exit 0, busy absent, last present).
With the same test unit observed running, reconciliation refused (test exit 0,
busy present, last absent). A separate run with the unit absent also refused
with busy retained. This is evidence that this generated runtime executed the
systemctl path on this host, not authentication of a production server or
model, process-tree termination, kernel memory limits, crash recovery or a
qualified governor. No model or inference request ran.

The first attempt to start the running unit waited for a oneshot and timed out;
although the start response failed, the unit had started. It was stopped and
independently checked. A rerun used `--no-block`; stopping its running unit left
it in `failed`, so the task-owned unit was stopped/reset and an independent
query confirmed `LoadState=not-found`, `ActiveState=inactive`. The separate
absent-unit run confirmed the same final state. The compiler and fixture used
3/4-second soft/hard CPU limits, an 8 GiB virtual address cap for sparse runtime
mappings and 50 ms sampling with a 200 MiB RSS termination threshold (sampling
is not a hard limit). Task-owned temporary diagnostic directories were retained.
