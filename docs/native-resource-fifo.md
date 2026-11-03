# Native local resource FIFO candidate

`kotoba/resource_fifo.kotoba` implements a bounded, local, durable queue of
up to 64 caller-supplied 64-character lowercase hex tickets. A trusted host
provisions a private directory containing a stable `queue.lock` and an initial
`queue` containing `RGQ1` followed by LF. The component takes a nonblocking
filesystem flock before every operation. `enqueue` appends a unique ticket,
`front` reads the head, and `dequeue` removes only the exact head. Malformed,
missing or duplicate state, full queue, unknown staging and contention are
refused. No refusal is retried in a polling loop.

Writes use an fsynced `queue.intent`, an fsynced `queue.pending`, an atomic
rename and directory fsync. A surviving intent or pending file blocks later
operations for trusted-host reconciliation. The source requires a host-owned,
non-aliased directory; it does not make an untrusted directory safe or
establish power-loss durability on every filesystem.

## Not a governor or a deployment authorization

FIFO order starts at successful enqueue: contenders unable to take the lock
receive `fifo/busy`, so ordering among simultaneous arrivals is not provided.
The CLI has no access-control boundary of its own. A trusted host must retain
exclusive control of the directory and authorize dequeue only after proving
that the previous workload and its entire process tree have stopped. There is
no workload launcher, resource reservation or limit, local recovery agent,
distributed fencing, Mac qualification, secure multi-user service, or
production installation. Do not use this component to admit build, deployment,
inference or bot workloads until the complete governor is qualified.

The [isolated Linux ARM64 evidence](evidence/awai151-fifo-20261103/) covers
actual filesystem writes, sequential order, duplicates, busy contenders and
synthetic leftover intent/pending and corrupt state. Its concurrent case is a
single race; its crash markers are manually created, not a crash or power-loss
test. No Mac mini or existing service was modified.

A later [Linux process-kill observation](evidence/awai151-fifo-kill-20261103/)
paused instrumented source after the intent, pending write, rename, directory
sync and intent unlink. The uninstrumented component refused the surviving
intent in the first four cases and read the committed ticket in the fifth.
This does not test power loss, failed fsync, Mac, process trees or admission.
