# Cron to read-only MTP resident

AWAI-147 connects the native cron cursor to the existing read-only MTP profile
resident. `profile_cron_bridge.kotoba` composes the pinned cron planner,
agent-step identity, checkpoint and resident sources. It requires the due
schedule's profile and policy to match the host-pinned resident config, and
its source CID to match the exact host-pinned work-order digest. The resident
later verifies that digest again against the restored checkpoint. A changed
work order is refused rather than silently ignored.

The bridge checks runtime pins, the independently verified retained head and
a fresh GitHub archive acknowledgement before installing a canonical cron
request. A different unconsumed request or leftover temporary file is refused.
Completion requires that the current verified, archived head records the same
request CID. The local head, archive acknowledgement, clock and all config
paths are trusted host state; the acknowledgement is not a distributed
lease, and this bridge cannot prove that a socket peer loaded the named model.

`scripts/itonami-cron-resident-once.sh` is an explicit single-cycle host
controller. It verifies/refreshes the current archive, prepares the request,
durably creates a dispatch marker, calls the existing resident once, archives
the successor, checks completion and only then advances the local cursor. If a
completed head is already present after a crash, it can reconcile without
another inference. An existing dispatch marker with no completed head is
UNKNOWN and stops for operator reconciliation. The host must provision and
pin every executable and the archive operation, protect the state directory,
serialize *all* resident writers, classify data for GitHub, grant only the
necessary capabilities and independently qualify model and memory resources.
The script is not installed as a periodic service and does not authorize
tools, external messages or payments.

An ARM static source-unity gate passed; removing the work-digest check failed.
An x86 Linux fixture used the actual native bridge on a copied, valid signed
checkpoint with two retention receipts. It checked idempotent request
installation, work mismatch, a different pending request, simulated consumed
head and expired archive acknowledgement. A controller fixture used the actual
planner and bridge but simulated the resident publication and archive
acknowledgement: it advanced only after the simulated completed head, reused
that head without a second dispatch, refused an existing uncertain dispatch,
and stopped on a missing archive confirmation. The synthetic head and ack do
not prove a GitHub transaction or inference turn. No source service or key
was changed. [Evidence](evidence/awai147-cron-mtp-bridge-20261103.json).

An earlier separate resident integration did perform a Bonsai MTP turn and a
GitHub restore, but used a different setup. The new cron-to-MTP path, real
periodic activation, independent multi-host fencing, physical resource
reservation and the ten-Mac production fleet remain unqualified.

## Retention renewal before a cron dispatch

The controller now invokes `resident refresh CONFIG` before archiving a due
slot. This operation does not infer or consume the cron request. It verifies
the current head, pinned checkpoint closure, pinned runtime and the existing
pair of retention signatures even if their deadline has expired. An intact
head whose retention expires within 15 minutes is re-replicated and published
with fresh retention receipts; otherwise it is left unchanged. A missing,
invalid or unverifiable head is refused. The controller requires an exact
successful refresh result before archive, request installation or a dispatch
marker. All resident writers still require host-level serialization.

The macOS-host controller sequencing test uses test doubles and substitutes
BSD `stat`; it checks that an invalid head stops before archive or bridge and
that ready and refreshed cases proceed. An Amu macOS compiled source-unity
test checked the decision boundaries; its bypass mutant returned `1` while the
original returned `0`. A subsequent Linux ARM64 static source-unity build with
the git-checkpoint pinned root compiler also returned exit 0, and the bypass
mutant returned exit 1. The [evidence](evidence/awai147-retention-refresh-native-20261103.json)
records exact hashes. This is a local native gate, not a restarted CI pipeline.

An isolated Linux VM test then created *expired*, independently signed
retention receipts with public test keys for a test checkpoint. The actual
native resident `refresh` rejected an invalid receipt reference and, with the
peer stopped, preserved the old head. With the native checkpoint peer running,
it uploaded the verified closure, acquired fresh receipts, published the same
checkpoint head and independently verified the new signature pair. A repeated
refresh left the head unchanged; no request was installed. The two test
identities and replica stores shared one VM, so this does not qualify separate
failure domains, GitHub archival, crash recovery or model inference. The
[host harness](../test-native/murakumo/resident-retention-live-host-test.py)
requires a disposable VM, separately built fixture binaries and a test-only
checkpoint; it must never be pointed at live credentials. [Evidence](evidence/awai147-retention-refresh-live-20261103.json).
No periodic service has been installed.

