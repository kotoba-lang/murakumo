# FIFO with native fixed Kapitsa version launch

On 2026-11-05, the task-only Python observer combined the native RGQ4 FIFO,
experimental WP04 watcher and a native fixed-profile SETEXEC helper from
Amu. Each helper targets only the installed Kapitsa `--version` under its
corresponding target Mac home directory; it does not start a server or load a
model. The installed Kapitsa hash and signature and the three diagnostic
images' hashes/signatures were checked before launch.

On naphtali, benjamin and judah (Darwin ARM64, macOS 26.7.2, build 25G332),
the observer enqueued two test tickets and reserved the first, started the
helper in an allow-default fork-deny sandbox, and waited for its stopped
target. `ps` showed the expected Kapitsa path. After watcher readiness and a
heartbeat, the observer verified that FIFO front/dequeue/second reserve were
refused, resumed the child, and checked exact Kapitsa 0.2.26 version output,
the request-bound status 0 and the reaped direct-child exit 0. The hold still
blocked front and second reserve. Only explicit test-only release/dequeue/
settle then made the second ticket visible. Completed strict-SSH wrappers
reported their temporary directories absent.

The observer `scripts/native/check-fifo-kapitsa-version.py` hash was
`42ff0aa0e4d95336498b82c017a30f219d60dc8156ebbde1b4be3a5923429a52`;
the task-specific SSH wrapper `three-hosts.py` hash was
`775b428422e6949f3f96299b5a3bef1699a390949e9c96643546bea00f443bd0`.
They depend on local paths and separately pinned Amu diagnostic artifacts and
are not deployment tools. The native helpers and watcher do not authenticate
the target path atomically; Python remains the supervisor and the caller of
FIFO release. A version command does not establish inference, durable
recovery, whole-process-tree containment, a trusted host, resource governor,
or a bot/GitHub state turn.
