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
