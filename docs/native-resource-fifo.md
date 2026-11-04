# Native local resource FIFO candidate

`kotoba/resource_fifo.kotoba` implements a bounded, local, durable queue of
up to 64 caller-supplied 64-character lowercase hex tickets. A trusted host
provisions a private directory containing a stable `queue.lock` and an empty
`queue` containing exactly `RGQ3` followed by LF. `RGQ1` and `RGQ2` are
intentionally incompatible and are refused. Migration requires the host to
stop and exclude older writers, reconcile all existing queues, reservations
and workloads, and migrate offline; changing the header alone is not a safe
migration. The component takes a nonblocking filesystem flock before every
operation. `enqueue` appends a unique ticket, `front` reads the head when no
reservation or release is pending, and `cancel` removes an exact non-head
ticket. The trusted host may cancel only an abandoned request that never
launched; cancellation of the head is refused.

The normal handoff is `reserve T`, independently run and supervise the owned
workload, then only after independently proving its whole lifetime ended,
`release T`, `dequeue T`, and `settle T`. `reserve` durably records the exact
head and blocks other operations. `release` moves that marker to a pending
barrier; it does not dequeue. `dequeue` requires the matching pending ticket,
and `settle` checks the completed queue journal and archives the barrier.
Other tickets and ordinary queue operations remain refused while the barrier
exists. These operations require the host to serialize callers and protect
the directory and its files. A reservation is not proof of capacity, workload
identity or termination. An unknown write, lost response, malformed marker or
interrupted transition requires trusted host reconciliation before dispatch;
none authorizes a retry or release by itself.

Writes use an fsynced `queue.intent`, an fsynced `queue.pending`, an atomic
rename and directory fsync. After syncing the new queue, the intent is renamed
to `queue.last` and the directory is synced again. A surviving intent or pending file blocks ordinary
operations. The intent contains both the original and target queue. Under the
same lock, explicit `recover` archives the intent only when it describes one
valid append or single removal and no pending file remains. If the queue equals
the target, recovery reports `fifo/recovered`; if it still equals the original,
recovery reports `fifo/aborted`. Both paths sync the visible queue, intent and
directory before archiving the intent, then sync the directory again. Neither
path applies the transition again or authorizes a workload retry: a trusted
host must reconcile the operation identity and any external workload separately.
After a lost recovery response, `fifo/no-intent` alone does not distinguish
an archived committed transition from an archived aborted one.
Invalid, older-format or unresolved intents remain blocked
for trusted-host reconciliation. The source requires a host-owned,
non-aliased directory; it does not make an untrusted directory safe or
establish power-loss durability on every filesystem.

## Not a governor or a deployment authorization

FIFO order starts at successful enqueue: contenders unable to take the lock
receive `fifo/busy`, so ordering among simultaneous arrivals is not provided.
The CLI has no access-control boundary of its own. A trusted host must retain
exclusive control of the directory and authorize dequeue only after proving
that the previous workload and its entire process tree have stopped. There is
no workload launcher, capacity accounting or resource limits, local recovery agent,
distributed fencing, Mac qualification, secure multi-user service, or
production installation. Do not use this component to admit build, deployment,
inference or bot workloads until the complete governor is qualified.

The [isolated Linux ARM64 evidence](evidence/awai151-fifo-20261103/) covers
actual filesystem writes, sequential order, duplicates, busy contenders and
synthetic leftover intent/pending and corrupt state. Its concurrent case is a
single race; its crash markers are manually created, not a crash or power-loss
test. No Mac mini or existing service was modified.

A historical [Linux process-kill observation](evidence/awai151-fifo-kill-20261103/)
paused instrumented source after the intent, pending write, rename, directory
sync and intent unlink. The uninstrumented component refused the surviving
intent in the first four cases and read the committed ticket in the fifth.
This does not test power loss, failed fsync, Mac, process trees or admission.

The [Darwin/Linux portability observation](evidence/awai151-fifo-portable-20261103/)
exercises byte writes and the completed-intent rename on one Mac with the
diagnostic FS2 runtime and rechecks the current source on Linux. It does not
qualify the Mac release runtime or authorize governor use.

The [Mac process-kill observation](evidence/awai151-fifo-mac-kill-20261103/)
repeats five write-boundary SIGKILL cases on one local Mac using instrumented
fixtures and the diagnostic FS2 runtime. The uninstrumented artifact refused
surviving intent, or read the committed ticket after intent archival. It does
not establish power-loss, workload termination, fairness or fleet readiness.

The same diagnostic binaries were also checked independently on two Mac minis
via Tailscale SSH. The [receipt](evidence/awai151-fifo-two-minis-20261103/)
records local FIFO, held-lock and five SIGKILL cases on each host. This was not
a cross-host queue, model inference or full governor test.

The [recovery observation](evidence/awai151-fifo-recovery-20261103/) checks
synthetic process kills, invalid journals and a repeat recovery using the
diagnostic Mac runtime, plus Linux ARM64 regression checks. It does not simulate power loss or authorize workload
admission. Earlier process-kill evidence used the prior intent format.

The [cancellation observation](evidence/awai151-fifo-cancel-20261103/)
checks queue order, head refusal, recovery and a single lock race on a local
Mac diagnostic runtime. Cancellation is not yet integrated with workload
identity or a production governor.

The earlier `RGQ3` reservation and release-barrier source was retested on one
local Mac with the diagnostic compiler and FS2 packager. The fixture exercised
old-format refusal, reserve/release/dequeue/settle, synthetic release sync
faults, process kills at injected points, recovery and a single contention
race; its source SHA-256 was
`d9afabd660eb58465a59f1565fd51d28bf169946eebd47aee807ff3e5b96633a`.
This does not establish real power-loss durability, host process supervision,
actual workload termination or capacity admission.

The current `RGQ4` format adds a durable settle-receipt barrier and refuses
older queue formats. No automatic migration is provided. The
[RGQ4 evidence](evidence/awai151-fifo-release-pending-20261104.md) records
the complete local diagnostic suite, injected settle interruption and sync
faults, and short tests on three Mac minis. These synthetic tests do not
qualify a production provider, actual power-loss recovery, a native workload
supervisor, complete process-tree termination or capacity admission.
