# One durable cron cycle

`scripts/itonami-cron-run-once.sh` composes the native cron cursor from
[itonami-agent #164](https://github.com/cloud-itonami/itonami-agent/pull/164),
the cron job-node adapter and the verified GitHub output archive. A trusted
host provides pinned executables, definition, source, private state directory,
cursor, signer DID, repository and explicit `ITONAMI_*` node configuration.
Run with no arguments for the complete usage line. These are host inputs, not
data chosen by a model or restored checkpoint. Paths used by the native tools
must be inside the host's granted filesystem root and fit their path bounds.

The runner serializes one host with a private lock directory. It asks the
native planner for the first due slot, stores its job identity and wrapper,
and creates the output log exclusively before dispatch. The native node's
existing attempt marker adds another local refusal against repeated starts.
The archive command snapshots and verifies the signed output, writes it to a
private GitHub per-job ref and checks a fresh readback. Only then does the
native cursor advance and fsync. `cursor/idle` returns without a job.

An existing log is checked and archived again without re-executing the cell.
This permits recovery after the archive succeeded but cursor advancement did
not. A partial log, pre-existing lock, ambiguous marker, changed input, archive
conflict or failed cursor update stops for reconciliation. A crash before a
new log receives a complete result can require manual recovery even if no
cell actually started. The runner never automatically deletes an uncertain
log or attempt; it also does not prove a writer's lease remains live at the
external effect boundary. An old cursor and state copied to another host are
not a distributed lock or a durable shared cursor.

An isolated ARM Podman fixture exercised the real native planner, cron adapter,
job node, admitted read-only cell, three authenticated loopback voters and
GitHub archive. The first job completed and advanced the cursor; a next poll
was idle. Restoring the previous cursor simulated the post-archive crash
window: existing output was reverified and the cursor advanced with the same
single attempt. An incomplete log did not advance the cursor, and a held lock
was refused. The native cursor gate passed; bypassing its schedule pin failed.
[Evidence](evidence/awai147-cron-runner-github-20261103.json).

The cell was rule-based, the voters shared a VM and the signing key was public.
This does not prove MTP inference, durable distributed consensus, real
power-loss behavior or production admission. The runner is a single invocation,
not an installed service; trusted schedule enrollment, periodic activation,
production identity, resource reservations, effect fencing, and full bot
state restore are still required. The host must classify output before enabling
capture and protect the private log and GitHub credentials.
