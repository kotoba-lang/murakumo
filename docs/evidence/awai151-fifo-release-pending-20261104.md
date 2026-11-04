# FIFO release-pending barrier

In the prior `RGQ2` candidate, release renamed the reservation away and then
synced the directory. If that sync failed or the process died after the rename,
the command could return unknown while `front` exposed the next action. A
caller was required to reconcile externally, but the FIFO did not retain a
barrier for that interval.

The `RGQ3` candidate renames the reservation to `queue.release.pending` and
keeps queue operations blocked. Only the matching ticket can dequeue while
this marker exists. After dequeue, the marker still blocks `front`. `settle`
requires an exact journal describing removal of that ticket, the matching
post-dequeue queue, no active transaction and successful syncs before moving
the marker to history. Recovery may reconcile a queue journal while the
release marker remains; an unresolved pending queue file still blocks settle.
A dequeue without the reservation/release sequence is refused. A trusted host
must independently prove the entire workload stopped *before* release; none
of these file checks proves process termination.

A lost or failed final settle response still requires host reconciliation: a
failure after the marker was renamed may leave it absent. At that point this
candidate has checked and synced the completed dequeue and its journal before
the rename, but it does not provide a general exactly-once transaction or a
trusted host. A host must not equate absent marker with a verified external
workload state. Paths, lock inode and writers must remain exclusively owned.
There is no automatic migration. Old `RGQ1` and `RGQ2` queues refuse on this
candidate; the prior signed `RGQ2` FIFO refused `RGQ3` in a local test.

On one local Apple Silicon Mac, the signed diagnostic FIFO passed reservation,
release-pending, matching dequeue and settle cases. A source-injected
post-release-rename sync failure returned unknown and left `front` blocked by
the pending marker. Killing a separately instrumented release process after
the rename likewise left `front` and enqueue blocked. In separate local cases,
a dequeue process was killed at each of five queue journal boundaries while
the release marker remained. `front` stayed blocked; the unresolved pending
file case refused settle, while the other cases could be reconciled and settled
under the test host's explicit control. A reservation guard-bypass mutant was
detected. Existing FIFO and legacy-format negative controls also passed.
These are synthetic process-kill and source-fault tests, not power-loss or
actual kernel fsync failure tests.

The same signed FIFO was tested on naphtali, benjamin and judah over
host-key-pinned Tailscale SSH. Each Darwin ARM64 build `25G332` passed the short
reserve, release-pending, dequeue and settle sequence and prior journal cases;
temporary files were removed. The new release/dequeue kill and sync-fault
cases were run locally only. No real workload, model or persistent service was
started.

