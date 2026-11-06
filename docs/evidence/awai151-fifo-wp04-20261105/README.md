# FIFO hold with the experimental WP04 watcher

On 2026-11-05 a task-only Python observer combined the current RGQ4 FIFO
entry point with the experimental WP04 native watcher. The FIFO component
SHA-256 was `bd23560f142c1e417662eca2f026c521c931be5ed48739c7976c91b10588c825`;
its selected-source diagnostic image SHA-256 was
`e0686d9f82fc3eb5f218a0e716d373ef3a0a9565b0e09f39bef4ce48e0ae9da7`.
The WP04 watcher image SHA-256 was
`ad32993a07f5e8b3db5d46f695c9d1a2b319974f145f13ebe3406c23810eba6e`.
Each image's hash and signature were checked. The watcher is an Amu diagnostic
candidate, not a released or qualified dependency.

On the local Mac and, over strict Tailscale SSH, naphtali, benjamin and judah
(Darwin ARM64, macOS 26.7.2, build 25G332), each fresh temporary queue held two
synthetic tickets and reserved the first. After watcher readiness and one
heartbeat, three independent cases were run: killing the watcher while the
owned sleep child remained alive, killing the child and observing status 9,
and letting it exit normally and observing status 0. Before each outcome the
FIFO refused front/dequeue and reservation of the second ticket; afterward,
front and reservation of the second ticket remained refused while held.
In the latter two cases the WP04 frame matched the parent's direct-child wait
result. The observer then manually performed release/dequeue/settle **only in
the test fixture**, after reaping that child; the second ticket became visible.
The completed remote wrappers reported their temporary directories absent.

`scripts/native/check-fifo-wp04-boundary.py`, SHA-256
`0000dd49cbc8f4a3b9a5bfb144702dc26882821f3a3434763aa50ffbe2686d8f`,
is the exact task-only observer. `build-fifo.py` and `three-hosts.py` record
the local build and SSH wrapper; their absolute paths and diagnostic tool
pins are not deployment instructions. The FIFO source was compiled by the
pinned diagnostic compiler
`920954769b8fdc51cf50dfcab5c44a4e56cf01f261806a153b21488ba81ac4e6`
and packager
`f726f9dd8a4f169187275797a74d55ad3c62cc5f681cd31f19e1a28d67834f8d`.
Each native FIFO/watcher subprocess was CPU-limited; there was no hard memory
cap or real inference.

This is Python orchestration of two native candidates, not a native supervisor.
The FIFO does not authenticate watcher output or verify child/descendant
termination before accepting release; an untrusted or buggy caller can still
release early. Direct-child reaping in this test is not proof that all
descendants, IPC-launched work or GPU allocations stopped. No supervisor
death, durable host reconciliation, power failure, resource-budget
enforcement, model, bot, or end-to-end governor was qualified.
