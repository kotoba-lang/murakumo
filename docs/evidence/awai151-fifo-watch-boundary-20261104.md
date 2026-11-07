# FIFO barrier and watcher loss diagnostic

On one local Darwin ARM64 host, the task-only observer
`scripts/native/check-fifo-watch-boundary.py` (SHA-256
`eab45c5e86e9a31eb1e32910ae4101efdb7b3147a84d0113a478951fd29a001d`)
used a signed native RGQ3 FIFO image (SHA-256
`4a910551efd81330f18bc0aa1c3fa2f6d1c932e77a62378d5a00830a75c05fdb`)
and signed native WP02 watcher (SHA-256
`1fdff12c551e847948096c96bf4037d91aee540310ca9947f43c9dda20d3869b`).
It enqueued two tickets, reserved the first, started its own `/bin/sleep 5`,
and bound the watcher to the child's sampled PID/start time. After validating
readiness and one heartbeat, it killed and reaped the watcher while the child
was still running. `front`, `dequeue` and the next ticket's `reserve` were
refused as `fifo/reservation-held`. The observer then killed and reaped its own
direct child; the hold remained. Only then did this disposable fixture manually
invoke release, dequeue and settle, after which the second ticket was visible.
Its temporary queue was removed. The fixture passed twice with these pins.

This checks two native candidates orchestrated by a test-only Python process;
there is no native supervisor connecting them. The watcher does not control the
FIFO and a caller can invoke release without proving termination. This sample
covers only one direct owned `sleep`, not descendant termination, durable host
reconciliation, process limits, supervisor death, PID reuse, a production FS2
provider, actual power loss, model inference or governor qualification. It does
not authorize deployment or automatic release based on watcher output.