Final source SHA-256:
`d9afabd660eb58465a59f1565fd51d28bf169946eebd47aee807ff3e5b96633a`;
signed FIFO SHA-256:
`4a910551efd81330f18bc0aa1c3fa2f6d1c932e77a62378d5a00830a75c05fdb`;
artifact manifest SHA-256:
`c9aafa3c5f20f0e691d6beb1936b566f19f5d5b53325084321d0297d569268b5`;
target observer SHA-256:
`15576784eab8fc774521c02078ef054136c67d4decaf50414c261145c5f8575c`.
The target artifact preceded a comment-only source change; the final local
rebuild reproduced the same signed image hash. The compiler and packager were
the earlier pinned diagnostics
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6` /
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
A production provider, process supervisor, full workload termination, resource
limits, durable host recovery, distributed fence and selfhost are unqualified.

## RGQ4 settle acknowledgement barrier

RGQ3 could expose the next ticket if settle moved the release marker but its
final directory-sync acknowledgement was lost. The RGQ4 candidate requires a
matching `queue.reservation.settled` receipt after the history marker is
synced. With neither active reservation nor release pending, missing,
mismatched, malformed or unsynchronizable history/receipt remains unknown.
Before the next reservation the receipt is moved to `.previous` and synced;
interruption while consuming it remains unknown. The explicit `settle-recover`
operation requires the exact ticket, current queue, durable dequeue journal,
absence of active transaction/hold/release and successful syncs before restoring
the receipt. It does not prove workload termination; that remains a trusted
host prerequisite. A stale journal after another queue operation may prevent
reconciliation and requires separate host handling. No automatic migration is
provided: RGQ1, RGQ2 and RGQ3 must be refused, and older callers must be
excluded.

A signed local native Darwin FS2 diagnostic tested a source-injected failure
immediately after moving the release marker: settle returned unknown and front
refused; the wrong ticket could not recover; the matching ticket recovered,
and the next reservation/release/dequeue/settle completed. The current FIFO
refused an RGQ3 queue. A separate history-guard bypass mutant exposed the next
ticket in the same unknown state, so the control distinguished the fix. The
source/image SHA-256 values were:

- current: `0c336627beaa716305fc894d547504ee207b51b7a32117bc63989f8623cb30a9` / `abd117f8a18503fd3404bc94ea765683d928a4b25239285076f1a273f6374d51`
- injected lost sync response: `ab05d2c9a27dec8b40c57db99d93c61d26529ffb27fc492ba97ead33a8929eaa` / `1d81531786efa4c7eb94fc7d631fb7023d6c8a784baa8fd7c46d45c9432f0367`
- history guard bypass: `a66efa69756519c6bf3a6e42a4f8ce19f8a4fa6c2117799d713ae301850125c6` / `2c2dacb62c422b807c0f0cdd6c15fa2877b0220949712d614535091ba990abfc`

Each native process had a three-second CPU cap. The repository fixture has
been updated for RGQ4 and includes the injected acknowledgement-loss and
bypass cases, but its complete suite was not run here. This is not a real
kernel fsync failure, power loss, multihost recovery, process supervisor or
qualified resource governor; do not deploy workloads on this evidence.

### Three-Mac RGQ4 diagnostic observation

On 2026-11-04, the source-bound RGQ4 diagnostic, the injected lost-sync image
and the history-guard bypass image above were sent to naphtali, benjamin and
judah over Tailscale SSH with strict previously pinned host keys. All three
reported Darwin kernel `25.6.0`, checked each image SHA-256 and strict code
signature, and passed the same short sequence: reserve, release, dequeue,
injected settle acknowledgement loss, blocked front, bypass control exposing
the next ticket, wrong-ticket refusal, exact-ticket recovery, next full
reservation and RGQ3 refusal. Each native process had a three-second CPU cap.
The outer staging directory was independently checked absent after cleanup;
the observer's own case directory used `TemporaryDirectory` cleanup.

The candidate source file SHA-256 was
`bd23560f142c1e417662eca2f026c521c931be5ed48739c7976c91b10588c825` and
`scripts/native/observe-fifo-settle-barrier.py` SHA-256 was
`8bbf46c4666d2dc543435088ddae74994066457a83ba694ffcb534bd8a46feda`.
This is an injected-result diagnostic on one operation sequence per host, not
real fsync failure, power loss, independent process death, full FIFO suite,
resource reservation, whole-workload lifetime, a distributed fence or governor
qualification. No model, build workload, persistent service or existing
workload was changed.

### RGQ4 reservation with lost watcher on three Macs

On 2026-11-04, naphtali, benjamin and judah each ran the pinned signed RGQ4
FIFO (`abd117f8a18503fd3404bc94ea765683d928a4b25239285076f1a273f6374d51`)
and WP02 watcher (`1fdff12c551e847948096c96bf4037d91aee540310ca9947f43c9dda20d3869b`)
over host-key-checked Tailscale SSH. The test-only Python observer
`scripts/native/check-fifo-watch-boundary.py` SHA-256
`4749bf963eba5e9ef032a5672ebe659a2c98e5160b6d8473f25a553b61007188`
enqueued two synthetic tickets and reserved the first, started an owned
`/bin/sleep`, bound a watcher to its observed PID/birth identity, read a ready
frame and heartbeat, then killed and reaped the watcher. In each case the
owned child remained alive and FIFO front/dequeue/second reserve refused. The
observer then killed and reaped its direct child; the reservation still held.
Only after that test-only observation did it explicitly release, dequeue and
settle, exposing the second ticket. Each native FIFO/watcher process had a
three-second CPU cap. The outer temporary staging directory was independently
checked absent after the test. No existing service or model was changed.

The observer made the supervision and reconciliation decisions in Python;
neither the watcher nor FIFO was integrated into a native host supervisor.
This one direct child does not cover grandchildren, daemonization, missed
fork/exec events, crashes, restart, authenticated resource accounting, memory
or GPU limits, distributed fencing or real workloads. The test therefore does
not qualify a governor or authorize persistent deployment.

### RGQ4 settle process-kill boundaries

On one local Darwin ARM64 host, the stored diagnostic
`scripts/native/check-fifo-settle-crash.py` (SHA-256
`20a24c7de6c2f052eebd3955871d7b45ec4bb420bb07e8388325ac0d76ca4305`)
built the current source and three source-instrumented pause variants with
the pinned diagnostic compiler/provider. A task-owned native FIFO process
was SIGKILLed separately just after release-marker rename, just after the
receipt write, and just after consumption of the receipt for the next reserve.
After rename or consumption, uninstrumented front returned unknown; the exact
prior ticket then reconciled, and the next reservation succeeded. After the
receipt write, uninstrumented front re-read and synced the visible receipt
before admitting the next ticket. A separate source-injected receipt-sync
failure returned unknown in that case. All three test children were reaped;
each native process had a three-second CPU cap.

The good image SHA-256 was
`abd117f8a18503fd3404bc94ea765683d928a4b25239285076f1a273f6374d51`;
rename, receipt, consume and receipt-sync-fault diagnostic image hashes were
`73fb4a53bbbe840fbcaf999972d4b08b469b61dcbb020cba278ed0b71250a857`,
`8b1b7cc66ed9a40518180c4a6572dfbfa0c7dcdb63e08149217328ca62903078`,
`616c95c8bc6ef6bf1189b109883b24b41c604df3994db7aad08d742682e90915`, and
`6f3adc8042a4529dbc97948a3a5dd299b9fe29b7d3d73522fbc0bd688091d6cb`.
The pause and failure are injected into source and the checks observe process
death on one running OS. This does not simulate kernel I/O faults, power loss,
real workloads, descendants, multi-host coordination or a qualified governor.