A further isolated Linux VM test used a classified checkpoint already stored
in the private GitHub archive. The native resident renewed expired public-test
retention receipts through a native loopback peer, then the real resident
archive adapter checked the private repository, fetched the immutable archive,
verified its tree and checkpoint closure, and wrote an acknowledgement matching
the checkpoint and tree. The previously archived tree was independently read
back; the test did not establish whether the attempted Git push created a new
ref. The test used same-VM test signers, no cron controller, model inference or
production credentials. Its short-lived GitHub credential was delivered to the
VM process via stdin and the dedicated VM was removed. The optional
[host harness](../test-native/murakumo/resident-retention-github-host-test.py)
requires a disposable VM, test-only fixture and explicitly selected private
repository. [Evidence](evidence/awai147-retention-refresh-github-20261103.json).

A later isolated ARM64 Linux VM exercised the actual cron planner, controller,
bridge, resident, checkpoint peer and GitHub archive adapter together. The
resident started with an expired signed head and no live voters; the GitHub
archive was an existing classified test checkpoint. It renewed the head,
read back and verified the private archive, prepared the cron request and
reported `resident/quorum-unavailable`. The cursor remained unchanged and the
durable dispatch marker remained. A second invocation refused the uncertain
dispatch without calling the resident again. The controller's missing execute
bit was discovered by this test and fixed. The two test identities were
colocated in one VM; no MTP inference, successful cursor advance, real periodic
activation, independent failure domains or production membership was tested.
The [host harness](../test-native/murakumo/cron-resident-github-failure-host-test.py)
requires a disposable VM, pinned binaries, a classified test fixture and a
private GitHub repository. [Evidence](evidence/awai147-cron-github-quorum-failure-20261103.json).

## Guarded real-model attempt

A later attempt prepared an isolated Linux ARM64 VM and the native binaries
for a complete cron turn, then started the existing Bonsai MTP runtime on one
Mac mini under a memory-pressure watchdog. The pre-start snapshot showed 90%
reported free memory pressure and zero used swap. During model loading, the
reported free percentage fell below the watchdog's 40% floor; the watchdog
stopped the task-owned model process before the endpoint became ready. The
exact minimum was not captured. Afterwards the measured free percentage was
again 90%, swap used was zero, the task-owned port was closed, and the VM had
been removed. This snapshot does not prove absence of any transient impact on
other workloads.

The cron controller, GitHub archive for a new successor and MTP inference were
not run in this attempt; no new result was produced. This does not establish a
safe single-host capacity or that a different host can accept the model.
[Evidence](evidence/awai147-cron-real-model-admission-20261103.json).

## Model submission recovery candidate

The draft resident now observes systemd's load and substate separately. Only
an explicit `not-found` result, with no prior request, dispatch marker or
response, allows a first submission. It writes and flushes the request and a
local dispatch marker before starting the unit. If the request or marker is
left behind without a discoverable unit, the result is unknown and the
resident does not submit again. An existing running or exited unit can be
observed only when its saved request matches the expected bytes. The private
cache still needs external single-writer serialization; these files do not
provide a distributed fence or survive an untrusted host rollback.

A low-cost Mac Amu CLI check accepted the integrated source. Extracted tests
of the exact decision functions returned 0; controls removing the marker check
or ignoring a simulated failed `systemctl` query returned 1. Those tests use
mock provider responses and the CLI prints the Kotoba result on stdout (its
process exit status alone does not distinguish the controls). A disposable
Linux VM independently showed `systemctl --user show` for a never-created
unit returning `LoadState=not-found` and `SubState=dead`, both with exit 0.
The VM was stopped and removed. These observations do not execute the full
resident, an actual inference or its crash/restart. A Mac FS capability probe
trapped instead of running.

