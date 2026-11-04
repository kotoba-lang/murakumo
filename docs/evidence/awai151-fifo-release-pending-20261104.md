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
