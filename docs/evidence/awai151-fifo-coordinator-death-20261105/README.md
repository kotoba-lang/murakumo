# FIFO hold after coordinator death

On 2026-11-05, a task-only Python observer used the current RGQ4 FIFO and
experimental WP04 watcher to exercise coordinator death. FIFO source SHA-256
`bd23560f142c1e417662eca2f026c521c931be5ed48739c7976c91b10588c825`, signed
selected-source FIFO image SHA-256
`e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7`,
and signed watcher SHA-256
`ad32993a07f5e8b3db5d46f695c9d1a2b319974f145f13ebe3406c23810eba6e`
were pinned and signatures checked. The observer was
`scripts/native/check-fifo-coordinator-death.py`, SHA-256
`f3999a2710fd2e9a70e80dae809f4fa2532dd1e51aff2cd4575fa7ede09da891`.
The local run used the initial observer before its pre-readiness cleanup change;
the three hosts below ran the pinned version.

Each case created a private queue with two synthetic tickets and reserved the
first. A test coordinator launched its own eight-second `/bin/sleep`, bound
WP04 to its PID/start time, and forwarded readiness and one heartbeat. The
observer killed and reaped only its own coordinator, confirmed the direct
child still matched its earlier process identity, and observed that front,
dequeue and second reservation were refused. After the child and watcher were
no longer observed under their recorded identities, front and the next
reservation remained refused. Only an explicit **test-only** release,
dequeue and settle exposed the second ticket. The observer waited for the
short-lived orphan to disappear; it did not reap the orphan after reparenting.

The pinned observer passed on naphtali, benjamin and judah, using strict
Tailscale host-key SSH. Each reported Darwin ARM64 macOS 26.7.2 build
25G332 and its transfer wrapper reported its own temporary directory absent.
The observer also removed its own task queue after the recorded processes
were no longer observed. `three-hosts.py` SHA-256
`76d28480e6d62a9b5e4e68e7ae974f282f2596416105afa462d8b3bfb7caf76d`
contains local paths and is not a deployment command. Native helper processes
were CPU-limited; there was no hard memory cap and no model was loaded.

This is a Python test coordinator around diagnostic binaries, not a native
supervisor or production governor. It does not prove descendant or IPC work
stopped, kernel exit causes, resource enforcement, PID reuse immunity, crash
recovery after machine or power loss, or correctness of release based on
process disappearance. An untrusted caller could still invoke FIFO release
early. No inference service or existing service was modified.