A separate ARM64 Linux native fixture copied the actual request-write and
marker functions, while substituting byte-equivalent text identity for CID
calculation. It exercised real local file writes and fsync, normal process
restart, a request left without a marker, and SIGKILL after marker creation.
Each subsequent attempt refused to prepare the same submission. A control
that removed both existence checks returned 1. The source, artifact hashes
and limits are recorded in [evidence](evidence/awai150-dispatch-marker-native-20261103.json).
This tests the isolated filesystem sequence, not power loss, a complete
resident, a real systemd dispatch or a distributed fence. The current resident
also uses systemd and curl, so this draft is not a qualified Kotoba-only
operational path. See AWAI-150.

A follow-up ARM64 Linux native fixture combined the actual status-query,
decision and marker functions with a real user systemd instance. Its
substitute submitter started a transient `touch` unit, rather than the
resident's curl or a model. The initial request prepared its marker and the
matching completed unit was reusable. After stopping the unit, systemd
reported `LoadState=not-found` and `SubState=dead`; the saved request and
marker made the next attempt refuse. With both user-bus environment paths
invalid, the real query failed and the native decision returned UNKNOWN.
The first fixture's `--wait` and `RemainAfterExit` combination timed out;
the successful run used `--no-block`, as recorded in the
[evidence](evidence/awai150-systemd-submission-20261103.json). It substitutes
text identity for CID comparison and does not test the full resident, its
actual curl request, inference, a crash during inference, power loss, or
concurrent writers. The disposable VM was stopped and removed.

A further isolated ARM64 Linux fixture used the actual `pr-submit` and
`pr-wait` functions: systemd started curl against a private Unix socket whose
test server checked a fixed nonsecret request and authorization value. A
valid request returned a fixed response exactly once; after removing the
unit, the saved marker prevented another request. A deliberately invalid
authorization value received HTTP 400, the unit failed, and recovery also
refused a second request. The fixture replaces profile/CID and response/MTP
validation with fixed test stubs; the test server's Python is a dated,
one-time test dependency only. It does not exercise a real model or the full
resident. See [evidence](evidence/awai150-curl-submission-20261103.json).

An additional native ARM64 fixture executes the actual `pr-step-range` path
with real systemd/curl and a private Unix-socket test server. Its profile and
runtime checks, CID and hash, JSON admission, MTP classification, answer and
response compaction are explicit fixture stubs. In the first case, output and
next state were written after one request. In the second, the native process
was killed after the server recorded the request; when the existing unit
completed, a fresh process reused its response and published output and state
without a second request. In the third, both the process and its unit were
stopped before the response: the fresh process returned
`profile/inference-unavailable-state-retained`, left state unchanged and did
not send another request. Stopping curl caused the test server's expected
broken pipe. The [fixture evidence](evidence/awai150-profile-step-20261103.json)
does not establish real profile authorization, MTP correctness, a full
resident turn, GitHub storage, power-loss recovery or native-only operation.

The next fixture removes those profile, JSON, hash and MTP-validation stubs.
It composes 106 reachable Kotoba functions from the pinned source-unity
closure and uses the real `pr-step-range`, profile/role digest checks, JSON
validation, SHA-256, CID, MTP response checks, output compaction and state
writes. In a disposable ARM64 Linux VM, a fixed-response Unix-socket test
server supplied fabricated model/MTP fields. A valid input produced a
compacted output and a next-state CID that an independent calculation matched.
A changed role digest was refused before dispatch, and a wrong model in the
response was refused without a second server request. Killing the native
process during a held request and then allowing the unit to finish allowed a
fresh process to publish the verified output with one server request. See
[fixture evidence](evidence/awai150-profile-validation-20261103.json).

