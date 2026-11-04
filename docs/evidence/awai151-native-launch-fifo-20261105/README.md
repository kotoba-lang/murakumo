# Native SETEXEC fixture with FIFO and watcher

On 2026-11-05, a task-only Python observer combined the current RGQ4 FIFO,
the experimental WP04 native watcher, and a newly emitted Darwin ARM64
native helper whose only target is `/usr/bin/true`. The helper uses a private
SETEXEC+START_SUSPENDED spawn attribute and does not itself install a sandbox
or supervise work. The observer launched it under an allow-default,
fork-deny sandbox. The helper's signed image SHA-256 was
`49a7b8c80c83730448b6465b06063abf01b4e6c573b0e87114483c2f516afa2b`;
the FIFO and watcher image pins are recorded in the prior
[WP04 FIFO diagnostic](../awai151-fifo-wp04-20261105/README.md).

On the local Mac and naphtali, benjamin and judah (Darwin ARM64, macOS 26.7.2,
build 25G332), the observer enqueued two synthetic tickets and reserved the
first. It waited for the native helper's target to stop, registered the
watcher, received an exact heartbeat and checked that front/dequeue/second
reservation were refused. It resumed the task-owned target, received status 0
and independently reaped the direct child with exit 0. The hold still blocked
front and second reservation; only the test observer's explicit
release/dequeue/settle made the second ticket visible. Remote wrappers
reported their temporary directories absent. No model or persistent service
was started.

The executed observer `scripts/native/check-fifo-native-launch.py` has SHA-256
`0c10da3aa4c4c9ab459093c2476c4f4996aac13e447d9083f3da15c418749d88`;
the SSH wrapper `three-hosts.py` hash is
`50ea1154d6172abb5efdc326603e9a19f18ba2d78d23b8852c212b330768e352`.
Both use diagnostic paths and artifacts. The underlying FIFO and watcher were
CPU-limited; the short `/usr/bin/true` child did not receive a separately
verified CPU cap. This is not a native supervisor: Python still orchestrates
the launch, watcher and release, the FIFO does not validate the watcher, and
direct-child reaping does not establish descendant or IPC termination. No
host crash, durable reservation recovery, actual inference or resource
governor qualification is established.
