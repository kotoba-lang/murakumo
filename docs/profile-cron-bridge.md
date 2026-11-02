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