The draft additionally syncs the repository directory after creating
`outputs`, and syncs the output file and its parent directory before writing
or renaming the new state. A failed sync stops state publication. Small macOS
Amu source-unity fixtures with mocked filesystem calls exercise the real
`pr-save-output` and `pr-step-range` paths: the output fixture returns `0`
for successful writes and the file/directory failure cases; removing its
output-directory sync makes it return `1`. The repository-sync fixture
returns `0` without `UNSAFE_WRITE`; removing the prepublication repository
sync emits `UNSAFE_WRITE` before returning `0`. CLI result values are stdout,
not exit status. See [output fixture](evidence/awai150-output-sync-fixture.kotoba)
and [repository fixture](evidence/awai150-repo-sync-fixture.kotoba). These
mocked cases do not prove filesystem power-loss behavior, a complete resident,
or that the unqualified systemd/curl path is suitable for deployment.

A separate nonsecret GitHub transport observation used two independent
clones of the new private bot-state repository. Two distinct commits shared a
base; the first push created a temporary ref and the stale second push was
rejected with `fetch first`. A separate bare clone fetched the ref and checked
the first commit and file bytes. The observer removed its unchanged test ref
and a later `ls-remote` confirmed its absence. This does not exercise the bot,
native GitHub writer, concurrent lease/fencing or ambiguous acknowledgement;
Git ref conflict handling alone does not fence an authorized stale writer.
See [evidence](evidence/awai150-github-cas-live-20261103.json).

A further ARM64 static fixture exercised the actual `pr-save-output` and
native wire-35 file operations on XFS in a disposable VM. New and matching
files succeeded, a mismatching file was preserved and refused, and an
injected output-directory sync failure was refused. The sync-bypass control
failed. After an orderly VM restart, the written output remained and was
reused. CID hashing was replaced with text identity and the failure was
injected; this does not demonstrate sudden power-loss behavior or complete
state publication. See [evidence](evidence/awai150-output-sync-native-20261103.json).
This still uses systemd/curl and a fake server; its reported MTP fields are
test data, not proof of model execution. It is not a complete resident,
GitHub archive, power-loss or distributed-fencing qualification.
The resident cycle refuses an existing head whose checkpoint closure or
replica receipts fail `rt-intact-head?`; it does not replace those receipts by
re-replicating. A missing head may still bootstrap, and an intact expired head
may refresh. `docs/evidence/awai150-head-repair-fixture.kotoba` exercises the
actual cycle with mocked dependencies: the three cases return the expected
results, while removing the production guard makes the fixture return 1.
This does not authenticate mutable head fields such as `request` or `nextAt`,
or prove real replica, restart, or concurrent writer behavior.

Head admission also requires the exact seven-field canonical schema: raw CIDs
for the checkpoint and receipts, a raw request CID or `none`, a nonnegative
integer `nextAt` and a positive integer `until`. The small source-closure
fixture `docs/evidence/awai150-head-shape-fixture.kotoba` exercises the real
JSON, CID and shape functions; its invalid-time bypass control fails. This is
format validation, not authentication of the time or consumed request.

The draft resident now stores a signed head envelope: the key must match the
host-pinned `authorB`, and the resident and cron bridge verify its signature
before using the payload. Publishing also verifies the candidate and binds it
to the expected checkpoint, schedule and request. The source-closure fixture
`docs/evidence/awai150-signed-head-fixture.kotoba` uses the actual Ed25519,
JSON, CID and head functions with a fixed public test seed; it detects changed
schedule or request, wrong signers, unsigned heads and malformed input. An
Amu macOS run printed 0; bypassing signature verification printed 1. A
preliminary run trapped on malformed JSON, leading to an explicit validity
check before canonical parsing. The native Linux attempt did not run: its
disposable VM entered emergency mode during ignition and was removed.

This draft format refuses earlier unsigned heads and has no migration yet.
It does not prevent replay of an older valid signed head, qualify key custody,
prove the complete resident/bridge integration, or authorize deployment.

`docs/evidence/awai150-signed-candidate-fixture.kotoba` exercises the actual
candidate publisher and real signature verification with a fixed test key.
It accepts a matching signed candidate and rejects changed checkpoint, time or
request, missing candidates and unsigned candidates. Removing each of the
three expected-value checks separately makes the fixture print 1. This fixture
substitutes in-memory filesystem access and a shape-only replica-admission
predicate; it does not test persistence, actual receipts or concurrent writers.
