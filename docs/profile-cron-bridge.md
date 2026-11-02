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

## Controller crash lock

On Linux the controller takes an exclusive nonblocking `/usr/bin/flock` on a
persistent file in the private state directory. It also creates the existing
`lock` directory with a version marker, so an older controller using only that
directory cannot enter while the new controller runs. A later invocation that
holds the kernel lock may remove a marked directory left by a killed process;
an unmarked legacy directory, an incomplete marker, a symlink or an unexpected
directory entry is refused for operator reconciliation. Do not delete or
replace the lock file while any controller or descendant may be running.
Processes inheriting the descriptor keep the lock until they exit. The durable
per-job dispatch marker is separate and is never removed by lock recovery: an
unconfirmed dispatch still stops instead of retrying inference.

A disposable Linux ARM64 VM ran the actual shell controller with test
executables. It checked exclusion against both a second new invocation and the
legacy directory operation, parent SIGKILL while a child still holds the lock,
recovery after the child exits, refusal of an unmarked legacy directory, and
preservation of an uncertain dispatch without another resident invocation.
This is host-local process coordination; it does not fence writers on other
machines, resolve a dispatch of unknown outcome, install a periodic service,
or demonstrate inference or GitHub archival. The reusable fixture is
[`cron-controller-lock-host-test.sh`](../test-native/murakumo/cron-controller-lock-host-test.sh).
